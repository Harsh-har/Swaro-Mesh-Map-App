package no.nordicsemi.android.swaromapmesh;

import static no.nordicsemi.android.swaromapmesh.swajaui.Svg_Operations.SvgColorManager.COLOR_TRANSPARENT;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Matrix;
import android.graphics.Picture;
import android.graphics.RectF;
import android.graphics.drawable.PictureDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.OverScroller;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import com.caverock.androidsvg.SVG;
import com.caverock.androidsvg.SVGParseException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import java.io.File;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.io.InputStream;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import dagger.hilt.android.AndroidEntryPoint;
import no.nordicsemi.android.swaromapmesh.databinding.FragmentNetworkBinding;
import no.nordicsemi.android.swaromapmesh.node.NodeConfigurationActivity;
import no.nordicsemi.android.swaromapmesh.swajaui.DeviceOperations;
import no.nordicsemi.android.swaromapmesh.swajaui.Svg_Operations.DeviceInfo;
import no.nordicsemi.android.swaromapmesh.swajaui.Svg_Operations.SvgColorManager;
import no.nordicsemi.android.swaromapmesh.swajaui.Svg_Operations.SvgParsers;
import no.nordicsemi.android.swaromapmesh.utils.DeviceCodes;
import no.nordicsemi.android.swaromapmesh.utils.Utils;
import no.nordicsemi.android.swaromapmesh.viewmodels.ClientServerElementStore;
import no.nordicsemi.android.swaromapmesh.viewmodels.SharedViewModel;

@AndroidEntryPoint
public class NetworkFragment extends Fragment implements DeviceOperations.DeviceCallback {

    private static final String TAG = "NetworkFragment";

    // ── Zoom constants ────────────────────────────────────────────────────
    private static final float MAX_ZOOM           = 25f;
    private static final float DOUBLE_TAP_ZOOM    = 2.5f;
    private static final float TOUCH_TOLERANCE_PX = 20f;
    private static final float MIN_SVG_TOLERANCE  = 0.3f;
    private static final long  ANIMATION_DURATION = 280L;
    private static final int   FLING_DURATION     = 2000;
    private static final float TAP_MOVE_SLOP      = 10f;
    private static final long  TAP_MAX_DURATION   = 250;

    // ── Area / zoom lock state ────────────────────────────────────────────
    private float  areaLockedMinZoom  = -1f;
    private String areaLockedId       = null;
    private String currentFocusAreaId = null;
    private String pendingFocusAreaId = null;

    /** Cached union of all selection_layer bounds (= full floor plan rect). */
    private RectF floorPlanBounds = null;

    // ── UI ────────────────────────────────────────────────────────────────
    private FragmentNetworkBinding binding;
    private boolean         mAutoSetupInProgress = false;
    private SharedViewModel mViewModel;

    // ── Threading ─────────────────────────────────────────────────────────
    private final ExecutorService loadExecutor   = Executors.newSingleThreadExecutor();
    private final ExecutorService renderExecutor = Executors.newSingleThreadExecutor();
    private final Handler         mainHandler    = new Handler(Looper.getMainLooper());
    private Future<?> pendingRender;

    // ── Data ──────────────────────────────────────────────────────────────
    private final Map<String, DeviceInfo>   deviceMap             = new LinkedHashMap<>();
    private final Map<String, Set<String>>  iconToDeviceRelations = new HashMap<>();
    private String selectedDeviceId;

    // ── Helper classes ────────────────────────────────────────────────────
    private final SvgParsers      svgParser    = new SvgParsers();
    private final SvgColorManager colorManager = new SvgColorManager();

    // ── SVG state ─────────────────────────────────────────────────────────
    private SVG      currentSvg;
    private Document svgDocument;

    // ── Zoom & pan ────────────────────────────────────────────────────────
    private final Matrix  matrix       = new Matrix();
    private final float[] matrixValues = new float[9];
    private float   minZoom         = 1f;
    private float   lastTouchX, lastTouchY;
    private boolean isDragging      = false;
    private int     activePointerId = MotionEvent.INVALID_POINTER_ID;

    // ── Gesture detectors ─────────────────────────────────────────────────
    private ScaleGestureDetector scaleDetector;
    private GestureDetector      gestureDetector;
    private OverScroller         scroller;
    private VelocityTracker      velocityTracker;
    private ValueAnimator        flingAnimator;
    private ValueAnimator        zoomAnimator;

    // ── Tap helpers ───────────────────────────────────────────────────────
    private float   tapDownX, tapDownY;
    private long    tapDownTime;
    private boolean hasMoved      = false;
    // Tracks whether the current gesture involved 2+ fingers.
    // When true, fling is suppressed so pinch-lift never jerks the map.
    private boolean wasMultiTouch = false;

    private boolean mIsAddDeviceMode = false;
    private boolean mIsMoveMode = false;
    private String mMovingDeviceId = null;
    private String mSelectedCategory = DeviceCodes.CONTROL_NODE;

    private DeviceOperations deviceOperations;

    // ══════════════════════════════════════════════════════════════════════
    //  LIFECYCLE
    // ══════════════════════════════════════════════════════════════════════

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding    = FragmentNetworkBinding.inflate(inflater, container, false);
        mViewModel = new ViewModelProvider(requireActivity()).get(SharedViewModel.class);
        deviceOperations = new DeviceOperations(requireContext(), this);
        setupZoomAndPan();
        setupAddDeviceLogic();
        observeViewModel();
        loadInitialSvg();
        return binding.getRoot();
    }

    private void loadInitialSvg() {
        File internalSvg = new File(requireContext().getFilesDir(), "modified_map.svg");
        if (internalSvg.exists()) {
            loadSvgFromUri(Uri.fromFile(internalSvg));
        } else {
            loadSvgFromAssets("office.svg");
        }
    }

    private void setupAddDeviceLogic() {
        binding.fabAddDevice.setOnClickListener(v -> deviceOperations.showCategorySelectionDialog());
        binding.btnCancelAdd.setOnClickListener(v -> {
            if (mIsMoveMode) exitMoveMode();
            else exitAddDeviceMode();
        });
        binding.btnSaveDevice.setOnClickListener(v -> {
            if (mIsMoveMode) {
                float[] iconCoords = getDraggableIconCoords();
                float[] svgCoords = touchToSvgCoords(iconCoords[0], iconCoords[1]);
                deviceOperations.moveDeviceInSvg(mMovingDeviceId, svgCoords[0], svgCoords[1], svgDocument, svgParser);
                exitMoveMode();
            } else {
                deviceOperations.showDeviceInfoDialog(mSelectedCategory, deviceMap, svgDocument, svgParser);
            }
        });

        binding.draggableIcon.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_MOVE) {
                View parent = (View) v.getParent();
                if (parent == null) return true;
                
                int[] loc = new int[2];
                parent.getLocationOnScreen(loc);
                
                // Set position relative to parent container, centered on touch
                v.setX(event.getRawX() - loc[0] - v.getWidth() / 2f);
                v.setY(event.getRawY() - loc[1] - v.getHeight() / 2f);
            } else if (event.getAction() == MotionEvent.ACTION_UP) {
                v.performClick();
            }
            return true;
        });
    }

    @Override
    public void onDataChanged() {
        if (svgDocument == null) return;
        Map<String, DeviceInfo> newDevices = svgParser.extractDevices(svgDocument);
        deviceMap.clear();
        deviceMap.putAll(newDevices);
        Map<String, Set<String>> newRelations = svgParser.parseRelations(svgDocument, deviceMap);
        iconToDeviceRelations.clear();
        iconToDeviceRelations.putAll(newRelations);

        colorManager.init(svgDocument, svgParser, deviceMap);
        refreshColors();
        reRenderSvg();
    }

    @Override
    public void enterAddMode(String category) {
        mSelectedCategory = category;
        enterAddDeviceMode();
    }

    @Override
    public void exitAddMode() {
        exitAddDeviceMode();
    }

    @Override
    public float[] getDraggableIconCoords() {
        if (binding == null) return new float[]{0, 0};
        // Get center of icon relative to the container
        float centerX = binding.draggableIcon.getX() + binding.draggableIcon.getWidth() / 2f;
        float centerY = binding.draggableIcon.getY() + binding.draggableIcon.getHeight() / 2f;
        
        // Convert to be relative to the svgView (which is the coordinate system touchToSvgCoords expects)
        float relativeX = centerX - binding.svgView.getLeft();
        float relativeY = centerY - binding.svgView.getTop();
        
        return new float[]{relativeX, relativeY};
    }

    @Override
    public String getCurrentFocusAreaId() {
        return currentFocusAreaId;
    }

    private void enterAddDeviceMode() {
        mIsAddDeviceMode = true;
        binding.addDeviceToolbar.setVisibility(View.VISIBLE);
        binding.draggableIcon.setVisibility(View.VISIBLE);
        binding.fabAddDevice.setVisibility(View.GONE);

        // Center the icon after layout pass
        binding.draggableIcon.post(() -> {
            if (binding == null) return;
            float viewWidth = binding.container.getWidth();
            float viewHeight = binding.container.getHeight();
            if (viewWidth > 0 && viewHeight > 0) {
                binding.draggableIcon.setX(viewWidth / 2f - binding.draggableIcon.getWidth() / 2f);
                binding.draggableIcon.setY(viewHeight / 2f - binding.draggableIcon.getHeight() / 2f);
            }
        });
    }

    private void exitAddDeviceMode() {
        mIsAddDeviceMode = false;
        binding.addDeviceToolbar.setVisibility(View.GONE);
        binding.draggableIcon.setVisibility(View.GONE);
        binding.fabAddDevice.setVisibility(View.VISIBLE);
    }

    private void exitMoveMode() {
        mIsMoveMode = false;
        mMovingDeviceId = null;
        binding.addDeviceToolbar.setVisibility(View.GONE);
        binding.draggableIcon.setVisibility(View.GONE);
        binding.fabAddDevice.setVisibility(View.VISIBLE);
        
        // Restore original visibility in SVG
        refreshColors();
        reRenderSvg();
    }

    private void observeViewModel() {

        mViewModel.isAutoSetupInProgress().observe(getViewLifecycleOwner(), inProgress -> {
            if (binding == null) return;

            boolean wasInProgress = mAutoSetupInProgress;
            mAutoSetupInProgress = Boolean.TRUE.equals(inProgress);

            if (mAutoSetupInProgress) {
                binding.autoSetupOverlay.setVisibility(View.VISIBLE);
                binding.progressBar.setVisibility(View.VISIBLE);
                binding.svgView.setOnTouchListener(null);
            } else {
                binding.autoSetupOverlay.setVisibility(View.GONE);
                binding.progressBar.setVisibility(View.GONE);
                binding.svgView.setOnTouchListener(this::handleTouch);

                if (wasInProgress) {
                    Toast.makeText(requireContext(), "All process is complete", Toast.LENGTH_SHORT).show();
                }

                if (wasInProgress && svgDocument != null && !deviceMap.isEmpty()) {
                    selectedDeviceId = null;
                    refreshColors();
                    reRenderSvg();
                }
            }
        });

        mViewModel.getFocusAreaId().observe(getViewLifecycleOwner(), areaId -> {
            if (areaId == null || areaId.isEmpty()) return;

            pendingFocusAreaId = areaId;
            mViewModel.setFocusAreaId(null);

            if (svgDocument != null && !svgParser.areaMap.isEmpty()) {
                zoomToArea(areaId);
                pendingFocusAreaId = null;
            }
        });

        mViewModel.getProvisionedDeviceIds().observe(getViewLifecycleOwner(), ids -> {
            if (binding == null || svgDocument == null || deviceMap.isEmpty()) return;
            if (mAutoSetupInProgress) return;
            selectedDeviceId = null;
            if (currentFocusAreaId == null && areaLockedId == null) {
                colorManager.restoreAllAreas(
                        svgParser.selectionLayerElements, svgParser.selectionLayerBounds);
            }
            refreshColors();
            reRenderSvg();
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        if (svgDocument == null || deviceMap.isEmpty()) return;
        if (mAutoSetupInProgress) return;
        selectedDeviceId = null;
        if (currentFocusAreaId == null && areaLockedId == null) {
            colorManager.restoreAllAreas(
                    svgParser.selectionLayerElements, svgParser.selectionLayerBounds);
        }
        colorManager.forceResnapshotAllDevices(deviceMap);
        refreshColors();
        reRenderSvg();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (flingAnimator   != null) flingAnimator.cancel();
        if (zoomAnimator    != null) zoomAnimator.cancel();
        if (velocityTracker != null) { velocityTracker.recycle(); velocityTracker = null; }
        if (pendingRender   != null) pendingRender.cancel(true);
        loadExecutor.shutdownNow();
        renderExecutor.shutdownNow();
        binding = null;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  BACK PRESS
    // ══════════════════════════════════════════════════════════════════════

    public boolean handleBackPress() {
        if (areaLockedId != null) {
            areaLockedId       = null;
            areaLockedMinZoom  = -1f;
            currentFocusAreaId = null;
            colorManager.restoreAllAreas(
                    svgParser.selectionLayerElements, svgParser.selectionLayerBounds);
            refreshColors();
            reRenderSvg();
            binding.svgView.post(() -> fitFloorPlanToView(true));
            return true;
        }
        if (currentFocusAreaId != null) {
            currentFocusAreaId = null;
            colorManager.restoreAllAreas(
                    svgParser.selectionLayerElements, svgParser.selectionLayerBounds);
            refreshColors();
            reRenderSvg();
            return true;
        }
        if (selectedDeviceId != null) {
            deselectCurrentDevice();
            return true;
        }
        return false;
    }

    public boolean isAreaZoomed() { return areaLockedId != null; }

    // ══════════════════════════════════════════════════════════════════════
    //  PRIVATE INNER TYPES
    // ══════════════════════════════════════════════════════════════════════

    private static class RelationHitResult {
        final String iconId;
        final String tappedDeviceId;
        RelationHitResult(String iconId, String tappedDeviceId) {
            this.iconId         = iconId;
            this.tappedDeviceId = tappedDeviceId;
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  SVG LOADING
    // ══════════════════════════════════════════════════════════════════════

    private void loadSvgFromAssets(String assetFileName) {
        showLoading(true);
        loadExecutor.execute(() -> {
            try {
                String[] assets = requireContext().getAssets().list("");
                boolean found = false;
                if (assets != null)
                    for (String a : assets)
                        if (a.equals(assetFileName)) { found = true; break; }

                if (!found) {
                    mainHandler.post(() -> {
                        showLoading(false);
                        showPlaceholder(true);
                        Toast.makeText(requireContext(),
                                "SVG not found: " + assetFileName, Toast.LENGTH_LONG).show();
                    });
                    return;
                }

                InputStream is1 = requireContext().getAssets().open(assetFileName);
                Document    doc = svgParser.parseDocument(is1);
                is1.close();

                InputStream is2 = requireContext().getAssets().open(assetFileName);
                SVG         svg = SVG.getFromInputStream(is2);
                is2.close();

                if (doc != null) svgParser.parseViewBox(doc);
                Map<String, DeviceInfo>  devices   = svgParser.extractDevices(doc);
                Map<String, Set<String>> relations = svgParser.parseRelations(doc, devices);
                svgParser.parseSelectionLayer(doc);
                floorPlanBounds = null;

                mainHandler.post(() -> onSvgLoaded(svg, doc, devices, relations));
            } catch (SVGParseException e) {
                Log.e(TAG, "SVG parse error", e);
                mainHandler.post(() -> { showLoading(false); showPlaceholder(true); });
            } catch (Exception e) {
                Log.e(TAG, "Error loading SVG from assets", e);
                mainHandler.post(() -> { showLoading(false); showPlaceholder(true); });
            }
        });
    }

    private void loadSvgFromUri(Uri uri) {
        showLoading(true);
        loadExecutor.execute(() -> {
            try {
                String      uriStr = uri.toString();
                InputStream is1, is2;
                if (uriStr.startsWith("file://")) {
                    File f = new File(uri.getPath());
                    is1 = new java.io.FileInputStream(f);
                    is2 = new java.io.FileInputStream(f);
                } else {
                    is1 = requireContext().getContentResolver().openInputStream(uri);
                    is2 = requireContext().getContentResolver().openInputStream(uri);
                }
                if (is1 == null || is2 == null) {
                    mainHandler.post(() -> { showLoading(false); showPlaceholder(true); });
                    return;
                }
                SVG      svg = SVG.getFromInputStream(is1); is1.close();
                Document doc = svgParser.parseDocument(is2); is2.close();

                if (doc != null) svgParser.parseViewBox(doc);
                Map<String, DeviceInfo>  devices   = svgParser.extractDevices(doc);
                Map<String, Set<String>> relations = svgParser.parseRelations(doc, devices);
                svgParser.parseSelectionLayer(doc);
                floorPlanBounds = null;

                mainHandler.post(() -> onSvgLoaded(svg, doc, devices, relations));
            } catch (Exception e) {
                Log.e(TAG, "Error loading SVG from URI", e);
                mainHandler.post(() -> { showLoading(false); showPlaceholder(true); });
            }
        });
    }

    private void debugDrawTolerance() {
        if (svgDocument == null) return;
        try {
            Element root = svgDocument.getDocumentElement();
            Node devicesGroup = svgParser.findElementById(root, "Devices");

            Element debugGroup = svgDocument.createElement("g");
            debugGroup.setAttribute("id", "debug_tolerance_layer");

            // Accurate shapes for ALL physical devices in "Devices" group (Purple)
            if (devicesGroup instanceof Element) {
                addPhysicalDevicesToDebug((Element) devicesGroup, debugGroup);
            }

            if (devicesGroup != null) {
                root.insertBefore(debugGroup, devicesGroup);
            } else {
                root.appendChild(debugGroup);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error drawing debug tolerance", e);
        }
    }

    private void addPhysicalDevicesToDebug(Element parent, Element debugGroup) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (!(node instanceof Element)) continue;
            Element el = (Element) node;

            String id = el.getAttribute("id");
            String tag = el.getTagName().toLowerCase().replace("svg:", "");

            // If it's a leaf group or a direct drawable element with an ID, add it
            if (!id.isEmpty() && (!"g".equals(tag) || !hasDirectGChild(el))) {
                Element clone = (Element) el.cloneNode(true);
                clone.setAttribute("pointer-events", "none");
                applyDebugAppearance(clone, "#E040FB"); // Purple for physical devices
                debugGroup.appendChild(clone);
            } else if ("g".equals(tag)) {
                // Keep searching for leaves
                addPhysicalDevicesToDebug(el, debugGroup);
            }
        }
    }

    private boolean hasDirectGChild(Element el) {
        NodeList children = el.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node c = children.item(i);
            if (c instanceof Element && "g".equals(((Element) c).getTagName().toLowerCase().replace("svg:", ""))) {
                return true;
            }
        }
        return false;
    }

    private void applyDebugAppearance(Element el, String color) {
        el.removeAttribute("style");
        el.removeAttribute("display");
        el.setAttribute("fill", "none");
        el.setAttribute("stroke", color);
        el.setAttribute("stroke-width", "0.2"); // Even thinner for accurate shape
        el.setAttribute("stroke-dasharray", "1,1");

        NodeList children = el.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element) {
                applyDebugAppearance((Element) children.item(i), color);
            }
        }
    }

    private void addDebugRect(Element parent, RectF rect, String color) {
        Element r = svgDocument.createElement("rect");
        r.setAttribute("x", String.valueOf(rect.left));
        r.setAttribute("y", String.valueOf(rect.top));
        r.setAttribute("width", String.valueOf(rect.width()));
        r.setAttribute("height", String.valueOf(rect.height()));
        r.setAttribute("fill", "none"); // Remove fill to avoid "collaboration" mess
        r.setAttribute("stroke", color);
        r.setAttribute("stroke-width", "0.5");
        r.setAttribute("stroke-dasharray", "2,1"); // Dashed line for better visibility
        r.setAttribute("pointer-events", "none");
        parent.appendChild(r);
    }

    private void onSvgLoaded(SVG svg, Document document,
                             Map<String, DeviceInfo>  devices,
                             Map<String, Set<String>> relations) {
        currentSvg  = svg;
        svgDocument = document;
        deviceMap.clear();
        deviceMap.putAll(devices);
        iconToDeviceRelations.clear();
        iconToDeviceRelations.putAll(relations);

        colorManager.init(document, svgParser, deviceMap);
        // debugDrawTolerance(); // Show tolerance areas for debugging
        refreshColors();
        renderSvg(svg, true);
        showLoading(false);
    }

    // ══════════════════════════════════════════════════════════════════════
    //  COLOR REFRESH
    // ══════════════════════════════════════════════════════════════════════

    private void refreshColors() {
        SharedPreferences prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE);
        boolean showProvisioned = prefs.getBoolean("show_device_filter", false);

        colorManager.refreshAllColors(
                deviceMap,
                getProvisionedSet(),
                getAddressedSet(),
                selectedDeviceId,
                mMovingDeviceId,
                iconToDeviceRelations,
                currentFocusAreaId,
                showProvisioned
        );
    }

    private Set<String> getAddressedSet() {
        Set<String> addressed = new HashSet<>();
        SharedPreferences prefs = requireContext().getSharedPreferences("device_address_prefs", Context.MODE_PRIVATE);
        Map<String, ?> all = prefs.getAll();
        for (String key : all.keySet()) {
            if (key.startsWith("address_")) {
                String val = String.valueOf(all.get(key));
                if (val != null && !val.isEmpty()) {
                    // key is "address_device_id", we need "device_id"
                    addressed.add(key.substring("address_".length()));
                }
            }
        }
        return addressed;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  AREA ZOOM
    // ══════════════════════════════════════════════════════════════════════

    private void zoomToArea(String areaId) {
        if (areaId == null || areaId.isEmpty()) return;

        RectF areaBounds = svgParser.selectionLayerBounds.get(areaId);
        String finalAreaId = areaId;

        // 1. Try exact match in areaMap or find element directly
        if (areaBounds == null) {
            Element areaEl = svgParser.findElementById(svgDocument.getDocumentElement(), areaId);
            if (areaEl != null) {
                areaBounds = svgParser.computeBounds(areaEl);
                Log.d(TAG, "zoomToArea: Found area element directly by ID: " + areaId);
            }
        }

        if (areaBounds == null) {
            List<String> iconIds = svgParser.areaMap.get(areaId);
            if (iconIds != null && !iconIds.isEmpty()) {
                areaBounds = calculateAreaBoundsFromIcons(iconIds);
                Log.d(TAG, "zoomToArea: Calculated bounds from " + iconIds.size() + " icons for: " + areaId);
            }
        }

        // 2. Fuzzy match fallback
        if (areaBounds == null) {
            Log.d(TAG, "zoomToArea: No exact match for '" + areaId + "', trying robust fuzzy match...");

            // Try fuzzy selection layer
            for (Map.Entry<String, RectF> entry : svgParser.selectionLayerBounds.entrySet()) {
                if (svgParser.isFuzzyMatch(entry.getKey(), areaId)) {
                    areaBounds = entry.getValue();
                    finalAreaId = entry.getKey();
                    Log.d(TAG, "zoomToArea: Fuzzy match found in selectionLayer: " + finalAreaId);
                    break;
                }
            }

            // Try fuzzy element search
            if (areaBounds == null) {
                Element root = svgDocument.getDocumentElement();
                NodeList allGroups = root.getElementsByTagName("g");
                for (int i = 0; i < allGroups.getLength(); i++) {
                    Element el = (Element) allGroups.item(i);
                    String id = el.getAttribute("id");
                    if (svgParser.isFuzzyMatch(id, areaId)) {
                        areaBounds = svgParser.computeBounds(el);
                        finalAreaId = id;
                        Log.d(TAG, "zoomToArea: Fuzzy match found room element: " + finalAreaId);
                        break;
                    }
                }
            }

            // Try fuzzy area map icons fallback
            if (areaBounds == null) {
                for (Map.Entry<String, List<String>> entry : svgParser.areaMap.entrySet()) {
                    if (svgParser.isFuzzyMatch(entry.getKey(), areaId)) {
                        areaBounds = calculateAreaBoundsFromIcons(entry.getValue());
                        if (areaBounds != null) {
                            finalAreaId = entry.getKey();
                            Log.d(TAG, "zoomToArea: Fuzzy match found icons in areaMap: " + finalAreaId);
                            break;
                        }
                    }
                }
            }
        }

        if (areaBounds == null || areaBounds.isEmpty()) {
            Log.w(TAG, "zoomToArea: Could not find bounds for '" + areaId + "'");
            return;
        }

        Log.d(TAG, "zoomToArea '" + finalAreaId + "' → " + areaBounds);
        currentFocusAreaId = finalAreaId;

        colorManager.dimOtherAreas(
                finalAreaId,
                svgParser.selectionLayerElements,
                svgParser.selectionLayerBounds,
                new RectF(areaBounds));
        refreshColors();
        reRenderSvg();

        focusOnBounds(areaBounds, finalAreaId, true);
    }

    private RectF calculateAreaBoundsFromIcons(List<String> iconIds) {
        if (iconIds == null || iconIds.isEmpty()) return null;
        RectF bounds = null;
        for (String iconId : iconIds) {
            DeviceInfo info = deviceMap.get(iconId);
            if (info != null && info.bounds != null) {
                if (bounds == null) bounds = new RectF(info.bounds);
                else bounds.union(info.bounds);
            }
        }
        return bounds;
    }

    private void focusOnBounds(RectF bounds, String areaIdToLock, boolean animate) {
        if (binding == null) return;
        
        Runnable doZoom = () -> {
            float vW = binding.svgView.getWidth();
            float vH = binding.svgView.getHeight();
            if (vW <= 0 || vH <= 0) {
                mainHandler.postDelayed(() -> focusOnBounds(bounds, areaIdToLock, animate), 150);
                return;
            }
            float padding = 30f; // Slightly more padding
            RectF padded = new RectF(bounds);
            padded.inset(-padding, -padding);

            float scaleX = vW / padded.width();
            float scaleY = vH / padded.height();
            float targetScale = Math.min(MAX_ZOOM, Math.max(minZoom, Math.min(scaleX, scaleY)));

            float cx = padded.centerX() - svgParser.vbX;
            float cy = padded.centerY() - svgParser.vbY;
            float transX = vW / 2f - cx * targetScale;
            float transY = vH / 2f - cy * targetScale;

            if (animate) {
                // Clear any existing lock so we can animate scale smoothly
                areaLockedId = null;
                areaLockedMinZoom = -1f;
                
                animateToMatrix(targetScale, transX, transY);
                
                // Lock after animation finishes
                mainHandler.postDelayed(() -> {
                    if (areaIdToLock != null) {
                        areaLockedId = areaIdToLock;
                        areaLockedMinZoom = targetScale;
                        clampMatrix();
                        binding.svgView.setImageMatrix(matrix);
                    }
                }, ANIMATION_DURATION + 50);
            } else {
                if (areaIdToLock != null) {
                    areaLockedId = areaIdToLock;
                    areaLockedMinZoom = targetScale;
                }
                matrix.reset();
                matrix.postScale(targetScale, targetScale);
                matrix.postTranslate(transX, transY);
                clampMatrix();
                binding.svgView.setImageMatrix(matrix);
            }
        };

        if (binding.svgView.getDrawable() != null)
            binding.svgView.post(doZoom);
        else
            mainHandler.postDelayed(() -> binding.svgView.post(doZoom), 200);
    }

    private void exitAreaZoom() {
        areaLockedId      = null;
        areaLockedMinZoom = -1f;
        colorManager.restoreAllAreas(
                svgParser.selectionLayerElements, svgParser.selectionLayerBounds);
        refreshColors();
        reRenderSvg();
        binding.svgView.post(() -> fitFloorPlanToView(true));
    }

    // ══════════════════════════════════════════════════════════════════════
    //  PROVISIONED HELPERS
    // ══════════════════════════════════════════════════════════════════════

    private boolean isProvisioned(String deviceId) {

        if (ClientServerElementStore.isProvisioned(deviceId)) return true;
        
        // Smarter check: check if any version of this device (shared binding key) is provisioned
        String bindingKey = svgParser.extractRelationKey(deviceId);
        if (bindingKey == null) return false;
        
        for (String pid : ClientServerElementStore.getProvisionedKeys()) {
            if (bindingKey.equals(svgParser.extractRelationKey(pid))) return true;
        }
        return false;
    }

    private String getTrueProvisionedId(String deviceId) {
        if (ClientServerElementStore.isProvisioned(deviceId)) return deviceId;
        
        String bindingKey = svgParser.extractRelationKey(deviceId);
        if (bindingKey == null) return deviceId;
        
        for (String pid : ClientServerElementStore.getProvisionedKeys()) {
            if (bindingKey.equals(svgParser.extractRelationKey(pid))) return pid;
        }
        return deviceId;
    }

    private Set<String> getProvisionedSet() {
        return ClientServerElementStore.getProvisionedKeys();
    }

    // ══════════════════════════════════════════════════════════════════════
    //  FLOOR PLAN FIT
    //  FIX: Uses vbW/vbH (viewBox dimensions) instead of drawable intrinsic
    //       size so the boundary is correct even without a selection_layer.
    // ══════════════════════════════════════════════════════════════════════

    private void fitFloorPlanToView(boolean animate) {
        if (binding == null || binding.svgView.getDrawable() == null) return;
        float vW = binding.svgView.getWidth();
        float vH = binding.svgView.getHeight();
        if (vW <= 0 || vH <= 0) return;

        RectF fp = getFloorPlanBounds();
        if (fp == null || fp.isEmpty()) {
            // ── FIX: use SVG viewBox dimensions, not drawable intrinsic size ──
            float svgW  = svgParser.vbW > 0 ? svgParser.vbW : binding.svgView.getDrawable().getIntrinsicWidth();
            float svgH  = svgParser.vbH > 0 ? svgParser.vbH : binding.svgView.getDrawable().getIntrinsicHeight();
            float scale = Math.min(vW / svgW, vH / svgH);
            minZoom = scale;

            // FIX: The drawable already represents the viewBox, so its (0,0) is (vbX, vbY).
            // We only need to center the drawable within the view.
            float transX = (vW - svgW * scale) / 2f;
            float transY = (vH - svgH * scale) / 2f;

            Log.d(TAG, "fitFloorPlanToView (NoBounds): vSize=" + vW + "x" + vH + " svgSize=" + svgW + "x" + svgH + " vb=" + svgParser.vbX + "," + svgParser.vbY + " scale=" + scale + " trans=" + transX + "," + transY);

            if (animate) {
                animateToMatrix(scale, transX, transY);
            } else {
                matrix.reset();
                matrix.postScale(scale, scale);
                matrix.postTranslate(transX, transY);
                clampMatrix();
                binding.svgView.setImageMatrix(matrix);
            }
            return;
        }

        float padding = 16f;
        RectF padded  = new RectF(fp);
        padded.inset(-padding, -padding);

        float scale  = Math.min(vW / padded.width(), vH / padded.height());
        float cx     = padded.centerX() - svgParser.vbX;
        float cy     = padded.centerY() - svgParser.vbY;
        float transX = vW / 2f - cx * scale;
        float transY = vH / 2f - cy * scale;
        minZoom      = scale;

        if (animate) animateToMatrix(scale, transX, transY);
        else {
            matrix.reset();
            matrix.postScale(scale, scale);
            matrix.postTranslate(transX, transY);
            clampMatrix();
            binding.svgView.setImageMatrix(matrix);
        }
    }

    private RectF getFloorPlanBounds() {
        if (floorPlanBounds != null) return floorPlanBounds;
        if (svgParser.selectionLayerBounds.isEmpty()) return null;
        RectF union = null;
        for (RectF r : svgParser.selectionLayerBounds.values()) {
            if (union == null) union = new RectF(r);
            else union.union(r);
        }
        floorPlanBounds = union;
        return floorPlanBounds;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  SVG RENDERING
    // ══════════════════════════════════════════════════════════════════════

    private void renderSvg(SVG svg, boolean applyDomChanges) {
        try {
            int rW = Math.max(1, (int) svgParser.vbW);
            int rH = Math.max(1, (int) svgParser.vbH);
            Picture         picture  = svg.renderToPicture(rW, rH);
            PictureDrawable drawable = new PictureDrawable(picture);
            binding.svgView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            binding.svgView.setImageDrawable(drawable);
            binding.svgView.setVisibility(View.VISIBLE);
            binding.svgPlaceholder.setVisibility(View.GONE);
            if (!mAutoSetupInProgress) {
                binding.progressBar.setVisibility(View.GONE);
                binding.fabAddDevice.setVisibility(View.VISIBLE);
            }

            binding.svgView.post(() -> {
                fitFloorPlanToView(false);
                binding.svgView.invalidate();
                if (applyDomChanges) reRenderSvg();
                
                // If there's a pending area from navigation, zoom to it now
                if (pendingFocusAreaId != null) {
                    final String focusId = pendingFocusAreaId;
                    pendingFocusAreaId = null;
                    // Short delay to ensure everything is stable
                    mainHandler.postDelayed(() -> zoomToArea(focusId), 150);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Error rendering SVG", e);
            showPlaceholder(true);
        }
    }

    private void reRenderSvg() {
        if (svgDocument == null) return;
        if (pendingRender != null && !pendingRender.isDone()) pendingRender.cancel(true);

        final float[] snap = new float[9];
        matrix.getValues(snap);
        final Matrix frozenMatrix = new Matrix();
        frozenMatrix.setValues(snap);

        pendingRender = renderExecutor.submit(() -> {
            try {
                String svgStr = documentToString(svgDocument);
                if (svgStr.isEmpty()) return;
                SVG     svg     = SVG.getFromString(svgStr);
                int     rW      = Math.max(1, (int) svgParser.vbW);
                int     rH      = Math.max(1, (int) svgParser.vbH);
                Picture picture = svg.renderToPicture(rW, rH);
                PictureDrawable drawable = new PictureDrawable(picture);
                mainHandler.post(() -> {
                    if (binding == null) return;
                    binding.svgView.setImageDrawable(drawable);
                    binding.svgView.setImageMatrix(frozenMatrix);
                    binding.svgView.invalidate();
                });
            } catch (Exception e) {
                Log.e(TAG, "reRenderSvg error", e);
            }
        });
    }

    private String documentToString(Document doc) {
        if (doc == null) return "";
        try {
            Transformer  t  = TransformerFactory.newInstance().newTransformer();
            StringWriter sw = new StringWriter();
            t.transform(new DOMSource(doc), new StreamResult(sw));
            return sw.toString();
        } catch (Exception e) {
            Log.e(TAG, "documentToString error", e);
            return "";
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  UI STATE
    // ══════════════════════════════════════════════════════════════════════

    private void showPlaceholder(boolean show) {
        if (binding == null) return;
        if (show) {
            binding.svgPlaceholder.setVisibility(View.VISIBLE);
            binding.svgView.setVisibility(View.GONE);
            if (!mAutoSetupInProgress) {
                binding.progressBar.setVisibility(View.GONE);
                binding.fabAddDevice.setVisibility(View.VISIBLE);
            }
        } else {
            binding.svgPlaceholder.setVisibility(View.GONE);
            binding.svgView.setVisibility(View.VISIBLE);
        }
    }

    private void showLoading(boolean show) {
        if (binding == null) return;
        if (show) {
            binding.progressBar.setVisibility(View.VISIBLE);
            binding.svgPlaceholder.setVisibility(View.GONE);
            binding.svgView.setVisibility(View.GONE);
        } else {
            if (!mAutoSetupInProgress) {
                binding.progressBar.setVisibility(View.GONE);
                binding.fabAddDevice.setVisibility(View.VISIBLE);
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  TOUCH HANDLING
    // ══════════════════════════════════════════════════════════════════════

    private void setupZoomAndPan() {
        binding.svgView.setScaleType(ImageView.ScaleType.MATRIX);
        scroller = new OverScroller(requireContext(), new DecelerateInterpolator(2.5f));

        scaleDetector = new ScaleGestureDetector(requireContext(),
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {

                    // ── FIX: clamp factor before postScale to avoid drift ──
                    @Override
                    public boolean onScale(ScaleGestureDetector d) {
                        float cur    = getScale();
                        float factor = d.getScaleFactor();

                        // Clamp factor so scale never exceeds min/max
                        float next = cur * factor;
                        if (next < minZoom)  factor = minZoom  / cur;
                        if (next > MAX_ZOOM) factor = MAX_ZOOM / cur;

                        matrix.postScale(factor, factor, d.getFocusX(), d.getFocusY());

                        // During active pinch: only clamp translation, NOT scale
                        clampTranslationOnly();
                        binding.svgView.setImageMatrix(matrix);
                        return true;
                    }

                    // ── FIX: full clamp once gesture ends (imperceptible snap) ──
                    @Override
                    public void onScaleEnd(ScaleGestureDetector detector) {
                        clampMatrix();
                        binding.svgView.setImageMatrix(matrix);
                    }
                });

        gestureDetector = new GestureDetector(requireContext(),
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onDoubleTap(MotionEvent e) {
                        /*hasMoved = true;
                        if (areaLockedId != null) {
                            exitAreaZoom();
                        } else {
                            float target = getScale() > minZoom + 0.5f
                                    ? minZoom : DOUBLE_TAP_ZOOM;
                            animateZoomTo(target, e.getX(), e.getY());
                        }*/
                        return true;
                    }
                    @Override
                    public void onLongPress(MotionEvent e) {
                        hasMoved = true;
                        handleSvgLongPress(e.getX(), e.getY());
                    }

                    @Override
                    public boolean onFling(MotionEvent e1, MotionEvent e2,
                                           float vx, float vy) {
                        // Suppress fling if the gesture involved 2+ fingers.
                        // GestureDetector fires onFling from ACTION_UP even
                        // after a pinch, causing a jerk when fingers lift.
                        // if (wasMultiTouch) return false;
                        // startFling(vx, vy); // Disable map fling
                        return true;
                    }
                });

        binding.svgView.setOnTouchListener(this::handleTouch);
    }

    private void handleSvgLongPress(float touchX, float touchY) {
        if (svgDocument == null) return;
        float[] c = touchToSvgCoords(touchX, touchY);

        String hitId = findDeviceAt(c[0], c[1]);
        boolean isTechnician = true;
        RectF bounds = null;

        if (hitId != null) {
            bounds = deviceMap.get(hitId).bounds;
        } else {
            RelationHitResult relationHit = findRelationDeviceAt(c[0], c[1]);
            if (relationHit != null) {
                hitId = relationHit.tappedDeviceId;
                isTechnician = false;
                Element el = svgParser.findElementById(svgDocument.getDocumentElement(), hitId);
                if (el != null) bounds = svgParser.computeBounds(el);
            }
        }

        if (hitId == null || bounds == null) return;

        // ── Haptic feedback ───────────────────────────────────────────────
        binding.svgView.performHapticFeedback(
                android.view.HapticFeedbackConstants.LONG_PRESS);

        List<String> options = new java.util.ArrayList<>();
        if (isTechnician) {
            if (isProvisioned(hitId))
                options.add("Reset Node");
            options.add("Edit Device");
        }
        
        options.add("Move Device");

        options.add("Cancel");

        if (options.size() <= 1) return;

        final String finalHitId = hitId;
        final RectF finalBounds = bounds;
        
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(isTechnician ? deviceOperations.extractPureDeviceName(finalHitId) : "User Layer Element")
                .setItems(options.toArray(new String[0]), (dialog, which) -> {
                    String choice = options.get(which);
                    if ("Reset Node".equals(choice)) {
                        boolean isConnected = mViewModel.isConnectedToProxy().getValue() != null
                                && Boolean.TRUE.equals(mViewModel.isConnectedToProxy().getValue());
                        if (!isConnected) {
                            Toast.makeText(requireContext(), "Connect to proxy first", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        openNodeConfigForReset(finalHitId, deviceMap.get(finalHitId));
                    } else if ("Edit Device".equals(choice)) {
                        deviceOperations.showEditDeviceDialog(finalHitId, deviceMap.get(finalHitId), deviceMap, svgDocument);
                    } else if ("Move Device".equals(choice)) {
                        mIsMoveMode = true;
                        mMovingDeviceId = finalHitId;
                        
                        // Hide original in SVG while moving
                        refreshColors();
                        reRenderSvg();

                        binding.addDeviceToolbar.setVisibility(View.VISIBLE);
                        binding.draggableIcon.setVisibility(View.VISIBLE);
                        binding.fabAddDevice.setVisibility(View.GONE);
                        
                        // Wait for layout to get width/height for correct centering
                        binding.draggableIcon.post(() -> {
                            if (binding == null || mMovingDeviceId == null) return;
                            float[] svgPos = {finalBounds.centerX(), finalBounds.centerY()};
                            float[] screenPos = svgToTouchCoords(svgPos[0], svgPos[1]);
                            
                            float containerX = screenPos[0] + binding.svgView.getLeft();
                            float containerY = screenPos[1] + binding.svgView.getTop();
                            
                            binding.draggableIcon.setX(containerX - binding.draggableIcon.getWidth() / 2f);
                            binding.draggableIcon.setY(containerY - binding.draggableIcon.getHeight() / 2f);
                        });
                        
                    }
                })
                .show();
    }

    // ══════════════════════════════════════════════════════════════════════
//  EDIT DEVICE
// ══════════════════════════════════════════════════════════════════════



    /**
     * Same as checkDeviceConflict but skips the device being edited (self-check).
     */

    private void applyDeviceEdits(String iconId, DeviceInfo device,
                                  String newName, String newEid, String newRid) {
        if (svgDocument == null) return;
        try {
            Element iconEl = device.element;

            // ── 1. Update elementId in <metadata><elementId> ──────────────
            NodeList allChildren = iconEl.getElementsByTagName("*");
            boolean eidUpdated = false, ridUpdated = false;
            for (int i = 0; i < allChildren.getLength(); i++) {
                Node n = allChildren.item(i);
                if (!(n instanceof Element)) continue;
                Element el = (Element) n;
                String tag = el.getTagName().toLowerCase().replace("svg:", "");
                if ("elementid".equals(tag)) {
                    el.setTextContent(newEid);
                    eidUpdated = true;
                } else if ("reciveid".equals(tag) || "receiveid".equals(tag)) {
                    el.setTextContent(newRid);
                    ridUpdated = true;
                }
            }

            // ── If <metadata> tags didn't exist, create them ──────────────
            if (!eidUpdated || (!ridUpdated && !newRid.isEmpty())) {
                Element metadata = null;
                NodeList metaList = iconEl.getElementsByTagName("metadata");
                if (metaList.getLength() > 0) {
                    metadata = (Element) metaList.item(0);
                } else {
                    metadata = svgDocument.createElement("metadata");
                    iconEl.insertBefore(metadata, iconEl.getFirstChild());
                }
                if (!eidUpdated) {
                    Element eidNode = svgDocument.createElement("elementId");
                    eidNode.setTextContent(newEid);
                    metadata.appendChild(eidNode);
                }
                if (!ridUpdated && !newRid.isEmpty()) {
                    Element ridNode = svgDocument.createElement("reciveId");
                    ridNode.setTextContent(newRid);
                    metadata.appendChild(ridNode);
                }
            }

            // ── 2. The in-memory deviceMap is fully rebuilt below via
            //    svgParser.extractDevices(), so no direct field assignment needed.
            //    (DeviceInfo fields are final — updated implicitly via re-parse.)

            // ── 3. Save & refresh ─────────────────────────────────────────
            deviceOperations.saveSvgToInternal(svgDocument);

            Map<String, DeviceInfo> newDevices = svgParser.extractDevices(svgDocument);
            deviceMap.clear();
            deviceMap.putAll(newDevices);
            Map<String, Set<String>> newRelations = svgParser.parseRelations(svgDocument, deviceMap);
            iconToDeviceRelations.clear();
            iconToDeviceRelations.putAll(newRelations);

            colorManager.init(svgDocument, svgParser, deviceMap);
            refreshColors();
            reRenderSvg();

            Toast.makeText(requireContext(),
                    "Device updated: EID=" + newEid, Toast.LENGTH_SHORT).show();

        } catch (Exception e) {
            Log.e(TAG, "Error editing device", e);
            Toast.makeText(requireContext(), "Failed to update device", Toast.LENGTH_SHORT).show();
        }
    }


    private void openNodeConfigForReset(String deviceId, DeviceInfo device) {
        SharedPreferences prefs = requireContext()
                .getSharedPreferences("app_prefs", Context.MODE_PRIVATE);
        Uri    svgUri       = mViewModel.getSvgUri().getValue();
        String svgUriString = svgUri != null ? svgUri.toString() : "";
        String svgName      = prefs.getString("svg_name_" + svgUriString, "");

         String displayName = deviceOperations.extractPureDeviceName(deviceId);

        Intent intent = new Intent(requireContext(), NodeConfigurationActivity.class);
        intent.putExtra(Utils.EXTRA_SVG_DEVICE_ID,                   deviceId);
        intent.putExtra(DeviceDetailActivity.EXTRA_DEVICE_NAME,      displayName);
        intent.putExtra(DeviceDetailActivity.EXTRA_PURE_DEVICE_NAME, displayName);
        intent.putExtra(DeviceDetailActivity.EXTRA_ELEMENT_ID,       device.elementId);
        intent.putExtra(DeviceDetailActivity.EXTRA_RECEIVE_ID,       device.receiveId);
        intent.putExtra("svg_name",   svgName);
        intent.putExtra("AUTO_RESET", true);
        startActivity(intent);
    }

    private boolean handleTouch(View v, MotionEvent event) {
        if (mIsAddDeviceMode) return false; // Let draggableIcon handle it or ignore map touches

        if (velocityTracker == null) velocityTracker = VelocityTracker.obtain();
        velocityTracker.addMovement(event);
        gestureDetector.onTouchEvent(event);
        // scaleDetector.onTouchEvent(event); // Disable zoom in/out for now

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (flingAnimator != null) flingAnimator.cancel();
                if (zoomAnimator  != null) zoomAnimator.cancel();
                scroller.forceFinished(true);
                activePointerId = event.getPointerId(0);
                lastTouchX  = event.getX();
                lastTouchY  = event.getY();
                isDragging    = true;
                tapDownX      = event.getX();
                tapDownY      = event.getY();
                tapDownTime   = event.getEventTime();
                hasMoved      = false;
                wasMultiTouch = false;   // fresh gesture — assume single finger
                break;

            case MotionEvent.ACTION_POINTER_DOWN:
                isDragging    = false;
                hasMoved      = true;
                wasMultiTouch = true;    // 2+ fingers → block fling on lift
                break;

            case MotionEvent.ACTION_MOVE:
                if (!scaleDetector.isInProgress()) {
                    int idx = event.findPointerIndex(activePointerId);
                    if (idx == -1) { activePointerId = event.getPointerId(0); break; }
                    float dx  = event.getX(idx) - lastTouchX;
                    float dy  = event.getY(idx) - lastTouchY;
                    float tdx = event.getX(idx) - tapDownX;
                    float tdy = event.getY(idx) - tapDownY;
                    if (Math.sqrt(tdx * tdx + tdy * tdy) > TAP_MOVE_SLOP) hasMoved = true;
                    /*if (isDragging && (Math.abs(dx) > 0.5f || Math.abs(dy) > 0.5f)) {
                        matrix.postTranslate(dx, dy);
                        clampMatrix();
                        binding.svgView.setImageMatrix(matrix);
                    }*/
                    lastTouchX = event.getX(idx);
                    lastTouchY = event.getY(idx);
                }
                break;

            case MotionEvent.ACTION_UP:
                if (!hasMoved && !scaleDetector.isInProgress()
                        && (event.getEventTime() - tapDownTime) < TAP_MAX_DURATION)
                    handleSvgTap(tapDownX, tapDownY);
                activePointerId = MotionEvent.INVALID_POINTER_ID;
                isDragging      = false;
                hasMoved        = false;
                wasMultiTouch   = false;  // reset for next gesture
                if (velocityTracker != null) {
                    velocityTracker.recycle();
                    velocityTracker = null;
                }
                break;

            case MotionEvent.ACTION_CANCEL:
                activePointerId = MotionEvent.INVALID_POINTER_ID;
                isDragging      = false;
                hasMoved        = true;
                wasMultiTouch   = false;  // reset for next gesture
                if (velocityTracker != null) {
                    velocityTracker.recycle();
                    velocityTracker = null;
                }
                break;

            case MotionEvent.ACTION_POINTER_UP:
                int pi  = event.getActionIndex();
                int pid = event.getPointerId(pi);
                if (pid == activePointerId) {
                    int ni = (pi == 0) ? 1 : 0;
                    activePointerId = event.getPointerId(ni);
                    lastTouchX = event.getX(ni);
                    lastTouchY = event.getY(ni);
                }
                break;
        }
        return true;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  TAP / HIT TEST
    // ══════════════════════════════════════════════════════════════════════

    private void handleSvgTap(float touchX, float touchY) {
        if (svgDocument == null) return;
        float[] c = touchToSvgCoords(touchX, touchY);

        Log.d(TAG, "TAP-EVENT: touch=(" + touchX + "," + touchY + ") -> svg=(" + c[0] + "," + c[1] + ") scale=" + getScale() + " areaLocked=" + areaLockedId);

        // 1. Icon hit check
        String hitIconId = findDeviceAt(c[0], c[1]);
        if (hitIconId != null) {
            if (currentFocusAreaId != null) {
                DeviceInfo info = deviceMap.get(hitIconId);
                // Use fuzzy match for area verification during tap
                if (info == null || !svgParser.isFuzzyMatch(info.areaId, currentFocusAreaId)) return;
            }
            onDeviceTapped(hitIconId);
            return;
        }

        // 2. Relation device hit check
        RelationHitResult hit = findRelationDeviceAt(c[0], c[1]);
        if (hit != null) {
            onRelationDeviceTapped(hit.iconId, hit.tappedDeviceId);
            return;
        }

        // 3. Area check: if background is tapped, focus on area
        String hitAreaId = findAreaAt(c[0], c[1]);
        if (hitAreaId != null && !hitAreaId.equals(areaLockedId)) {
            // zoomToArea(hitAreaId); // Disable automatic zoom on area tap
            return;
        }

        deselectCurrentDevice();
    }

    private String findAreaAt(float svgX, float svgY) {
        if (svgDocument == null) return null;
        for (Map.Entry<String, Element> entry : svgParser.selectionLayerElements.entrySet()) {
            if (svgParser.contains(entry.getValue(), svgX, svgY)) {
                return entry.getKey();
            }
        }
        return null;
    }
    
    private void onRelationDeviceTapped(String iconId, String tappedDeviceId) {
        if (isProvisioned(iconId)) {
            // provision device hone pr relation device par tab karne par bhi kuch nahi karna
            return;
        }
        
        String trueId = getTrueProvisionedId(iconId);
        // Case-insensitive search in deviceMap
        DeviceInfo device = null;
        for (String key : deviceMap.keySet()) {
            if (key.equalsIgnoreCase(trueId)) {
                device = deviceMap.get(key);
                break;
            }
        }
        
        if (device == null) device = deviceMap.get(iconId); // Fallback
        if (device == null) return;

        SharedPreferences prefs = requireContext()
                .getSharedPreferences("app_prefs", Context.MODE_PRIVATE);
        Uri    svgUri       = mViewModel.getSvgUri().getValue();
        String svgUriString = svgUri != null ? svgUri.toString() : "";
        String svgName      = prefs.getString("svg_name_" + svgUriString, "");

        String displayName = deviceOperations.extractPureDeviceName(device.id);

        Intent intent = new Intent(requireContext(), DeviceDetailActivity.class);
        intent.putExtra(DeviceDetailActivity.EXTRA_DEVICE_ID,        device.id);
        intent.putExtra(DeviceDetailActivity.EXTRA_DEVICE_NAME,      displayName);
        intent.putExtra(DeviceDetailActivity.EXTRA_PURE_DEVICE_NAME, displayName);
        intent.putExtra(DeviceDetailActivity.EXTRA_ELEMENT_ID,       device.elementId);
        intent.putExtra(DeviceDetailActivity.EXTRA_RECEIVE_ID,       device.receiveId);
        startActivity(intent);
    }

    private RelationHitResult findRelationDeviceAt(float svgX, float svgY) {
        String bestIconId    = null;
        String bestDeviceId  = null;
        float  smallestArea  = Float.MAX_VALUE;
        float  minDistSq     = Float.MAX_VALUE;

        // Pass 1: Exact hits (no expansion)
        for (Map.Entry<String, Set<String>> entry : iconToDeviceRelations.entrySet()) {
            String      iconId    = entry.getKey();
            // Allow tapping relation devices even if not provisioned yet to help user find them
            // if (!isProvisioned(iconId)) continue;

            for (String deviceId : entry.getValue()) {
                Element deviceEl = svgParser.findElementById(svgDocument.getDocumentElement(), deviceId);
                if (deviceEl == null) continue;

                RectF bounds = svgParser.computeBounds(deviceEl);
                if (bounds == null || bounds.isEmpty()) continue;

                boolean isStrip = deviceId.toLowerCase().contains("pss04") || deviceId.toLowerCase().contains("strip node");
                float limit = isStrip ? 200f : 15f;
                if (bounds.width() > limit || bounds.height() > limit) continue;

                if (svgParser.contains(deviceEl, svgX, svgY)) {
                    float area = bounds.width() * bounds.height();
                    float dx = svgX - bounds.centerX();
                    float dy = svgY - bounds.centerY();
                    float distSq = dx * dx + dy * dy;

                    if (distSq < minDistSq || (Math.abs(distSq - minDistSq) < 4f && area < smallestArea)) {
                        smallestArea = area;
                        minDistSq    = distSq;
                        bestIconId   = iconId;
                        bestDeviceId = deviceId;
                    }
                }
            }
        }
        if (bestIconId != null) return new RelationHitResult(bestIconId, bestDeviceId);

        // Pass 2: Expanded hits — same scale-aware tolerance as findDeviceAt()
        float currentScale = getScale();
        smallestArea = Float.MAX_VALUE;
        minDistSq    = Float.MAX_VALUE;
        for (Map.Entry<String, Set<String>> entry : iconToDeviceRelations.entrySet()) {
            String      iconId    = entry.getKey();
            // if (!isProvisioned(iconId)) continue;

            for (String deviceId : entry.getValue()) {
                Element deviceEl = svgParser.findElementById(svgDocument.getDocumentElement(), deviceId);
                if (deviceEl == null) continue;

                RectF bounds = svgParser.computeBounds(deviceEl);
                if (bounds == null || bounds.isEmpty()) continue;

                boolean isStrip = deviceId.toLowerCase().contains("pss04") || deviceId.toLowerCase().contains("strip node");
                float limit = isStrip ? 200f : 15f;
                if (bounds.width() > limit || bounds.height() > limit) continue;

                float screenTolPx = isStrip ? TOUCH_TOLERANCE_PX * 1.4f : TOUCH_TOLERANCE_PX;
                float tolerance = Math.max(MIN_SVG_TOLERANCE, screenTolPx / currentScale);

                if (svgParser.contains(deviceEl, svgX, svgY, tolerance)) {
                    float area = bounds.width() * bounds.height();
                    float dx = svgX - bounds.centerX();
                    float dy = svgY - bounds.centerY();
                    float distSq = dx * dx + dy * dy;

                    if (distSq < minDistSq || (Math.abs(distSq - minDistSq) < 4f && area < smallestArea)) {
                        smallestArea = area;
                        minDistSq    = distSq;
                        bestIconId   = iconId;
                        bestDeviceId = deviceId;
                    }
                }
            }
        }

        return (bestIconId != null) ? new RelationHitResult(bestIconId, bestDeviceId) : null;
    }
    
    public float[] touchToSvgCoords(float localX, float localY) {
        Matrix inverse = new Matrix();
        if (!matrix.invert(inverse)) return new float[]{localX, localY};
        
        float[] pt = {localX, localY};
        inverse.mapPoints(pt);
        float finalX = svgParser.vbX + pt[0];
        float finalY = svgParser.vbY + pt[1];
        Log.v(TAG, "touchToSvgCoords: ptInDrawable=(" + pt[0] + "," + pt[1] + ") vb=(" + svgParser.vbX + "," + svgParser.vbY + ") -> finalSvg=(" + finalX + "," + finalY + ")");
        return new float[]{finalX, finalY};
    }

    public float[] svgToTouchCoords(float svgX, float svgY) {
        float drawableX = svgX - svgParser.vbX;
        float drawableY = svgY - svgParser.vbY;
        float[] pt = {drawableX, drawableY};
        matrix.mapPoints(pt);
        return pt;
    }

    private String findDeviceAt(float svgX, float svgY) {
        String bestId       = null;
        float  smallestArea = Float.MAX_VALUE;
        float  minDistSq    = Float.MAX_VALUE;

        Log.d(TAG, "findDeviceAt: searching at (" + svgX + "," + svgY + ")");

        // Pass 1: Exact hits on icon bounds
        for (Map.Entry<String, DeviceInfo> entry : deviceMap.entrySet()) {
            String id = entry.getKey();
            RectF bounds = entry.getValue().bounds;

            // 1. First check if point is within the bounding box of the icon
            // We use a small buffer (0.5 units) to account for floating point errors
            if (svgX < bounds.left - 0.5f || svgX > bounds.right + 0.5f ||
                    svgY < bounds.top - 0.5f || svgY > bounds.bottom + 0.5f) {
                continue;
            }

            // 2. Then check the actual shape (paths/circles/etc) for precision
            if (svgParser.contains(entry.getValue().element, svgX, svgY)) {
                float area = bounds.width() * bounds.height();
                float dx = svgX - bounds.centerX();
                float dy = svgY - bounds.centerY();
                float distSq = dx * dx + dy * dy;

                if (distSq < minDistSq || (Math.abs(distSq - minDistSq) < 4f && area < smallestArea)) {
                    smallestArea = area;
                    minDistSq    = distSq;
                    bestId       = id;
                    Log.d(TAG, "MATCH-Pass1: " + id + " dist=" + Math.sqrt(distSq));
                }
            }
        }
        if (bestId != null) return bestId;

        // Pass 2: Expanded hits (Tolerance based)
        float currentScale = getScale();
        smallestArea = Float.MAX_VALUE;
        minDistSq    = Float.MAX_VALUE;

        for (Map.Entry<String, DeviceInfo> entry : deviceMap.entrySet()) {
            String id     = entry.getKey();
            RectF  bounds = entry.getValue().bounds;
            boolean isStrip = id.toLowerCase().contains("pss04") || id.toLowerCase().contains("strip node");

            float screenTolPx = isStrip ? TOUCH_TOLERANCE_PX * 1.5f : TOUCH_TOLERANCE_PX;
            float tol = Math.max(MIN_SVG_TOLERANCE, screenTolPx / currentScale);

            // Check against expanded bounding box
            if (svgX < bounds.left - tol || svgX > bounds.right + tol ||
                    svgY < bounds.top - tol || svgY > bounds.bottom + tol) {
                continue;
            }

            // Precise check with tolerance
            if (svgParser.contains(entry.getValue().element, svgX, svgY, tol)) {
                float area = bounds.width() * bounds.height();
                float dx = svgX - bounds.centerX();
                float dy = svgY - bounds.centerY();
                float distSq = dx * dx + dy * dy;

                if (distSq < minDistSq || (Math.abs(distSq - minDistSq) < 4f && area < smallestArea)) {
                    smallestArea = area;
                    minDistSq    = distSq;
                    bestId       = id;
                    Log.d(TAG, "MATCH-Pass2: " + id + " dist=" + Math.sqrt(distSq) + " tol=" + tol);
                }
            }
        }
        if (bestId != null) Log.d(TAG, "findDeviceAt Winner: " + bestId);
        return bestId;
    }
    // ══════════════════════════════════════════════════════════════════════
    //  DEVICE TAP
    // ══════════════════════════════════════════════════════════════════════

    private void onDeviceTapped(String deviceId) {
        if (isProvisioned(deviceId)) {
            // provision device hone pr device ki details show nhi karna, aur move bhi nahi hone dena
            return;
        }
        
        String trueId = getTrueProvisionedId(deviceId);
        DeviceInfo device = deviceMap.get(trueId);
        if (device == null) device = deviceMap.get(deviceId); // Fallback

        deselectCurrentDevice();
        selectedDeviceId = deviceId;

        if (device != null && device.element != null) {
            colorManager.applyColorToIconGroup(
                    device.element,
                    isProvisioned(deviceId)
                            ? COLOR_TRANSPARENT
                            : SvgColorManager.COLOR_SELECTED);
        }
        reRenderSvg();

        SharedPreferences prefs = requireContext()
                .getSharedPreferences("app_prefs", Context.MODE_PRIVATE);
        Uri    svgUri       = mViewModel.getSvgUri().getValue();
        String svgUriString = svgUri != null ? svgUri.toString() : "";
        String svgName      = prefs.getString("svg_name_" + svgUriString, "");

        String      displayName    = deviceOperations.extractPureDeviceName(trueId);

        Intent intent = new Intent(requireContext(), DeviceDetailActivity.class);
        intent.putExtra(DeviceDetailActivity.EXTRA_DEVICE_ID,        trueId);
        intent.putExtra(DeviceDetailActivity.EXTRA_DEVICE_NAME,      displayName);
        intent.putExtra(DeviceDetailActivity.EXTRA_PURE_DEVICE_NAME, displayName);
        intent.putExtra(DeviceDetailActivity.EXTRA_ELEMENT_ID,
                device != null ? device.elementId : null);
        intent.putExtra(DeviceDetailActivity.EXTRA_RECEIVE_ID,
                device != null ? device.receiveId : null);
        startActivity(intent);
    }

    private void deselectCurrentDevice() {
        if (selectedDeviceId == null) return;
        DeviceInfo device = deviceMap.get(selectedDeviceId);
        if (device != null && !isProvisioned(selectedDeviceId)) {
            colorManager.restoreIconGroupColor(device.element);
            reRenderSvg();
        }
        selectedDeviceId = null;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  ZOOM & PAN HELPERS
    // ══════════════════════════════════════════════════════════════════════

    private float getScale() {
        matrix.getValues(matrixValues);
        return matrixValues[Matrix.MSCALE_X];
    }

    /**
     * Full clamp: enforces both scale floor/ceiling AND translation bounds.
     * Call this at rest (gesture end, drag, fling). NOT during active pinch.
     */
    private void clampMatrix() {
        if (binding == null || binding.svgView.getDrawable() == null) return;
        matrix.getValues(matrixValues);

        float effectiveMin = (areaLockedMinZoom > 0) ? areaLockedMinZoom : minZoom;
        float scale = Math.max(effectiveMin,
                Math.min(MAX_ZOOM, matrixValues[Matrix.MSCALE_X]));

        matrixValues[Matrix.MSCALE_X] = scale;
        matrixValues[Matrix.MSCALE_Y] = scale;
        matrix.setValues(matrixValues);

        applyTranslationClamp(scale);
    }

    /**
     * Translation-only clamp: does NOT touch scale values.
     * Use this during active pinch gesture to avoid per-frame jitter/hang.
     *
     * FIX: When no selection_layer boundary exists, use SVG viewBox dimensions
     * (vbW/vbH) instead of drawable intrinsic size, so the boundary is correct
     * regardless of whether the SVG has a selection_layer group.
     */
    private void clampTranslationOnly() {
        if (binding == null || binding.svgView.getDrawable() == null) return;
        matrix.getValues(matrixValues);
        float scale = matrixValues[Matrix.MSCALE_X];
        applyTranslationClamp(scale);
    }

    /**
     * Core translation-clamp logic shared by clampMatrix() and clampTranslationOnly().
     * Extracts min/max TX/TY from either the area boundary or the full floor plan,
     * then clamps matrixValues and writes them back.
     */
    private void applyTranslationClamp(float scale) {
        if (binding == null || binding.svgView.getDrawable() == null) return;
        matrix.getValues(matrixValues);

        float vW = binding.svgView.getWidth();
        float vH = binding.svgView.getHeight();

        RectF boundary = areaLockedId != null
                ? svgParser.selectionLayerBounds.get(areaLockedId)
                : getFloorPlanBounds();

        float minTX, maxTX, minTY, maxTY;

        if (boundary != null) {
            // Selection-layer boundary is in SVG coords → convert to screen coords
            float bL = (boundary.left   - svgParser.vbX) * scale;
            float bT = (boundary.top    - svgParser.vbY) * scale;
            float bR = (boundary.right  - svgParser.vbX) * scale;
            float bB = (boundary.bottom - svgParser.vbY) * scale;
            float bW = bR - bL;
            float bH = bB - bT;

            if (bW >= vW) { minTX = vW - bR; maxTX = -bL; }
            else          { float cx = vW / 2f - (bL + bW / 2f); minTX = maxTX = cx; }

            if (bH >= vH) { minTY = vH - bB; maxTY = -bT; }
            else          { float cy = vH / 2f - (bT + bH / 2f); minTY = maxTY = cy; }
        } else {
            // ── FIX: use viewBox dimensions, not drawable intrinsic size ──
            // Drawable intrinsic == rendered pixel size, which ignores vbX/vbY
            // offsets and produces wrong min/max translation when zooming out.
            float svgW = svgParser.vbW > 0
                    ? svgParser.vbW
                    : binding.svgView.getDrawable().getIntrinsicWidth();
            float svgH = svgParser.vbH > 0
                    ? svgParser.vbH
                    : binding.svgView.getDrawable().getIntrinsicHeight();

            float dW = svgW * scale;
            float dH = svgH * scale;

            minTX = (dW < vW) ? (vW - dW) / 2f : Math.min(0f, vW - dW);
            maxTX = (dW < vW) ? (vW - dW) / 2f : 0f;
            minTY = (dH < vH) ? (vH - dH) / 2f : Math.min(0f, vH - dH);
            maxTY = (dH < vH) ? (vH - dH) / 2f : 0f;
        }

        matrixValues[Matrix.MTRANS_X] = Math.max(minTX, Math.min(maxTX, matrixValues[Matrix.MTRANS_X]));
        matrixValues[Matrix.MTRANS_Y] = Math.max(minTY, Math.min(maxTY, matrixValues[Matrix.MTRANS_Y]));
        matrix.setValues(matrixValues);
    }

    private void animateToMatrix(float targetScale, float targetTX, float targetTY) {
        matrix.getValues(matrixValues);
        float startScale = matrixValues[Matrix.MSCALE_X];
        float startTX    = matrixValues[Matrix.MTRANS_X];
        float startTY    = matrixValues[Matrix.MTRANS_Y];

        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(ANIMATION_DURATION);
        animator.setInterpolator(new DecelerateInterpolator(2f));
        animator.addUpdateListener(anim -> {
            if (binding == null) return;
            float t = (float) anim.getAnimatedValue();
            matrixValues[Matrix.MSCALE_X] = startScale + (targetScale - startScale) * t;
            matrixValues[Matrix.MSCALE_Y] = startScale + (targetScale - startScale) * t;
            matrixValues[Matrix.MTRANS_X] = startTX    + (targetTX    - startTX)    * t;
            matrixValues[Matrix.MTRANS_Y] = startTY    + (targetTY    - startTY)    * t;
            matrix.setValues(matrixValues);
            clampMatrix();
            binding.svgView.setImageMatrix(matrix);
        });
        animator.start();
    }

    private void animateZoomTo(float targetScale, float pivotX, float pivotY) {
        if (zoomAnimator != null) zoomAnimator.cancel();
        float start = getScale();
        zoomAnimator = ValueAnimator.ofFloat(start, targetScale);
        zoomAnimator.setDuration(ANIMATION_DURATION);
        zoomAnimator.setInterpolator(new DecelerateInterpolator(2f));
        zoomAnimator.addUpdateListener(anim -> {
            if (binding == null) return;
            float val = (float) anim.getAnimatedValue();
            float cur = getScale();
            if (cur > 0) {
                matrix.postScale(val / cur, val / cur, pivotX, pivotY);
                clampMatrix();
                binding.svgView.setImageMatrix(matrix);
            }
        });
        zoomAnimator.start();
    }
    // ══════════════════════════════════════════════════════════════════════


    private void startFling(float velocityX, float velocityY) {
        if (binding == null || binding.svgView.getDrawable() == null) return;
        matrix.getValues(matrixValues);
        float scale = matrixValues[Matrix.MSCALE_X];

        // ── FIX: use viewBox dimensions here too ──
        float svgW = svgParser.vbW > 0
                ? svgParser.vbW : binding.svgView.getDrawable().getIntrinsicWidth();
        float svgH = svgParser.vbH > 0
                ? svgParser.vbH : binding.svgView.getDrawable().getIntrinsicHeight();
        float dW = svgW * scale;
        float dH = svgH * scale;

        float vW    = binding.svgView.getWidth();
        float vH    = binding.svgView.getHeight();
        int startX  = (int) matrixValues[Matrix.MTRANS_X];
        int startY  = (int) matrixValues[Matrix.MTRANS_Y];
        int minX    = (dW < vW) ? (int) ((vW - dW) / 2f) : (int) (vW - dW);
        int maxX    = (dW < vW) ? (int) ((vW - dW) / 2f) : 0;
        int minY    = (dH < vH) ? (int) ((vH - dH) / 2f) : (int) (vH - dH);
        int maxY    = (dH < vH) ? (int) ((vH - dH) / 2f) : 0;

        scroller.fling(startX, startY, (int) velocityX, (int) velocityY,
                minX, maxX, minY, maxY, 0, 0);

        if (flingAnimator != null) flingAnimator.cancel();
        flingAnimator = ValueAnimator.ofFloat(0f, 1f);
        flingAnimator.setDuration(FLING_DURATION);
        flingAnimator.addUpdateListener(anim -> {
            if (binding == null) { anim.cancel(); return; }
            if (scroller.computeScrollOffset()) {
                matrix.getValues(matrixValues);
                matrixValues[Matrix.MTRANS_X] = scroller.getCurrX();
                matrixValues[Matrix.MTRANS_Y] = scroller.getCurrY();
                matrix.setValues(matrixValues);
                clampMatrix();
                binding.svgView.setImageMatrix(matrix);
            } else {
                anim.cancel();
            }
        });
        flingAnimator.start();
    }
}
