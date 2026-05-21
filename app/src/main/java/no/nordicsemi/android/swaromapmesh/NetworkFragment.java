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

import java.io.InputStream;
import java.io.StringWriter;
import java.util.LinkedHashMap;
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
import no.nordicsemi.android.swaromapmesh.viewmodels.ClientServerElementStore;
import no.nordicsemi.android.swaromapmesh.viewmodels.SharedViewModel;

@AndroidEntryPoint
public class NetworkFragment extends Fragment {

    private static final String TAG = "NetworkFragment";

    // ── Zoom constants ─────────────────────────────────────────────────────
    private static final float MAX_ZOOM           = 10f;
    private static final float DOUBLE_TAP_ZOOM    = 2.5f;
    private static final float TAP_TOLERANCE      = 8f;
    private static final long  ANIMATION_DURATION = 280L;
    private static final int   FLING_DURATION     = 2000;
    private static final float TAP_MOVE_SLOP      = 10f;
    private static final long  TAP_MAX_DURATION   = 250L;

    // ── Area / zoom lock ───────────────────────────────────────────────────
    private float  areaLockedMinZoom  = -1f;
    private String areaLockedId       = null;
    private String currentFocusAreaId = null;
    private String pendingFocusAreaId = null;

    // ── Device state ───────────────────────────────────────────────────────
    private final Map<String, DeviceInfo> deviceMap = new LinkedHashMap<>();
    private String selectedDeviceId = null;

    private RectF floorPlanBounds = null;

    // ── UI / ViewModel ─────────────────────────────────────────────────────
    private FragmentNetworkBinding binding;
    private boolean                mAutoSetupInProgress = false;
    private SharedViewModel        mViewModel;

    // ── Threading ──────────────────────────────────────────────────────────
    private final ExecutorService loadExecutor   = Executors.newSingleThreadExecutor();
    private final ExecutorService renderExecutor = Executors.newSingleThreadExecutor();
    private final Handler         mainHandler    = new Handler(Looper.getMainLooper());
    private Future<?> pendingRender;

    // ── SVG helpers ────────────────────────────────────────────────────────
    private final SvgParsers      svgParser    = new SvgParsers();
    private final SvgColorManager colorManager = new SvgColorManager();

    // ── SVG state ──────────────────────────────────────────────────────────
    private Document svgDocument;

    // ── Zoom & pan ─────────────────────────────────────────────────────────
    private final Matrix  matrix       = new Matrix();
    private final float[] matrixValues = new float[9];
    private float   minZoom         = 1f;
    private float   lastTouchX, lastTouchY;
    private boolean isDragging      = false;
    private int     activePointerId = MotionEvent.INVALID_POINTER_ID;

    // ── Gesture detectors ──────────────────────────────────────────────────
    private ScaleGestureDetector scaleDetector;
    private GestureDetector      gestureDetector;
    private OverScroller         scroller;
    private VelocityTracker      velocityTracker;
    private ValueAnimator        flingAnimator;
    private ValueAnimator        zoomAnimator;

    // ── Tap helpers ────────────────────────────────────────────────────────
    private float   tapDownX, tapDownY;
    private long    tapDownTime;
    private boolean hasMoved      = false;
    private boolean wasMultiTouch = false;

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
        observeViewModel();
        loadSvgFromAssets("office.svg");
        return binding.getRoot();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (svgDocument == null || deviceMap.isEmpty() || mAutoSetupInProgress) return;
        selectedDeviceId = null;
        colorManager.forceResnapshotAllDevices(deviceMap);
        if (currentFocusAreaId != null) {
            colorManager.applyAreaFocus(currentFocusAreaId, deviceMap);
        } else {
            refreshColors();
        }
        reRenderSvg();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        cancelAnimators();
        if (velocityTracker != null) { velocityTracker.recycle(); velocityTracker = null; }
        if (pendingRender   != null) pendingRender.cancel(true);
        loadExecutor.shutdownNow();
        renderExecutor.shutdownNow();
        binding = null;
    }

    private void cancelAnimators() {
        if (flingAnimator != null) flingAnimator.cancel();
        if (zoomAnimator  != null) zoomAnimator.cancel();
    }

    // ══════════════════════════════════════════════════════════════════════
    //  VIEWMODEL OBSERVERS
    // ══════════════════════════════════════════════════════════════════════

    private void observeViewModel() {

        mViewModel.isAutoSetupInProgress().observe(getViewLifecycleOwner(), inProgress -> {
            if (binding == null) return;
            boolean wasInProgress = mAutoSetupInProgress;
            mAutoSetupInProgress  = Boolean.TRUE.equals(inProgress);

            if (mAutoSetupInProgress) {
                binding.autoSetupOverlay.setVisibility(View.VISIBLE);
                binding.progressBar.setVisibility(View.VISIBLE);
                binding.svgView.setOnTouchListener(null);
            } else {
                binding.autoSetupOverlay.setVisibility(View.GONE);
                binding.progressBar.setVisibility(View.GONE);
                binding.svgView.setOnTouchListener(this::handleTouch);
                if (wasInProgress) {
                    Toast.makeText(requireContext(),
                            "All process is complete", Toast.LENGTH_SHORT).show();
                }
                if (wasInProgress && svgDocument != null && !deviceMap.isEmpty()) {
                    selectedDeviceId = null;
                    applyCurrentColorMode();
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
            if (binding == null || svgDocument == null
                    || deviceMap.isEmpty() || mAutoSetupInProgress) return;
            selectedDeviceId = null;
            applyCurrentColorMode();
            reRenderSvg();
        });
    }

    // ══════════════════════════════════════════════════════════════════════
    //  BACK PRESS
    // ══════════════════════════════════════════════════════════════════════

    public boolean handleBackPress() {
        if (areaLockedId != null) {
            exitAreaZoom();
            return true;
        }
        if (currentFocusAreaId != null) {
            currentFocusAreaId = null;
            areaLockedId       = null;
            areaLockedMinZoom  = -1f;
            colorManager.restoreAllFurniture();
            colorManager.restoreAllSelectionLayers();
            refreshColors();
            reRenderSvg();
            binding.svgView.post(() -> fitFloorPlanToView(true));
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
    //  COLOR MODE
    // ══════════════════════════════════════════════════════════════════════

    private void applyCurrentColorMode() {
        if (currentFocusAreaId != null) {
            colorManager.applyAreaFocus(currentFocusAreaId, deviceMap);
        } else {
            refreshColors();
        }
    }

    private void refreshColors() {
        colorManager.refreshAllColors(
                deviceMap,
                getProvisionedSet(),
                selectedDeviceId,
                null);
    }

    // ══════════════════════════════════════════════════════════════════════
    //  AREA ZOOM
    // ══════════════════════════════════════════════════════════════════════

    private void zoomToArea(String areaId) {
        RectF areaBounds = resolveAreaBounds(areaId);
        if (areaBounds == null) {
            Log.w(TAG, "zoomToArea: no bounds for " + areaId);
            return;
        }

        Log.d(TAG, "zoomToArea '" + areaId + "' → " + areaBounds);

        // Only re-apply colors if area actually changed
        if (!areaId.equals(currentFocusAreaId)) {
            currentFocusAreaId = areaId;
            colorManager.applyAreaFocus(areaId, deviceMap);
            reRenderSvg();
        }

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
            if (binding.svgView.getDrawable() != null) {
                binding.svgView.post(doZoom);
            } else {
                mainHandler.postDelayed(() -> binding.svgView.post(doZoom), 200);
            }
        }, 300);
    }

    private RectF resolveAreaBounds(String areaId) {
        RectF bounds = svgParser.selectionLayerBounds.get(areaId);
        if (bounds != null) return bounds;

        // Fall back to union of device bounds in this area
        java.util.List<String> iconIds = svgParser.areaMap.get(areaId);
        if (iconIds == null || iconIds.isEmpty()) return null;
        RectF union = null;
        for (String iconId : iconIds) {
            DeviceInfo info = deviceMap.get(iconId);
            if (info == null || info.bounds == null) continue;
            if (union == null) union = new RectF(info.bounds);
            else union.union(info.bounds);
        }
        return union;
    }

    private void exitAreaZoom() {
        areaLockedId       = null;
        areaLockedMinZoom  = -1f;
        currentFocusAreaId = null;
        colorManager.restoreAllFurniture();
        colorManager.restoreAllSelectionLayers();
        refreshColors();
        reRenderSvg();
        binding.svgView.post(() -> fitFloorPlanToView(true));
    }

    // ══════════════════════════════════════════════════════════════════════
    //  SVG LOADING
    // ══════════════════════════════════════════════════════════════════════

    private void loadSvgFromAssets(String assetFileName) {
        showLoading(true);
        loadExecutor.execute(() -> {
            try {
                InputStream is = requireContext().getAssets().open(assetFileName);
                Document    doc = svgParser.parseDocument(is);
                is.close();

                if (doc == null) {
                    mainHandler.post(() -> { showLoading(false); showPlaceholder(true); });
                    return;
                }

                svgParser.parseViewBox(doc);
                Map<String, DeviceInfo> devices = svgParser.extractDevices(doc);
                svgParser.parseSelectionLayer(doc);
                floorPlanBounds = null;

                mainHandler.post(() -> onSvgLoaded(doc, devices));

            } catch (Exception e) {
                Log.e(TAG, "Error loading SVG from assets", e);
                mainHandler.post(() -> { showLoading(false); showPlaceholder(true); });
            }
        });
    }

    private void onSvgLoaded(Document document,
                             Map<String, DeviceInfo> devices) {
        svgDocument = document;
        deviceMap.clear();
        deviceMap.putAll(devices);

        colorManager.init(document, svgParser, deviceMap);

        // Do NOT auto-focus first area here.
        // If a pending focus arrived before load finished, use it.
        // Otherwise just hide everything and wait.
        if (pendingFocusAreaId != null) {
            final String focusId = pendingFocusAreaId;
            pendingFocusAreaId = null;
            currentFocusAreaId = focusId;
            colorManager.applyAreaFocus(focusId, deviceMap);
            reRenderSvg();
            mainHandler.postDelayed(() -> zoomToArea(focusId), 400);
        } else {
            // No area requested yet — hide all, show full map
            colorManager.hideAllUserLayerElements();
            colorManager.hideAllSelectionLayers();
            reRenderSvg();
        }

        showLoading(false);
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
    // ══════════════════════════════════════════════════════════════════════

    private void fitFloorPlanToView(boolean animate) {
        if (binding == null || binding.svgView.getDrawable() == null) return;
        float vW = binding.svgView.getWidth();
        float vH = binding.svgView.getHeight();
        if (vW <= 0 || vH <= 0) return;

        float svgW = svgParser.vbW > 0 ? svgParser.vbW
                : binding.svgView.getDrawable().getIntrinsicWidth();
        float svgH = svgParser.vbH > 0 ? svgParser.vbH
                : binding.svgView.getDrawable().getIntrinsicHeight();

        RectF fp = getFloorPlanBounds();
        float scale, transX, transY;

        if (fp != null && !fp.isEmpty()) {
            float padding = 16f;
            RectF padded  = new RectF(fp);
            padded.inset(-padding, -padding);
            scale  = Math.min(vW / padded.width(), vH / padded.height());
            float cx = padded.centerX() - svgParser.vbX;
            float cy = padded.centerY() - svgParser.vbY;
            transX = vW / 2f - cx * scale;
            transY = vH / 2f - cy * scale;
        } else {
            scale  = Math.min(vW / svgW, vH / svgH);
            transX = (vW - svgW * scale) / 2f - svgParser.vbX * scale;
            transY = (vH - svgH * scale) / 2f - svgParser.vbY * scale;
        }

        minZoom = scale;

        if (animate) {
            animateToMatrix(scale, transX, transY);
        } else {
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

    private void reRenderSvg() {
        if (svgDocument == null) return;
        if (pendingRender != null && !pendingRender.isDone())
            pendingRender.cancel(true);

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
                    binding.svgView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
                    binding.svgView.setImageDrawable(drawable);
                    binding.svgView.setVisibility(View.VISIBLE);
                    binding.svgPlaceholder.setVisibility(View.GONE);
                    if (!mAutoSetupInProgress)
                        binding.progressBar.setVisibility(View.GONE);

                    boolean matrixIsIdentity = isMatrixIdentity(frozenMatrix);
                    if (matrixIsIdentity) {
                        binding.svgView.post(() -> fitFloorPlanToView(false));
                    } else {
                        binding.svgView.setImageMatrix(frozenMatrix);
                    }
                    binding.svgView.invalidate();
                });
            } catch (Exception e) {
                Log.e(TAG, "reRenderSvg error", e);
            }
        });
    }

    private boolean isMatrixIdentity(Matrix m) {
        float[] v = new float[9];
        m.getValues(v);
        return v[Matrix.MSCALE_X] == 0f && v[Matrix.MTRANS_X] == 0f
                && v[Matrix.MTRANS_Y] == 0f;
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
        binding.svgPlaceholder.setVisibility(show ? View.VISIBLE : View.GONE);
        binding.svgView.setVisibility(show ? View.GONE  : View.VISIBLE);
        if (show && !mAutoSetupInProgress)
            binding.progressBar.setVisibility(View.GONE);
    }

    private void showLoading(boolean show) {
        if (binding == null) return;
        if (show) {
            binding.progressBar.setVisibility(View.VISIBLE);
            binding.svgPlaceholder.setVisibility(View.GONE);
            binding.svgView.setVisibility(View.GONE);
        } else {
            if (!mAutoSetupInProgress)
                binding.progressBar.setVisibility(View.GONE);
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
                    @Override
                    public boolean onScale(ScaleGestureDetector d) {
                        float cur    = getScale();
                        float factor = d.getScaleFactor();
                        float next   = cur * factor;
                        if (next < minZoom)  factor = minZoom  / cur;
                        if (next > MAX_ZOOM) factor = MAX_ZOOM / cur;
                        matrix.postScale(factor, factor,
                                d.getFocusX(), d.getFocusY());
                        clampTranslationOnly();
                        binding.svgView.setImageMatrix(matrix);
                        return true;
                    }

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
                        if (wasMultiTouch) return false;
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
                cancelAnimators();
                scroller.forceFinished(true);
                activePointerId = event.getPointerId(0);
                lastTouchX      = event.getX();
                lastTouchY      = event.getY();
                isDragging      = true;
                tapDownX        = event.getX();
                tapDownY        = event.getY();
                tapDownTime     = event.getEventTime();
                hasMoved        = false;
                wasMultiTouch   = false;
                break;

            case MotionEvent.ACTION_POINTER_DOWN:
                isDragging    = false;
                hasMoved      = true;
                wasMultiTouch = true;
                break;

            case MotionEvent.ACTION_MOVE:
                if (!scaleDetector.isInProgress()) {
                    int idx = event.findPointerIndex(activePointerId);
                    if (idx == -1) {
                        activePointerId = event.getPointerId(0);
                        break;
                    }
                    float dx  = event.getX(idx) - lastTouchX;
                    float dy  = event.getY(idx) - lastTouchY;
                    float tdx = event.getX(idx) - tapDownX;
                    float tdy = event.getY(idx) - tapDownY;
                    if (Math.sqrt(tdx * tdx + tdy * tdy) > TAP_MOVE_SLOP)
                        hasMoved = true;
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
                        && (event.getEventTime() - tapDownTime) < TAP_MAX_DURATION) {
                    handleSvgTap(tapDownX, tapDownY);
                }
                resetPointerState();
                break;

            case MotionEvent.ACTION_CANCEL:
                hasMoved = true;
                resetPointerState();
                break;

            case MotionEvent.ACTION_POINTER_UP:
                int pi  = event.getActionIndex();
                int pid = event.getPointerId(pi);
                if (pid == activePointerId) {
                    int ni = (pi == 0) ? 1 : 0;
                    activePointerId = event.getPointerId(ni);
                    lastTouchX      = event.getX(ni);
                    lastTouchY      = event.getY(ni);
                }
                break;
        }
        return true;
    }

    private void resetPointerState() {
        activePointerId = MotionEvent.INVALID_POINTER_ID;
        isDragging      = false;
        wasMultiTouch   = false;
        if (velocityTracker != null) {
            velocityTracker.recycle();
            velocityTracker = null;
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  TAP / HIT TEST
    // ══════════════════════════════════════════════════════════════════════

    private void handleSvgTap(float touchX, float touchY) {
        if (svgDocument == null) return;
        float[] c         = touchToSvgCoords(touchX, touchY);
        String  hitIconId = findDeviceAt(c[0], c[1]);

        if (hitIconId != null) {
            if (currentFocusAreaId != null) {
                DeviceInfo info = deviceMap.get(hitIconId);
                if (info == null || !currentFocusAreaId.equals(info.areaId)) return;
            }
            onDeviceTapped(hitIconId);
            return;
        }
        deselectCurrentDevice();
    }

    private void handleSvgLongPress(float touchX, float touchY) {
        if (svgDocument == null) return;
        float[] c         = touchToSvgCoords(touchX, touchY);
        String  hitIconId = findDeviceAt(c[0], c[1]);
        if (hitIconId == null || !isProvisioned(hitIconId)) return;

        DeviceInfo device = deviceMap.get(hitIconId);
        if (device == null) return;

        binding.svgView.performHapticFeedback(
                android.view.HapticFeedbackConstants.LONG_PRESS);

        boolean isConnected = Boolean.TRUE.equals(
                mViewModel.isConnectedToProxy().getValue());
        if (!isConnected) {
            new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle("Not Connected")
                    .setMessage("Please connect to a proxy node before resetting the device.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }

        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("Device Options")
                .setMessage("Do you want to reset this device?")
                .setPositiveButton("Reset Node",
                        (dialog, which) -> openNodeConfigForReset(hitIconId, device))
                .setNegativeButton("Cancel", null)
                .show();
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
                if (area < smallestArea) {
                    smallestArea = area;
                    bestId = entry.getKey();
                }
            }
        }
        return bestId;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  DEVICE TAP
    // ══════════════════════════════════════════════════════════════════════

    private void onDeviceTapped(String deviceId) {
        deselectCurrentDevice();
        selectedDeviceId = deviceId;

        DeviceInfo device = deviceMap.get(deviceId);
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
        String displayName  = extractPureDeviceName(deviceId);

        Log.d(TAG, "Tapped: " + deviceId + " provisioned=" + isProvisioned(deviceId));

        Intent intent = new Intent(requireContext(), DeviceDetailActivity.class);
        intent.putExtra(DeviceDetailActivity.EXTRA_DEVICE_ID,        deviceId);
        intent.putExtra(DeviceDetailActivity.EXTRA_DEVICE_NAME,      displayName);
        intent.putExtra(DeviceDetailActivity.EXTRA_PURE_DEVICE_NAME, displayName);
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

    private void openNodeConfigForReset(String deviceId, DeviceInfo device) {
        SharedPreferences prefs = requireContext()
                .getSharedPreferences("app_prefs", Context.MODE_PRIVATE);
        Uri    svgUri       = mViewModel.getSvgUri().getValue();
        String svgUriString = svgUri != null ? svgUri.toString() : "";
        String svgName      = prefs.getString("svg_name_" + svgUriString, "");
        String displayName  = extractPureDeviceName(deviceId);

        Intent intent = new Intent(requireContext(), NodeConfigurationActivity.class);
        intent.putExtra("EXTRA_SVG_DEVICE_ID",                       deviceId);
        intent.putExtra(DeviceDetailActivity.EXTRA_DEVICE_NAME,      displayName);
        intent.putExtra(DeviceDetailActivity.EXTRA_PURE_DEVICE_NAME, displayName);
        intent.putExtra("svg_name",   svgName);
        intent.putExtra("AUTO_RESET", true);
        startActivity(intent);
    }

    private String extractPureDeviceName(String fullDeviceId) {
        if (fullDeviceId == null || fullDeviceId.isEmpty()) return "";
        String name = fullDeviceId;
        int ci = name.lastIndexOf(":");
        if (ci != -1) name = name.substring(ci + 1).trim();
        name = name.replaceAll("_", " ").trim();
        if (name.isEmpty()) return fullDeviceId;
        return name;
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
        float effectiveMin = areaLockedMinZoom > 0 ? areaLockedMinZoom : minZoom;
        float scale = Math.max(effectiveMin,
                Math.min(MAX_ZOOM, matrixValues[Matrix.MSCALE_X]));
        matrixValues[Matrix.MSCALE_X] = scale;
        matrixValues[Matrix.MSCALE_Y] = scale;
        matrix.setValues(matrixValues);
        applyTranslationClamp(scale);
    }

    private void clampTranslationOnly() {
        if (binding == null || binding.svgView.getDrawable() == null) return;
        matrix.getValues(matrixValues);
        applyTranslationClamp(matrixValues[Matrix.MSCALE_X]);
    }

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
            float bL = (boundary.left   - svgParser.vbX) * scale;
            float bT = (boundary.top    - svgParser.vbY) * scale;
            float bR = (boundary.right  - svgParser.vbX) * scale;
            float bB = (boundary.bottom - svgParser.vbY) * scale;
            float bW = bR - bL;
            float bH = bB - bT;

            minTX = bW >= vW ? vW - bR : vW / 2f - (bL + bW / 2f);
            maxTX = bW >= vW ? -bL      : minTX;
            minTY = bH >= vH ? vH - bB : vH / 2f - (bT + bH / 2f);
            maxTY = bH >= vH ? -bT      : minTY;
        } else {
            float svgW = svgParser.vbW > 0 ? svgParser.vbW
                    : binding.svgView.getDrawable().getIntrinsicWidth();
            float svgH = svgParser.vbH > 0 ? svgParser.vbH
                    : binding.svgView.getDrawable().getIntrinsicHeight();
            float dW = svgW * scale;
            float dH = svgH * scale;

            minTX = dW < vW ? (vW - dW) / 2f : Math.min(0f, vW - dW);
            maxTX = dW < vW ? (vW - dW) / 2f : 0f;
            minTY = dH < vH ? (vH - dH) / 2f : Math.min(0f, vH - dH);
            maxTY = dH < vH ? (vH - dH) / 2f : 0f;
        }

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
            float cur = getScale();
            if (cur > 0) {
                matrix.postScale(val / cur, val / cur, pivotX, pivotY);
                clampMatrix();
                binding.svgView.setImageMatrix(matrix);
            }
        });
        zoomAnimator.start();
    }

    private void startFling(float velocityX, float velocityY) {
        if (binding == null || binding.svgView.getDrawable() == null) return;
        matrix.getValues(matrixValues);
        float scale = matrixValues[Matrix.MSCALE_X];
        float svgW  = svgParser.vbW > 0 ? svgParser.vbW
                : binding.svgView.getDrawable().getIntrinsicWidth();
        float svgH  = svgParser.vbH > 0 ? svgParser.vbH
                : binding.svgView.getDrawable().getIntrinsicHeight();
        float dW = svgW * scale;
        float dH = svgH * scale;
        float vW = binding.svgView.getWidth();
        float vH = binding.svgView.getHeight();

        int startX = (int) matrixValues[Matrix.MTRANS_X];
        int startY = (int) matrixValues[Matrix.MTRANS_Y];
        int minX   = dW < vW ? (int) ((vW - dW) / 2f) : (int) (vW - dW);
        int maxX   = dW < vW ? (int) ((vW - dW) / 2f) : 0;
        int minY   = dH < vH ? (int) ((vH - dH) / 2f) : (int) (vH - dH);
        int maxY   = dH < vH ? (int) ((vH - dH) / 2f) : 0;

        scroller.fling(startX, startY,
                (int) velocityX, (int) velocityY,
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