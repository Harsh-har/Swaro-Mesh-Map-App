package no.nordicsemi.android.swaromapmesh;

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
import java.io.File;
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
import no.nordicsemi.android.swaromapmesh.swajaui.Svg_Operations.DeviceInfo;
import no.nordicsemi.android.swaromapmesh.swajaui.Svg_Operations.SvgColorManager;
import no.nordicsemi.android.swaromapmesh.swajaui.Svg_Operations.SvgParsers;
import no.nordicsemi.android.swaromapmesh.viewmodels.SharedViewModel;

@AndroidEntryPoint
public class NetworkFragment extends Fragment {

    private static final String TAG = "NetworkFragment";

    // ── Zoom constants ────────────────────────────────────────────────────
    private static final float MAX_ZOOM        = 10f;
    private static final float DOUBLE_TAP_ZOOM = 2.5f;
    private static final float TAP_TOLERANCE   = 8f;
    private static final long  ANIMATION_DURATION = 280L;
    private static final int   FLING_DURATION     = 2000;

    // ── Prefs ─────────────────────────────────────────────────────────────
    private static final String PREFS_NAME              = "mesh_prefs";
    private static final String KEY_PROVISIONED_DEVICES = "provisioned_devices";

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
    private final SvgParsers svgParser    = new SvgParsers();
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
    private boolean hasMoved   = false;
    private static final float TAP_MOVE_SLOP    = 10f;
    private static final long  TAP_MAX_DURATION = 250;

    // ══════════════════════════════════════════════════════════════════════
    //  LIFECYCLE
    // ══════════════════════════════════════════════════════════════════════

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding   = FragmentNetworkBinding.inflate(inflater, container, false);
        mViewModel = new ViewModelProvider(requireActivity()).get(SharedViewModel.class);
        setupZoomAndPan();
        observeViewModel();
        return binding.getRoot();
    }

    private void observeViewModel() {
        mViewModel.isAutoSetupInProgress().observe(getViewLifecycleOwner(), inProgress -> {
            if (binding == null) return;
            mAutoSetupInProgress = Boolean.TRUE.equals(inProgress);
            if (mAutoSetupInProgress) {
                binding.autoSetupOverlay.setVisibility(View.VISIBLE);
                binding.progressBar.setVisibility(View.GONE);
                binding.svgView.setOnTouchListener(null);
            } else {
                binding.autoSetupOverlay.setVisibility(View.GONE);
                binding.svgView.setOnTouchListener(this::handleTouch);
                if (svgDocument != null && !deviceMap.isEmpty()) {
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
            refreshColors();
            reRenderSvg();
        });

        mViewModel.getSvgUri().observe(getViewLifecycleOwner(), uri -> {
            if (binding == null) return;
            if (uri != null) { showLoading(true); loadSvgFromUri(uri); }
            else             { showPlaceholder(true); loadSvgFromAssets("output.svg"); }
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mViewModel != null) {
            mAutoSetupInProgress = Boolean.TRUE.equals(
                    mViewModel.isAutoSetupInProgress().getValue());
            if (binding != null) {
                if (mAutoSetupInProgress) {
                    binding.autoSetupOverlay.setVisibility(View.VISIBLE);
                    binding.svgView.setOnTouchListener(null);
                } else {
                    binding.svgView.setOnTouchListener(this::handleTouch);
                }
            }
        }
        if (svgDocument == null || deviceMap.isEmpty() || mAutoSetupInProgress) return;
        selectedDeviceId = null;
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
    //  SVG LOADING
    // ══════════════════════════════════════════════════════════════════════

    private void loadSvgFromAssets(String assetFileName) {
        showLoading(true);
        loadExecutor.execute(() -> {
            try {
                String[] assets = requireContext().getAssets().list("");
                boolean  found  = false;
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
                Map<String, Set<String>> relations = svgParser.parseRelations(doc);
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
                Map<String, Set<String>> relations = svgParser.parseRelations(doc);
                svgParser.parseSelectionLayer(doc);
                floorPlanBounds = null;

                mainHandler.post(() -> onSvgLoaded(svg, doc, devices, relations));
            } catch (Exception e) {
                Log.e(TAG, "Error loading SVG from URI", e);
                mainHandler.post(() -> { showLoading(false); showPlaceholder(true); });
            }
        });
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
        refreshColors();
        renderSvg(svg, true);
        showLoading(false);
    }

    // ══════════════════════════════════════════════════════════════════════
    //  COLOR REFRESH  (delegates to SvgColorManager)
    // ══════════════════════════════════════════════════════════════════════

    private void refreshColors() {
        colorManager.refreshAllColors(
                deviceMap,
                getProvisionedFromPrefs(),
                selectedDeviceId,
                iconToDeviceRelations,
                currentFocusAreaId
        );
    }

    // ══════════════════════════════════════════════════════════════════════
    //  AREA ZOOM
    // ══════════════════════════════════════════════════════════════════════

    private void zoomToArea(String areaId) {
        RectF areaBounds = svgParser.selectionLayerBounds.get(areaId);
        if (areaBounds == null) {
            // Fallback: union of device icon bounds in that area
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

        // Dim other areas + highlight doors
        colorManager.dimOtherAreas(
                areaId, svgParser.selectionLayerElements, svgParser.selectionLayerBounds);

        // Show only this area's device icons
        refreshColors();
        reRenderSvg();

        final RectF finalBounds = new RectF(areaBounds);

        mainHandler.postDelayed(() -> {
            if (binding == null) return;
            Runnable doZoom = () -> {
                float vW = binding.svgView.getWidth();
                float vH = binding.svgView.getHeight();
                if (vW <= 0 || vH <= 0) {
                    mainHandler.postDelayed(() -> zoomToArea(areaId), 150);
                    return;
                }
                float padding     = 28f;
                RectF padded      = new RectF(finalBounds);
                padded.inset(-padding, -padding);

                float scaleX      = vW / padded.width();
                float scaleY      = vH / padded.height();
                float targetScale = Math.min(MAX_ZOOM,
                        Math.max(minZoom, Math.min(scaleX, scaleY)));

                areaLockedId      = areaId;
                areaLockedMinZoom = targetScale;

                float cx     = padded.centerX() - svgParser.vbX;
                float cy     = padded.centerY() - svgParser.vbY;
                float transX = vW / 2f - cx * targetScale;
                float transY = vH / 2f - cy * targetScale;

                animateToMatrix(targetScale, transX, transY);
            };

            if (binding.svgView.getDrawable() != null)
                binding.svgView.post(doZoom);
            else
                mainHandler.postDelayed(() -> binding.svgView.post(doZoom), 200);
        }, 300);
    }

    /**
     * Exit area zoom on double-tap:
     * Zoom back to full floor plan but keep icons restricted to the focused area.
     */
    private void exitAreaZoom() {
        areaLockedId      = null;
        areaLockedMinZoom = -1f;
        // currentFocusAreaId intentionally kept — icons stay restricted

        colorManager.restoreAllAreas(
                svgParser.selectionLayerElements, svgParser.selectionLayerBounds);

        if (currentFocusAreaId != null) refreshColors();
        else                            refreshColors();

        reRenderSvg();
        binding.svgView.post(() -> fitFloorPlanToView(true));
    }

    // ══════════════════════════════════════════════════════════════════════
    //  FLOOR PLAN FIT
    // ══════════════════════════════════════════════════════════════════════

    private void fitFloorPlanToView(boolean animate) {
        if (binding == null || binding.svgView.getDrawable() == null) return;
        float vW = binding.svgView.getWidth();
        float vH = binding.svgView.getHeight();
        if (vW <= 0 || vH <= 0) return;

        RectF fp = getFloorPlanBounds();
        if (fp == null || fp.isEmpty()) {
            float dW    = binding.svgView.getDrawable().getIntrinsicWidth();
            float dH    = binding.svgView.getDrawable().getIntrinsicHeight();
            float scale = Math.min(vW / dW, vH / dH);
            minZoom = scale;
            if (animate)
                animateToMatrix(scale, (vW - dW * scale) / 2f, (vH - dH * scale) / 2f);
            else {
                matrix.reset();
                matrix.postScale(scale, scale);
                matrix.postTranslate((vW - dW * scale) / 2f, (vH - dH * scale) / 2f);
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
            if (!mAutoSetupInProgress) binding.progressBar.setVisibility(View.GONE);

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
            } catch (Exception e) { Log.e(TAG, "reRenderSvg error", e); }
        });
    }

    private String documentToString(Document doc) {
        if (doc == null) return "";
        try {
            Transformer  t  = TransformerFactory.newInstance().newTransformer();
            StringWriter sw = new StringWriter();
            t.transform(new DOMSource(doc), new StreamResult(sw));
            return sw.toString();
        } catch (Exception e) { Log.e(TAG, "documentToString error", e); return ""; }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  UI STATE
    // ══════════════════════════════════════════════════════════════════════

    private void showPlaceholder(boolean show) {
        if (binding == null) return;
        if (show) {
            binding.svgPlaceholder.setVisibility(View.VISIBLE);
            binding.svgView.setVisibility(View.GONE);
            if (!mAutoSetupInProgress) binding.progressBar.setVisibility(View.GONE);
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
            if (!mAutoSetupInProgress) binding.progressBar.setVisibility(View.GONE);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  PREFS
    // ══════════════════════════════════════════════════════════════════════

    private Set<String> getProvisionedFromPrefs() {
        if (getContext() == null) return new HashSet<>();
        SharedPreferences prefs =
                requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return new HashSet<>(
                prefs.getStringSet(KEY_PROVISIONED_DEVICES, new HashSet<>()));
    }

    // ══════════════════════════════════════════════════════════════════════
    //  TOUCH HANDLING
    // ══════════════════════════════════════════════════════════════════════

    private void setupZoomAndPan() {
        binding.svgView.setScaleType(ImageView.ScaleType.MATRIX);
        scroller = new OverScroller(requireContext(), new DecelerateInterpolator(2.5f));

        scaleDetector = new ScaleGestureDetector(requireContext(),
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(ScaleGestureDetector d) {
                        float cur  = getScale();
                        float next = Math.max(minZoom,
                                Math.min(MAX_ZOOM, cur * d.getScaleFactor()));
                        matrix.postScale(next / cur, next / cur,
                                d.getFocusX(), d.getFocusY());
                        clampMatrix();
                        binding.svgView.setImageMatrix(matrix);
                        return true;
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
                    public boolean onFling(MotionEvent e1, MotionEvent e2,
                                           float vx, float vy) {
                        startFling(vx, vy);
                        return true;
                    }
                });

        binding.svgView.setOnTouchListener(this::handleTouch);
    }

    private boolean handleTouch(View v, MotionEvent event) {
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
                isDragging  = true;
                tapDownX    = event.getX();
                tapDownY    = event.getY();
                tapDownTime = event.getEventTime();
                hasMoved    = false;
                break;

            case MotionEvent.ACTION_POINTER_DOWN:
                isDragging = false;
                hasMoved   = true;
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
                if (velocityTracker != null) {
                    velocityTracker.recycle();
                    velocityTracker = null;
                }
                break;

            case MotionEvent.ACTION_CANCEL:
                activePointerId = MotionEvent.INVALID_POINTER_ID;
                isDragging      = false;
                hasMoved        = true;
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
        if (svgDocument == null || deviceMap.isEmpty()) return;
        float[]  c     = touchToSvgCoords(touchX, touchY);
        String   hitId = findDeviceAt(c[0], c[1]);

        if (hitId != null) {
            if (currentFocusAreaId != null) {
                DeviceInfo info = deviceMap.get(hitId);
                if (info == null || !currentFocusAreaId.equals(info.areaId)) return;
            }
            onDeviceTapped(hitId);
        } else {
            deselectCurrentDevice();
        }
    }

    private float[] touchToSvgCoords(float touchX, float touchY) {
        Matrix inverse = new Matrix();
        if (!matrix.invert(inverse)) return new float[]{touchX, touchY};
        float[] pt = {touchX, touchY};
        inverse.mapPoints(pt);
        return new float[]{svgParser.vbX + pt[0], svgParser.vbY + pt[1]};
    }

    private String findDeviceAt(float svgX, float svgY) {
        String bestId       = null;
        float  smallestArea = Float.MAX_VALUE;
        for (Map.Entry<String, DeviceInfo> entry : deviceMap.entrySet()) {
            RectF bounds   = entry.getValue().bounds;
            RectF expanded = new RectF(bounds);
            float inset    = (bounds.width() < 20 || bounds.height() < 20)
                    ? -Math.max(TAP_TOLERANCE, 15f) : -TAP_TOLERANCE;
            expanded.inset(inset, inset);
            if (expanded.contains(svgX, svgY)) {
                float area = bounds.width() * bounds.height();
                if (area < smallestArea) { smallestArea = area; bestId = entry.getKey(); }
            }
        }
        return bestId;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  DEVICE TAP
    // ══════════════════════════════════════════════════════════════════════

    private void onDeviceTapped(String deviceId) {
        Set<String> provisioned = getProvisionedFromPrefs();
        DeviceInfo  device      = deviceMap.get(deviceId);

        deselectCurrentDevice();
        selectedDeviceId = deviceId;

        if (device != null && device.element != null) {
            colorManager.applyColorToIconGroup(device.element,
                    provisioned.contains(deviceId)
                            ? SvgColorManager.COLOR_TRANSPARENT
                            : SvgColorManager.COLOR_SELECTED);
        }
        reRenderSvg();

        SharedPreferences prefs = requireContext()
                .getSharedPreferences("app_prefs", Context.MODE_PRIVATE);
        Uri    svgUri       = mViewModel.getSvgUri().getValue();
        String svgUriString = svgUri != null ? svgUri.toString() : "";
        String svgName      = prefs.getString("svg_name_" + svgUriString, "");

        String      displayName      = extractPureDeviceName(deviceId);
        Set<String> relatedDevices   = iconToDeviceRelations.getOrDefault(deviceId, new HashSet<>());
        String      relationDevName  = relatedDevices.isEmpty()
                ? null : relatedDevices.iterator().next();

        if (provisioned.contains(deviceId)) {
            Intent intent = new Intent(requireContext(), TestProvisionActivity.class);
            intent.putExtra(DeviceDetailActivity.EXTRA_DEVICE_ID,        deviceId);
            intent.putExtra(DeviceDetailActivity.EXTRA_DEVICE_NAME,      displayName);
            intent.putExtra(DeviceDetailActivity.EXTRA_PURE_DEVICE_NAME, displayName);
            intent.putExtra(DeviceDetailActivity.EXTRA_ELEMENT_ID,
                    device != null ? device.elementId : null);
            intent.putExtra("EXTRA_RELATION_DEVICE_NAME", relationDevName);
            intent.putExtra("svg_name", svgName);
            startActivity(intent);
            return;
        }
        Intent intent = new Intent(requireContext(), DeviceDetailActivity.class);
        intent.putExtra(DeviceDetailActivity.EXTRA_DEVICE_ID,        deviceId);
        intent.putExtra(DeviceDetailActivity.EXTRA_DEVICE_NAME,      displayName);
        intent.putExtra(DeviceDetailActivity.EXTRA_PURE_DEVICE_NAME, displayName);
        intent.putExtra(DeviceDetailActivity.EXTRA_ELEMENT_ID,
                device != null ? device.elementId : null);
        startActivity(intent);
    }

    /**
     * Strips area prefix and trailing digits to get a clean display name.
     * e.g. "LivingRoom:Light 1" → "Light"
     */
    private String extractPureDeviceName(String fullDeviceId) {
        if (fullDeviceId == null || fullDeviceId.isEmpty()) return "";
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
        if (device != null && !getProvisionedFromPrefs().contains(selectedDeviceId)) {
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

    private void clampMatrix() {
        if (binding == null || binding.svgView.getDrawable() == null) return;
        matrix.getValues(matrixValues);

        float effectiveMin = (areaLockedMinZoom > 0) ? areaLockedMinZoom : minZoom;
        float scale = Math.max(effectiveMin,
                Math.min(MAX_ZOOM, matrixValues[Matrix.MSCALE_X]));
        float vW = binding.svgView.getWidth();
        float vH = binding.svgView.getHeight();

        RectF boundary = areaLockedId != null
                ? svgParser.selectionLayerBounds.get(areaLockedId)
                : getFloorPlanBounds();

        float minTX, maxTX, minTY, maxTY;

        if (boundary != null) {
            float bL = (boundary.left   - svgParser.vbX) * scale;
            float bT = (boundary.top    - svgParser.vbY) * scale;
            float bR = (boundary.right  - svgParser.vbX) * scale;
            float bB = (boundary.bottom - svgParser.vbY) * scale;
            float bW = bR - bL, bH = bB - bT;

            if (bW >= vW) { minTX = vW - bR; maxTX = -bL; }
            else          { float cx = vW / 2f - (bL + bW / 2f); minTX = maxTX = cx; }

            if (bH >= vH) { minTY = vH - bB; maxTY = -bT; }
            else          { float cy = vH / 2f - (bT + bH / 2f); minTY = maxTY = cy; }
        } else {
            float dW = binding.svgView.getDrawable().getIntrinsicWidth()  * scale;
            float dH = binding.svgView.getDrawable().getIntrinsicHeight() * scale;
            minTX = (dW < vW) ? (vW - dW) / 2f : Math.min(0f, vW - dW);
            maxTX = (dW < vW) ? (vW - dW) / 2f : 0f;
            minTY = (dH < vH) ? (vH - dH) / 2f : Math.min(0f, vH - dH);
            maxTY = (dH < vH) ? (vH - dH) / 2f : 0f;
        }

        matrixValues[Matrix.MSCALE_X] = scale;
        matrixValues[Matrix.MSCALE_Y] = scale;
        matrixValues[Matrix.MTRANS_X] = Math.max(minTX,
                Math.min(maxTX, matrixValues[Matrix.MTRANS_X]));
        matrixValues[Matrix.MTRANS_Y] = Math.max(minTY,
                Math.min(maxTY, matrixValues[Matrix.MTRANS_Y]));
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
            matrix.postScale(val / getScale(), val / getScale(), pivotX, pivotY);
            clampMatrix();
            binding.svgView.setImageMatrix(matrix);
        });
        zoomAnimator.start();
    }

    private void startFling(float velocityX, float velocityY) {
        if (binding == null || binding.svgView.getDrawable() == null) return;
        matrix.getValues(matrixValues);
        float scale = matrixValues[Matrix.MSCALE_X];
        float dW    = binding.svgView.getDrawable().getIntrinsicWidth()  * scale;
        float dH    = binding.svgView.getDrawable().getIntrinsicHeight() * scale;
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
            } else { anim.cancel(); }
        });
        flingAnimator.start();
    }
}