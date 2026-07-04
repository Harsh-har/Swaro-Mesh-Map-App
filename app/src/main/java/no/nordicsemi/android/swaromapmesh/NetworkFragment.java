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
import java.io.FileOutputStream;
import java.io.OutputStream;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import android.widget.EditText;
import android.widget.LinearLayout;
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
import no.nordicsemi.android.swaromapmesh.swajaui.Svg_Operations.DeviceInfo;
import no.nordicsemi.android.swaromapmesh.swajaui.Svg_Operations.SvgColorManager;
import no.nordicsemi.android.swaromapmesh.swajaui.Svg_Operations.SvgParsers;
import no.nordicsemi.android.swaromapmesh.utils.DeviceCodes;
import no.nordicsemi.android.swaromapmesh.viewmodels.ClientServerElementStore;
import no.nordicsemi.android.swaromapmesh.viewmodels.SharedViewModel;

@AndroidEntryPoint
public class NetworkFragment extends Fragment {

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
    private String mSelectedCategory = DeviceCodes.CONTROL_NODE;
    private float mNewDeviceX, mNewDeviceY;

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
            loadSvgFromAssets("lalitesh.svg");
        }
    }

    private void setupAddDeviceLogic() {
        binding.fabAddDevice.setOnClickListener(v -> showCategorySelectionDialog());
        binding.btnCancelAdd.setOnClickListener(v -> exitAddDeviceMode());
        binding.btnSaveDevice.setOnClickListener(v -> showDeviceInfoDialog());

        binding.draggableIcon.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_MOVE) {
                v.setX(event.getRawX() - v.getWidth() / 2f);
                v.setY(event.getRawY() - v.getHeight() / 2f);
            } else if (event.getAction() == MotionEvent.ACTION_UP) {
                v.performClick();
            }
            return true;
        });
    }

    private void showCategorySelectionDialog() {
        String[] categories = {
                DeviceCodes.CONTROL_NODE,
                DeviceCodes.STRIP_NODE,
                DeviceCodes.LC_NODE,
                DeviceCodes.AC_NODE,
                DeviceCodes.RELAY_NODE,
                DeviceCodes.FAN_NODE,
                "Switch Plate",
                "Manual Entry..."
        };
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Select Device Category")
                .setItems(categories, (dialog, which) -> {
                    if (which == categories.length - 1) {
                        showManualCategoryDialog();
                    } else {
                        mSelectedCategory = categories[which];
                        enterAddDeviceMode();
                    }
                })
                .show();
    }

    private void showManualCategoryDialog() {
        final EditText input = new EditText(requireContext());
        input.setHint("Category Name ");
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Enter Category")
                .setView(input)
                .setPositiveButton("OK", (dialog, which) -> {
                    String cat = input.getText().toString().trim();
                    if (!cat.isEmpty()) {
                        mSelectedCategory = cat;
                        enterAddDeviceMode();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
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

    private void showDeviceInfoDialog() {
        LinearLayout layout = new LinearLayout(requireContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 20, 40, 20);

        final EditText nameInput = new EditText(requireContext());
        nameInput.setHint("Device Name ()");
        layout.addView(nameInput);

        final EditText elementIdInput = new EditText(requireContext());
        elementIdInput.setHint("Element ID (Integer)");
        elementIdInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        layout.addView(elementIdInput);

        final EditText receiveIdInput = new EditText(requireContext());
        receiveIdInput.setHint("Receive ID (Integer)");
        layout.addView(receiveIdInput);

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Enter Device Info")
                .setView(layout)
                .setPositiveButton("Save", (dialog, which) -> {
                    String name = nameInput.getText().toString().trim();
                    String eid  = elementIdInput.getText().toString().trim();
                    String rid  = receiveIdInput.getText().toString().trim();

                    if (name.isEmpty() || eid.isEmpty()) {
                        Toast.makeText(requireContext(),
                                "Name and Element ID are required", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    // ── Conflict check ────────────────────────────────────────────────
                    String conflict = checkDeviceConflict(eid, name);
                    if (conflict != null) {
                        new MaterialAlertDialogBuilder(requireContext())
                                .setTitle("⚠ ID Already Reserved")
                                .setMessage(conflict
                                        + "\n\nPlease use a different Element ID or device name.")
                                .setPositiveButton("Change ID", (d2, w2) -> {
                                    // Re-open the dialog so user can fix it
                                    showDeviceInfoDialog();
                                })
                                .setNegativeButton("Cancel Add", (d2, w2) -> exitAddDeviceMode())
                                .setCancelable(false)
                                .show();
                        return;
                    }

                    // ── All clear, proceed ────────────────────────────────────────────
                    float centerX = binding.draggableIcon.getX() + binding.draggableIcon.getWidth() / 2f;
                    float centerY = binding.draggableIcon.getY() + binding.draggableIcon.getHeight() / 2f;
                    float[] svgCoords = touchToSvgCoords(centerX, centerY);

                    addNewDeviceToSvg(mSelectedCategory, name, eid, rid, svgCoords[0], svgCoords[1]);
                    exitAddDeviceMode();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Nullable
    private String checkDeviceConflict(String elementId, String deviceName) {
        String normName = deviceName.trim().toLowerCase().replace(" ", "_");

        for (Map.Entry<String, DeviceInfo> entry : deviceMap.entrySet()) {
            String     existingKey = entry.getKey();
            DeviceInfo existing    = entry.getValue();
            String     areaSuffix  = existing.areaId != null
                    ? " (area: " + existing.areaId + ")" : "";  // extracted once

            if (existing.elementId != null
                    && !existing.elementId.trim().isEmpty()
                    && existing.elementId.trim().equals(elementId.trim())) {
                return "Element ID \"" + elementId + "\" is already used by:\n"
                        + extractPureDeviceName(existingKey) + areaSuffix;
            }

            if (extractPureDeviceName(existingKey).trim().equalsIgnoreCase(deviceName.trim())) {
                return "Device name \"" + deviceName + "\" is already used by:\n"
                        + existingKey + areaSuffix;
            }
        }
        return null;
    }
    private void addNewDeviceToSvg(String category, String name, String eid, String rid, float x, float y) {
        if (svgDocument == null) return;

        try {
            Element root = svgDocument.getDocumentElement();

            // 1. Determine the correct Area ID
            String areaId = currentFocusAreaId;
            if (areaId == null) {
                // Try to find area by spatial check if not focused
                for (Map.Entry<String, RectF> entry : svgParser.selectionLayerBounds.entrySet()) {
                    if (entry.getValue().contains(x, y)) {
                        areaId = entry.getKey();
                        break;
                    }
                }
            }
            if (areaId == null) areaId = "AddedDevices";

            // 2. Generate unique Device ID
            String deviceId = "manual_" + name.replace(" ", "_").replace(":", "_") + "_" + System.currentTimeMillis();
            String fullId = deviceId + ":" + category;

            // 3. Add to Technician Layer
            Element iconsGroup = svgParser.findElementById(root, "Technician Layer");
            if (iconsGroup == null) {
                iconsGroup = svgDocument.createElement("g");
                iconsGroup.setAttribute("id", "Technician Layer");
                root.appendChild(iconsGroup);
            }

            // Find or create the area-specific group inside Technician Layer
            Element areaGroup = svgParser.findElementById(iconsGroup, areaId);
            if (areaGroup == null) {
                areaGroup = svgParser.findElementFuzzy(iconsGroup, areaId);
            }
            if (areaGroup == null) {
                areaGroup = svgDocument.createElement("g");
                areaGroup.setAttribute("id", areaId);
                iconsGroup.appendChild(areaGroup);
            }

            Element iconEl = svgDocument.createElement("g");
            iconEl.setAttribute("id", deviceId + ":" + category);
            iconEl.setAttribute("data-manual", "true");
            iconEl.setAttribute("data-manual-added", "true"); // Backup attribute
            iconEl.setAttribute("transform", "translate(" + (x - svgParser.vbX) + " " + (y - svgParser.vbY) + ")");

            Element metadata = svgDocument.createElement("metadata");
            Element eidNode = svgDocument.createElement("elementId");
            eidNode.setTextContent(eid);
            metadata.appendChild(eidNode);
            if (!rid.isEmpty()) {
                Element ridNode = svgDocument.createElement("reciveId");
                ridNode.setTextContent(rid);
                metadata.appendChild(ridNode);
            }
            iconEl.appendChild(metadata);

            // Simple visual for icon: a square
            Element rect = svgDocument.createElement("rect");
            rect.setAttribute("width", "10");
            rect.setAttribute("height", "10");
            rect.setAttribute("x", "-5");
            rect.setAttribute("y", "-5");
            rect.setAttribute("fill", "#ffae42");
            rect.setAttribute("stroke", "#000");
            rect.setAttribute("stroke-width", "0.5");
            iconEl.appendChild(rect);

            areaGroup.appendChild(iconEl);

            // 4. Add to User Layer
            Element devicesGroup = svgParser.findElementById(root, "User Layer");
            if (devicesGroup == null) {
                devicesGroup = svgDocument.createElement("g");
                devicesGroup.setAttribute("id", "User Layer");
                root.appendChild(devicesGroup);
            }

            Element devAreaGroup = svgParser.findElementById(devicesGroup, areaId);
            if (devAreaGroup == null) {
                NodeList devChildren = devicesGroup.getChildNodes();
                for (int i = 0; i < devChildren.getLength(); i++) {
                    if (devChildren.item(i) instanceof Element) {
                        devAreaGroup = (Element) devChildren.item(i);
                        break;
                    }
                }
            }
            if (devAreaGroup == null) {
                devAreaGroup = svgDocument.createElement("g");
                devAreaGroup.setAttribute("id", areaId + "_Dev");
                devicesGroup.appendChild(devAreaGroup);
            }

            String physicalId = deviceId + "_phys";
            Element physEl = svgDocument.createElement("circle");
            physEl.setAttribute("id", physicalId);
            physEl.setAttribute("cx", String.valueOf(x));
            physEl.setAttribute("cy", String.valueOf(y));
            physEl.setAttribute("r", "3");
            physEl.setAttribute("style", "fill:#b3b3b3;");
            devAreaGroup.appendChild(physEl);

            // 6. Save to Internal Storage
            saveSvgToInternal();

            // 7. Update local state and re-render
            Map<String, DeviceInfo> newDevices = svgParser.extractDevices(svgDocument);
            deviceMap.clear();
            deviceMap.putAll(newDevices);
            Map<String, Set<String>> newRelations = svgParser.parseRelations(svgDocument, deviceMap);
            iconToDeviceRelations.clear();
            iconToDeviceRelations.putAll(newRelations);

            colorManager.init(svgDocument, svgParser, deviceMap);
            refreshColors();
            reRenderSvg();

            Toast.makeText(requireContext(), "Device added to " + areaId, Toast.LENGTH_SHORT).show();

        } catch (Exception e) {
            Log.e(TAG, "Error adding new device", e);
            Toast.makeText(requireContext(), "Failed to add device", Toast.LENGTH_SHORT).show();
        }
    }

    private void saveSvgToInternal() {
        try {
            File file = new File(requireContext().getFilesDir(), "modified_map.svg");
            OutputStream os = new FileOutputStream(file);
            Transformer t = TransformerFactory.newInstance().newTransformer();
            t.transform(new DOMSource(svgDocument), new StreamResult(os));
            os.close();
            Log.d(TAG, "SVG saved to " + file.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "Error saving SVG", e);
        }
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
        colorManager.refreshAllColors(
                deviceMap,
                getProvisionedSet(),
                getAddressedSet(),
                selectedDeviceId,
                iconToDeviceRelations,
                currentFocusAreaId
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
        RectF areaBounds = svgParser.selectionLayerBounds.get(areaId);
        if (areaBounds == null) {
            List<String> iconIds = svgParser.areaMap.get(areaId);
            if (iconIds == null || iconIds.isEmpty()) return;
            for (String iconId : iconIds) {
                DeviceInfo info = deviceMap.get(iconId);
                if (info != null && info.bounds != null) {
                    if (areaBounds == null) areaBounds = new RectF(info.bounds);
                    else areaBounds.union(info.bounds);
                }
            }
            if (areaBounds == null) return;
        }

        Log.d(TAG, "zoomToArea '" + areaId + "' → " + areaBounds);
        currentFocusAreaId = areaId;

        colorManager.dimOtherAreas(
                areaId,
                svgParser.selectionLayerElements,
                svgParser.selectionLayerBounds,
                new RectF(areaBounds));
        refreshColors();
        reRenderSvg();

        focusOnBounds(areaBounds, areaId, true);
    }

    private void focusOnBounds(RectF bounds, String areaIdToLock, boolean animate) {
        mainHandler.postDelayed(() -> {
            if (binding == null) return;
            Runnable doZoom = () -> {
                float vW = binding.svgView.getWidth();
                float vH = binding.svgView.getHeight();
                if (vW <= 0 || vH <= 0) {
                    mainHandler.postDelayed(() -> focusOnBounds(bounds, areaIdToLock, animate), 150);
                    return;
                }
                float padding = 20f;
                RectF padded = new RectF(bounds);
                padded.inset(-padding, -padding);

                float scaleX = vW / padded.width();
                float scaleY = vH / padded.height();
                float targetScale = Math.min(MAX_ZOOM, Math.max(minZoom, Math.min(scaleX, scaleY)));

                if (areaIdToLock != null) {
                    areaLockedId = areaIdToLock;
                    areaLockedMinZoom = targetScale;
                }

                float cx = padded.centerX() - svgParser.vbX;
                float cy = padded.centerY() - svgParser.vbY;
                float transX = vW / 2f - cx * targetScale;
                float transY = vH / 2f - cy * targetScale;

                if (animate) {
                    animateToMatrix(targetScale, transX, transY);
                } else {
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
        }, 300);
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
        return ClientServerElementStore.isProvisioned(deviceId);
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
                if (pendingFocusAreaId != null) {
                    final String focusId = pendingFocusAreaId;
                    pendingFocusAreaId = null;
                    mainHandler.postDelayed(() -> zoomToArea(focusId), 400);
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
                        hasMoved = true;
                        if (areaLockedId != null) {
                            exitAreaZoom();
                        } else {
                            float target = getScale() > minZoom + 0.5f
                                    ? minZoom : DOUBLE_TAP_ZOOM;
                            animateZoomTo(target, e.getX(), e.getY());
                        }
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
                        if (wasMultiTouch) return false;
                        startFling(vx, vy);
                        return true;
                    }
                });

        binding.svgView.setOnTouchListener(this::handleTouch);
    }

    private void handleSvgLongPress(float touchX, float touchY) {
        if (svgDocument == null) return;
        float[] c = touchToSvgCoords(touchX, touchY);

        String hitIconId = findDeviceAt(c[0], c[1]);
        if (hitIconId == null) return;

        DeviceInfo device = deviceMap.get(hitIconId);
        if (device == null) return;

        boolean isProvisioned = isProvisioned(hitIconId);
        boolean isManual = device.element.hasAttribute("data-manual")
                || device.element.hasAttribute("data-manual-added")
                || hitIconId.startsWith("manual_")
                || hitIconId.contains("new_device");

        Log.d(TAG, "handleSvgLongPress: id=" + hitIconId + " isManual=" + isManual + " isProvisioned=" + isProvisioned);

        // ── Haptic feedback ───────────────────────────────────────────────
        binding.svgView.performHapticFeedback(
                android.view.HapticFeedbackConstants.LONG_PRESS);

        List<String> options = new java.util.ArrayList<>();
        if (isProvisioned) options.add("Reset Node");
        options.add("Edit Device");           // available for ALL devices
        if (!isProvisioned) options.add("Delete from Map");
        options.add("Cancel");

        if (options.size() <= 1) return; // Only Cancel

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(extractPureDeviceName(hitIconId))
                .setItems(options.toArray(new String[0]), (dialog, which) -> {
                    String choice = options.get(which);
                    if ("Reset Node".equals(choice)) {
                        boolean isConnected = mViewModel.isConnectedToProxy().getValue() != null
                                && Boolean.TRUE.equals(mViewModel.isConnectedToProxy().getValue());
                        if (!isConnected) {
                            Toast.makeText(requireContext(), "Connect to proxy first", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        openNodeConfigForReset(hitIconId, device);
                    } else if ("Edit Device".equals(choice)) {
                        showEditDeviceDialog(hitIconId, device);
                    } else if ("Delete from Map".equals(choice)) {
                        if (isProvisioned) {
                            Toast.makeText(requireContext(), "Cannot delete provisioned device", Toast.LENGTH_SHORT).show();
                        } else {
                            deleteDeviceFromSvg(hitIconId);
                        }
                    }
                })
                .show();
    }

    // ══════════════════════════════════════════════════════════════════════
//  EDIT DEVICE
// ══════════════════════════════════════════════════════════════════════

    private void showEditDeviceDialog(String iconId, DeviceInfo device) {
        LinearLayout layout = new LinearLayout(requireContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 20, 40, 20);

        // ── Device Name ───────────────────────────────────────────────────
        final EditText nameInput = new EditText(requireContext());
        nameInput.setHint("Device Name");
        String currentName = extractPureDeviceName(iconId);
        nameInput.setText(currentName);
        layout.addView(nameInput);

        // ── Element ID ────────────────────────────────────────────────────
        final EditText elementIdInput = new EditText(requireContext());
        elementIdInput.setHint("Element ID (Integer)");
        elementIdInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        if (device.elementId != null) elementIdInput.setText(device.elementId.trim());
        layout.addView(elementIdInput);

        // ── Receive ID ────────────────────────────────────────────────────
        final EditText receiveIdInput = new EditText(requireContext());
        receiveIdInput.setHint("Receive ID (Integer)");
        if (device.receiveId != null) receiveIdInput.setText(device.receiveId.trim());
        layout.addView(receiveIdInput);

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Edit Device: " + currentName)
                .setView(layout)
                .setPositiveButton("Save", (dialog, which) -> {
                    String newName = nameInput.getText().toString().trim();
                    String newEid  = elementIdInput.getText().toString().trim();
                    String newRid  = receiveIdInput.getText().toString().trim();

                    if (newName.isEmpty() || newEid.isEmpty()) {
                        Toast.makeText(requireContext(),
                                "Name and Element ID are required", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    // ── Conflict check (skip self) ────────────────────────
                    String conflict = checkDeviceConflictExcluding(newEid, newName, iconId);
                    if (conflict != null) {
                        new MaterialAlertDialogBuilder(requireContext())
                                .setTitle("ID Already Reserved")
                                .setMessage(conflict + "\n\nPlease use a different Element ID.")
                                .setPositiveButton("Fix", (d2, w2) -> showEditDeviceDialog(iconId, device))
                                .setNegativeButton("Cancel", null)
                                .setCancelable(false)
                                .show();
                        return;
                    }

                    applyDeviceEdits(iconId, device, newName, newEid, newRid);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * Same as checkDeviceConflict but skips the device being edited (self-check).
     */
    @Nullable
    private String checkDeviceConflictExcluding(String elementId, String deviceName,
                                                String excludeIconId) {
        for (Map.Entry<String, DeviceInfo> entry : deviceMap.entrySet()) {
            String     existingKey = entry.getKey();
            DeviceInfo existing    = entry.getValue();

            if (existingKey.equals(excludeIconId)) continue; // skip self

            String areaSuffix = existing.areaId != null
                    ? " (area: " + existing.areaId + ")" : "";

            if (existing.elementId != null
                    && !existing.elementId.trim().isEmpty()
                    && existing.elementId.trim().equals(elementId.trim())) {
                return "Element ID \"" + elementId + "\" is already used by:\n"
                        + extractPureDeviceName(existingKey) + areaSuffix;
            }

            if (extractPureDeviceName(existingKey).trim().equalsIgnoreCase(deviceName.trim())) {
                return "Device name \"" + deviceName + "\" is already used by:\n"
                        + existingKey + areaSuffix;
            }
        }
        return null;
    }
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
            saveSvgToInternal();

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

    private void deleteDeviceFromSvg(String iconId) {
        if (svgDocument == null) return;
        try {
            Element root = svgDocument.getDocumentElement();

            // 1. Find and remove Icon
            DeviceInfo info = deviceMap.get(iconId);
            if (info != null && info.element != null) {
                Node parent = info.element.getParentNode();
                if (parent != null) parent.removeChild(info.element);
            }

            // 2. Find and remove Physical Device
            Set<String> physicalIds = iconToDeviceRelations.get(iconId);
            if (physicalIds != null) {
                Element devGroup = svgParser.findElementById(root, "User Layer");
                
                if (devGroup != null) {
                    for (String pid : physicalIds) {
                        Element pEl = svgParser.findElementById(devGroup, pid);
                        if (pEl != null) {
                            Node pParent = pEl.getParentNode();
                            if (pParent != null) pParent.removeChild(pEl);
                        }
                    }
                }
            }

            // 4. Save and Refresh
            saveSvgToInternal();

            Map<String, DeviceInfo> newDevices = svgParser.extractDevices(svgDocument);
            deviceMap.clear();
            deviceMap.putAll(newDevices);
            Map<String, Set<String>> newRelations = svgParser.parseRelations(svgDocument, deviceMap);
            iconToDeviceRelations.clear();
            iconToDeviceRelations.putAll(newRelations);

            colorManager.init(svgDocument, svgParser, deviceMap);
            refreshColors();
            reRenderSvg();

            Toast.makeText(requireContext(), "Device deleted", Toast.LENGTH_SHORT).show();

        } catch (Exception e) {
            Log.e(TAG, "Error deleting device", e);
            Toast.makeText(requireContext(), "Failed to delete device", Toast.LENGTH_SHORT).show();
        }
    }
    private void openNodeConfigForReset(String deviceId, DeviceInfo device) {
        SharedPreferences prefs = requireContext()
                .getSharedPreferences("app_prefs", Context.MODE_PRIVATE);
        Uri    svgUri       = mViewModel.getSvgUri().getValue();
        String svgUriString = svgUri != null ? svgUri.toString() : "";
        String svgName      = prefs.getString("svg_name_" + svgUriString, "");

        String displayName = extractPureDeviceName(deviceId);

        Intent intent = new Intent(requireContext(), NodeConfigurationActivity.class);
        intent.putExtra("EXTRA_SVG_DEVICE_ID",                       deviceId);
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
        scaleDetector.onTouchEvent(event);

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
                    if (isDragging && (Math.abs(dx) > 0.5f || Math.abs(dy) > 0.5f)) {
                        matrix.postTranslate(dx, dy);
                        clampMatrix();
                        binding.svgView.setImageMatrix(matrix);
                    }
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
                if (info == null || !currentFocusAreaId.equals(info.areaId)) return;
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
            zoomToArea(hitAreaId);
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
        DeviceInfo device = deviceMap.get(iconId);
        if (device == null) return;

        SharedPreferences prefs = requireContext()
                .getSharedPreferences("app_prefs", Context.MODE_PRIVATE);
        Uri    svgUri       = mViewModel.getSvgUri().getValue();
        String svgUriString = svgUri != null ? svgUri.toString() : "";
        String svgName      = prefs.getString("svg_name_" + svgUriString, "");

        String displayName = extractPureDeviceName(iconId);

        if (isProvisioned(iconId)) {
            Intent intent = new Intent(requireContext(), TestProvisionActivity.class);
            intent.putExtra(DeviceDetailActivity.EXTRA_DEVICE_ID,        iconId);
            intent.putExtra(DeviceDetailActivity.EXTRA_DEVICE_NAME,      displayName);
            intent.putExtra(DeviceDetailActivity.EXTRA_PURE_DEVICE_NAME, displayName);
            intent.putExtra(DeviceDetailActivity.EXTRA_ELEMENT_ID,       device.elementId);
            intent.putExtra(DeviceDetailActivity.EXTRA_RECEIVE_ID,       device.receiveId);
            intent.putExtra("EXTRA_RELATION_DEVICE_NAME",                tappedDeviceId);
            intent.putExtra("svg_name", svgName);
            startActivity(intent);
        } else {
            Intent intent = new Intent(requireContext(), DeviceDetailActivity.class);
            intent.putExtra(DeviceDetailActivity.EXTRA_DEVICE_ID,        iconId);
            intent.putExtra(DeviceDetailActivity.EXTRA_DEVICE_NAME,      displayName);
            intent.putExtra(DeviceDetailActivity.EXTRA_PURE_DEVICE_NAME, displayName);
            intent.putExtra(DeviceDetailActivity.EXTRA_ELEMENT_ID,       device.elementId);
            intent.putExtra(DeviceDetailActivity.EXTRA_RECEIVE_ID,       device.receiveId);
            startActivity(intent);
        }
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

                boolean isStrip = deviceId.toLowerCase().contains("st_") || deviceId.toLowerCase().contains("strip");
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

                boolean isStrip = deviceId.toLowerCase().contains("st_") || deviceId.toLowerCase().contains("strip");
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
    }    private float[] touchToSvgCoords(float touchX, float touchY) {
        Matrix inverse = new Matrix();
        if (!matrix.invert(inverse)) return new float[]{touchX, touchY};
        float[] pt = {touchX, touchY};
        inverse.mapPoints(pt);
        float finalX = svgParser.vbX + pt[0];
        float finalY = svgParser.vbY + pt[1];
        Log.v(TAG, "touchToSvgCoords: ptInDrawable=(" + pt[0] + "," + pt[1] + ") vb=(" + svgParser.vbX + "," + svgParser.vbY + ") -> finalSvg=(" + finalX + "," + finalY + ")");
        return new float[]{finalX, finalY};
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
            boolean isStrip = id.toLowerCase().contains("st_") || id.toLowerCase().contains("strip");

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
        DeviceInfo device = deviceMap.get(deviceId);

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

        String      displayName    = extractPureDeviceName(deviceId);
        Set<String> relatedDevices = iconToDeviceRelations.containsKey(deviceId)
                ? iconToDeviceRelations.get(deviceId) : new HashSet<>();
        String      relationDevName = relatedDevices.isEmpty()
                ? null : relatedDevices.iterator().next();

        Log.d(TAG, "isProvisioned check: deviceId=" + deviceId
                + " result=" + isProvisioned(deviceId));

        if (isProvisioned(deviceId)) {
            if (device != null && device.elementId != null) {
                try {
                    int svgId = Integer.parseInt(device.elementId.trim());
                    if (svgId >= 0) ClientServerElementStore.saveServerSvgElementId(deviceId, svgId);
                } catch (NumberFormatException ignored) {}
            }
            Intent intent = new Intent(requireContext(), TestProvisionActivity.class);
            intent.putExtra(DeviceDetailActivity.EXTRA_DEVICE_ID,        deviceId);
            intent.putExtra(DeviceDetailActivity.EXTRA_DEVICE_NAME,      displayName);
            intent.putExtra(DeviceDetailActivity.EXTRA_PURE_DEVICE_NAME, displayName);
            intent.putExtra(DeviceDetailActivity.EXTRA_ELEMENT_ID,
                    device != null ? device.elementId : null);
            intent.putExtra(DeviceDetailActivity.EXTRA_RECEIVE_ID,
                    device != null ? device.receiveId : null);
            intent.putExtra("EXTRA_RELATION_DEVICE_NAME", relationDevName);
            intent.putExtra("svg_name", svgName);
            startActivity(intent);
            return;
        }

        if (device != null && device.elementId != null) {
            try {
                int svgId = Integer.parseInt(device.elementId.trim());
                if (svgId >= 0) {
                    ClientServerElementStore.saveServerSvgElementId(deviceId, svgId);
                    Log.d(TAG, "✅ onDeviceTapped: svgId pre-saved device=" + deviceId + " svgId=" + svgId);
                }
            } catch (NumberFormatException e) {
                Log.w(TAG, "onDeviceTapped: elementId parse failed: " + device.elementId);
            }
        }

        Intent intent = new Intent(requireContext(), DeviceDetailActivity.class);
        intent.putExtra(DeviceDetailActivity.EXTRA_DEVICE_ID,        deviceId);
        intent.putExtra(DeviceDetailActivity.EXTRA_DEVICE_NAME,      displayName);
        intent.putExtra(DeviceDetailActivity.EXTRA_PURE_DEVICE_NAME, displayName);
        intent.putExtra(DeviceDetailActivity.EXTRA_ELEMENT_ID,
                device != null ? device.elementId : null);
        intent.putExtra(DeviceDetailActivity.EXTRA_RECEIVE_ID,
                device != null ? device.receiveId : null);
        startActivity(intent);
    }

    private String extractPureDeviceName(String fullDeviceId) {
        if (fullDeviceId == null || fullDeviceId.isEmpty()) return "";

        // New structure: RoomName_DeviceName_Count_ElementId_ReceiveId
        // Example: GBDR_Strip Node_1_13_13 -> DeviceName is parts[1]
        String[] parts = fullDeviceId.split("_");
        if (parts.length >= 5) {
            return parts[1];
        }

        String name = fullDeviceId;
        int ci = name.lastIndexOf(":");
        if (ci != -1) name = name.substring(ci + 1).trim();
        name = name.replaceAll("\\s*\\d+$", "")
                .replaceAll("\\d+$", "")
                .replaceAll("\\s+", " ")
                .trim();
        return name.isEmpty()
                ? (fullDeviceId.contains(":")
                   ? fullDeviceId.substring(fullDeviceId.indexOf(":") + 1).trim()
                   : fullDeviceId)
                : name;
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