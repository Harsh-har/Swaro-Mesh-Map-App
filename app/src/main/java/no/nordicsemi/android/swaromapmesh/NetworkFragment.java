package no.nordicsemi.android.swaromapmesh;

import android.animation.ValueAnimator;
import android.content.Intent;
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

import java.io.InputStream;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import dagger.hilt.android.AndroidEntryPoint;
import no.nordicsemi.android.swaromapmesh.ble.ScannerActivity;
import no.nordicsemi.android.swaromapmesh.databinding.FragmentNetworkBinding;
import no.nordicsemi.android.swaromapmesh.viewmodels.SharedViewModel;

@AndroidEntryPoint
public class NetworkFragment extends Fragment {

    private static final String TAG = "NetworkFragment";
    private static final float MAX_ZOOM = 10f;
    private static final float DOUBLE_TAP_ZOOM = 2.5f;

    // TAP_TOLERANCE in SVG units — increased for small devices like c_2, l_2 (~3.6px radius)
    private static final float TAP_TOLERANCE = 8f;
    private static final long ANIMATION_DURATION = 280L;
    private static final int FLING_DURATION = 2000;

    private FragmentNetworkBinding binding;
    private SharedViewModel mViewModel;
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ==================== DATA MODEL ====================

    private static class DeviceInfo {
        String id;          // SVG element id (e.g. "c_2", "SW-RL01-006A")
        Element element;    // The actual DOM element to recolor
        RectF bounds;       // Bounding box in SVG coordinate space
        String elementId;   // Value inside <metadata><elementId> tag
        String originalColor;

        DeviceInfo(String id, Element element, RectF bounds, String elementId) {
            this.id = id;
            this.element = element;
            this.bounds = bounds;
            this.elementId = elementId;
            this.originalColor = null;
        }
    }

    // ==================== ZOOM / PAN STATE ====================

    private final Matrix matrix = new Matrix();
    private final float[] matrixValues = new float[9];
    private float minZoom = 1f;
    private float lastTouchX, lastTouchY;
    private boolean isDragging = false;
    private int activePointerId = MotionEvent.INVALID_POINTER_ID;

    private ScaleGestureDetector scaleDetector;
    private GestureDetector gestureDetector;
    private OverScroller scroller;
    private VelocityTracker velocityTracker;
    private ValueAnimator flingAnimator;
    private ValueAnimator zoomAnimator;

    // Tap detection (no delay — replaces onSingleTapConfirmed)
    private float tapDownX, tapDownY;
    private long tapDownTime;
    private boolean hasMoved = false;
    private static final float TAP_MOVE_SLOP = 10f;   // px — movement beyond this = drag, not tap
    private static final long TAP_MAX_DURATION = 250;  // ms — longer = long-press, not tap

    // ==================== SVG STATE ====================

    private SVG currentSvg;
    private Document svgDocument;
    private final Map<String, DeviceInfo> deviceMap = new LinkedHashMap<>();
    private String selectedDeviceId;

    // Parsed from the SVG viewBox attribute
    private float vbX = 0f, vbY = 0f, vbW = 1200f, vbH = 640f;

    // ==================== LIFECYCLE ====================

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentNetworkBinding.inflate(inflater, container, false);
        mViewModel = new ViewModelProvider(requireActivity()).get(SharedViewModel.class);

        setupZoomAndPan();
        showPlaceholder(true);
        loadSVGFromAssets("officetest.svg");

        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        mViewModel.getSvgUri().observe(getViewLifecycleOwner(), uri -> {
            if (uri != null) {
                Log.d(TAG, "URI received: " + uri);
                loadSvg(uri);
            }
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (flingAnimator != null) flingAnimator.cancel();
        if (zoomAnimator != null) zoomAnimator.cancel();
        if (velocityTracker != null) {
            velocityTracker.recycle();
            velocityTracker = null;
        }
        executorService.shutdown();
        binding = null;
    }

    // ==================== UI STATE ====================

    private void showPlaceholder(boolean show) {
        if (binding == null) return;
        if (show) {
            binding.svgPlaceholder.setVisibility(View.VISIBLE);
            binding.svgView.setVisibility(View.GONE);
            binding.progressBar.setVisibility(View.GONE);
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
            binding.progressBar.setVisibility(View.GONE);
        }
    }

    // ==================== SVG LOADING ====================

    private void loadSVGFromAssets(String assetFileName) {
        showLoading(true);
        executorService.execute(() -> {
            try {
                // Check file exists
                String[] assets = requireContext().getAssets().list("");
                boolean found = false;
                if (assets != null) {
                    for (String a : assets) {
                        if (a.equals(assetFileName)) { found = true; break; }
                    }
                }
                if (!found) {
                    mainHandler.post(() -> {
                        showLoading(false);
                        showPlaceholder(true);
                        Toast.makeText(requireContext(),
                                "SVG not found: " + assetFileName, Toast.LENGTH_LONG).show();
                    });
                    return;
                }

                // Pass 1: DOM for device parsing
                InputStream is1 = requireContext().getAssets().open(assetFileName);
                Document document = parseDocument(is1);
                is1.close();

                // Pass 2: SVG for rendering
                InputStream is2 = requireContext().getAssets().open(assetFileName);
                SVG svg = SVG.getFromInputStream(is2);
                is2.close();

                if (document != null) parseViewBox(document);
                Map<String, DeviceInfo> devices = extractDevices(document);

                mainHandler.post(() -> {
                    currentSvg = svg;
                    svgDocument = document;
                    deviceMap.clear();
                    deviceMap.putAll(devices);
                    renderSvg(svg);
                    showLoading(false);
                    logDeviceMap();
                });

            } catch (SVGParseException e) {
                Log.e(TAG, "SVG parse error", e);
                mainHandler.post(() -> { showLoading(false); showPlaceholder(true); });
            } catch (Exception e) {
                Log.e(TAG, "Error loading SVG from assets", e);
                mainHandler.post(() -> { showLoading(false); showPlaceholder(true); });
            }
        });
    }

    private void loadSvg(Uri uri) {
        showLoading(true);
        executorService.execute(() -> {
            try {
                InputStream is1 = requireContext().getContentResolver().openInputStream(uri);
                if (is1 == null) {
                    mainHandler.post(() -> { showLoading(false); showPlaceholder(true); });
                    return;
                }
                SVG svg = SVG.getFromInputStream(is1);
                is1.close();

                InputStream is2 = requireContext().getContentResolver().openInputStream(uri);
                Document document = parseDocument(is2);
                if (is2 != null) is2.close();

                if (document != null) parseViewBox(document);
                Map<String, DeviceInfo> devices = extractDevices(document);

                mainHandler.post(() -> {
                    currentSvg = svg;
                    svgDocument = document;
                    deviceMap.clear();
                    deviceMap.putAll(devices);
                    renderSvg(svg);
                    showLoading(false);
                    logDeviceMap();
                });

            } catch (Exception e) {
                Log.e(TAG, "Error loading SVG from URI", e);
                mainHandler.post(() -> { showLoading(false); showPlaceholder(true); });
            }
        });
    }

    private void logDeviceMap() {
        if (deviceMap.isEmpty()) {
            Toast.makeText(requireContext(), "No devices found", Toast.LENGTH_SHORT).show();
            Log.w(TAG, "No devices found in SVG");
        } else {
            Toast.makeText(requireContext(),
                    deviceMap.size() + " devices found", Toast.LENGTH_SHORT).show();
            for (Map.Entry<String, DeviceInfo> e : deviceMap.entrySet()) {
                DeviceInfo d = e.getValue();
                Log.d(TAG, "Device: " + d.id
                        + " | elementId=" + d.elementId
                        + " | bounds=" + d.bounds.toShortString());
            }
        }
    }

    // ==================== XML PARSING ====================

    private Document parseDocument(InputStream inputStream) {
        if (inputStream == null) return null;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setValidating(false);
            try {
                factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
                factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
                factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            } catch (Exception ignored) {}

            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setEntityResolver((pub, sys) ->
                    new org.xml.sax.InputSource(new java.io.StringReader("")));
            Document doc = builder.parse(inputStream);
            doc.getDocumentElement().normalize();
            return doc;
        } catch (Exception e) {
            Log.e(TAG, "Error parsing XML", e);
            return null;
        }
    }

    /**
     * Parses the viewBox attribute from the root SVG element.
     * Sets vbX, vbY, vbW, vbH accurately.
     * Your SVG has viewBox="100 0 1000 640" — this is critical for tap mapping.
     */
    private void parseViewBox(Document document) {
        Element root = document.getDocumentElement();
        String vb = root.getAttribute("viewBox");
        if (vb != null && !vb.isEmpty()) {
            String[] parts = vb.trim().split("[\\s,]+");
            if (parts.length == 4) {
                try {
                    vbX = Float.parseFloat(parts[0]);
                    vbY = Float.parseFloat(parts[1]);
                    vbW = Float.parseFloat(parts[2]);
                    vbH = Float.parseFloat(parts[3]);
                    Log.d(TAG, "ViewBox parsed: x=" + vbX + " y=" + vbY
                            + " w=" + vbW + " h=" + vbH);
                } catch (NumberFormatException e) {
                    Log.e(TAG, "Invalid viewBox values", e);
                }
            }
        } else {
            // Fallback: use width/height attributes
            try {
                String w = root.getAttribute("width");
                String h = root.getAttribute("height");
                if (!w.isEmpty()) vbW = Float.parseFloat(w.replaceAll("[^0-9.]", ""));
                if (!h.isEmpty()) vbH = Float.parseFloat(h.replaceAll("[^0-9.]", ""));
            } catch (NumberFormatException ignored) {}
            vbX = 0; vbY = 0;
            Log.d(TAG, "No viewBox found, using w=" + vbW + " h=" + vbH);
        }
    }

    // ==================== DEVICE EXTRACTION ====================

    /**
     * Core fix: Only extracts elements that are DIRECT children of the "Devices" group
     * or immediate children of sub-groups inside "Devices".
     *
     * Strategy:
     * 1. Find <g id="Devices">
     * 2. For each child group (e.g. <g id="Kitchen">), collect its direct shape children
     *    that have an id AND contain <metadata><elementId>
     * 3. Also collect any direct shape children of "Devices" itself
     *
     * This avoids picking up wall/furniture elements from other groups.
     */
    private Map<String, DeviceInfo> extractDevices(Document document) {
        Map<String, DeviceInfo> devices = new LinkedHashMap<>();
        if (document == null) return devices;

        try {
            Element devicesGroup = findElementById(document.getDocumentElement(), "Devices");
            if (devicesGroup == null) {
                Log.w(TAG, "No <g id='Devices'> found, scanning entire document");
                // Fallback: scan everything but require metadata/elementId
                collectDevicesRequiringMetadata(document.getDocumentElement(), devices, false);
                return devices;
            }

            // Iterate direct children of Devices group
            NodeList children = devicesGroup.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node child = children.item(i);
                if (!(child instanceof Element)) continue;
                Element el = (Element) child;
                String tag = el.getTagName().toLowerCase();
                String id = el.getAttribute("id");

                if ("g".equals(tag)) {
                    // Sub-group like <g id="Kitchen"> — scan ITS direct children
                    collectDirectShapeChildren(el, devices);
                } else if (!id.isEmpty()) {
                    // Direct shape in Devices group
                    processDeviceElement(el, devices);
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "Error extracting devices", e);
        }

        return devices;
    }

    /**
     * Collects direct shape children (path, rect, circle, ellipse, polygon, polyline, line)
     * from a group element. Only adds elements that have a non-empty id.
     */
    private void collectDirectShapeChildren(Element groupEl, Map<String, DeviceInfo> devices) {
        NodeList children = groupEl.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (!(child instanceof Element)) continue;
            Element el = (Element) child;
            String tag = el.getTagName().toLowerCase();

            if ("g".equals(tag)) {
                // Nested group — recurse one more level
                String id = el.getAttribute("id");
                if (!id.isEmpty()) {
                    // Try the group itself first
                    processDeviceElement(el, devices);
                }
                // Also check its children
                collectDirectShapeChildren(el, devices);
            } else {
                processDeviceElement(el, devices);
            }
        }
    }

    /**
     * Processes a single element as a potential device.
     * Requires: non-empty id attribute.
     * Computes bounds and extracts metadata elementId.
     */
    private void processDeviceElement(Element el, Map<String, DeviceInfo> devices) {
        String id = el.getAttribute("id");
        if (id == null || id.isEmpty()) return;

        // Skip if already added (avoid duplicates from nested group + child)
        if (devices.containsKey(id)) return;

        RectF bounds = computeBounds(el);
        if (bounds == null || bounds.isEmpty()) {
            Log.w(TAG, "Skipping device " + id + " — empty bounds");
            return;
        }

        String elementId = extractElementId(el);
        devices.put(id, new DeviceInfo(id, el, bounds, elementId));
        Log.d(TAG, "Added device: id=" + id + " elementId=" + elementId
                + " bounds=" + bounds.toShortString());
    }

    /**
     * Fallback scan that requires a <metadata><elementId> to consider an element a device.
     */
    private void collectDevicesRequiringMetadata(Element element,
                                                 Map<String, DeviceInfo> devices,
                                                 boolean insideDevices) {
        String id = element.getAttribute("id");
        String tag = element.getTagName().toLowerCase();

        boolean nowInsideDevices = insideDevices || "Devices".equals(id);

        if (nowInsideDevices && !id.isEmpty() && !"g".equals(tag)) {
            String elementId = extractElementId(element);
            if (elementId != null) {
                RectF bounds = computeBounds(element);
                if (bounds != null && !bounds.isEmpty()) {
                    devices.put(id, new DeviceInfo(id, element, bounds, elementId));
                }
            }
        }

        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element) {
                collectDevicesRequiringMetadata((Element) child, devices, nowInsideDevices);
            }
        }
    }

    // ==================== ELEMENT SEARCH ====================

    private Element findElementById(Element root, String targetId) {
        if (targetId.equals(root.getAttribute("id"))) return root;
        NodeList children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element) {
                Element found = findElementById((Element) child, targetId);
                if (found != null) return found;
            }
        }
        return null;
    }

    // ==================== BOUNDS COMPUTATION ====================

    private RectF computeBounds(Element element) {
        String tag = element.getTagName().toLowerCase();
        // Strip namespace prefix if present (e.g. "svg:path" -> "path")
        if (tag.contains(":")) tag = tag.substring(tag.indexOf(':') + 1);

        switch (tag) {
            case "g":       return computeGroupBounds(element);
            case "rect":    return computeRectBounds(element);
            case "circle":  return computeCircleBounds(element);
            case "ellipse": return computeEllipseBounds(element);
            case "path":    return computePathBounds(element);
            case "polygon":
            case "polyline":return computePolyBounds(element);
            case "line":    return computeLineBounds(element);
            case "use":     return computeUseBounds(element);
            default:
                Log.v(TAG, "Unsupported shape tag for bounds: " + tag);
                return null;
        }
    }

    private RectF computeGroupBounds(Element element) {
        RectF union = null;
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element) {
                RectF b = computeBounds((Element) child);
                if (b != null && !b.isEmpty()) {
                    if (union == null) union = new RectF(b);
                    else union.union(b);
                }
            }
        }
        return union;
    }

    private RectF computeRectBounds(Element el) {
        Float x = parseFloat(el, "x");
        Float y = parseFloat(el, "y");
        Float w = parseFloat(el, "width");
        Float h = parseFloat(el, "height");
        if (w == null || h == null || w <= 0 || h <= 0) return null;
        float xv = x != null ? x : 0f;
        float yv = y != null ? y : 0f;
        return new RectF(xv, yv, xv + w, yv + h);
    }

    private RectF computeCircleBounds(Element el) {
        Float cx = parseFloat(el, "cx");
        Float cy = parseFloat(el, "cy");
        Float r  = parseFloat(el, "r");
        if (r == null || r <= 0) return null;
        float cxv = cx != null ? cx : 0f;
        float cyv = cy != null ? cy : 0f;
        return new RectF(cxv - r, cyv - r, cxv + r, cyv + r);
    }

    private RectF computeEllipseBounds(Element el) {
        Float cx = parseFloat(el, "cx");
        Float cy = parseFloat(el, "cy");
        Float rx = parseFloat(el, "rx");
        Float ry = parseFloat(el, "ry");
        if (rx == null || ry == null) return null;
        float cxv = cx != null ? cx : 0f;
        float cyv = cy != null ? cy : 0f;
        return new RectF(cxv - rx, cyv - ry, cxv + rx, cyv + ry);
    }

    private RectF computePathBounds(Element el) {
        String d = el.getAttribute("d");
        return parsePathBounds(d);
    }

    private RectF computePolyBounds(Element el) {
        String pts = el.getAttribute("points");
        return parsePointsBounds(pts);
    }

    private RectF computeLineBounds(Element el) {
        Float x1 = parseFloat(el, "x1");
        Float y1 = parseFloat(el, "y1");
        Float x2 = parseFloat(el, "x2");
        Float y2 = parseFloat(el, "y2");
        float x1v = x1 != null ? x1 : 0f;
        float y1v = y1 != null ? y1 : 0f;
        float x2v = x2 != null ? x2 : 0f;
        float y2v = y2 != null ? y2 : 0f;
        return new RectF(Math.min(x1v, x2v), Math.min(y1v, y2v),
                Math.max(x1v, x2v), Math.max(y1v, y2v));
    }

    private RectF computeUseBounds(Element el) {
        // Basic: use x/y/width/height if present
        Float x = parseFloat(el, "x");
        Float y = parseFloat(el, "y");
        Float w = parseFloat(el, "width");
        Float h = parseFloat(el, "height");
        if (w == null || h == null) return null;
        float xv = x != null ? x : 0f;
        float yv = y != null ? y : 0f;
        return new RectF(xv, yv, xv + w, yv + h);
    }

    // ==================== PATH BOUNDS PARSER ====================

    /**
     * Accurate absolute-coordinate path bounds extraction.
     * Handles M/L/H/V/C/S/Q/T/A commands (absolute and relative).
     * Tracks current position for relative commands.
     */
    private RectF parsePathBounds(String d) {
        if (d == null || d.isEmpty()) return null;

        List<Float> xs = new ArrayList<>();
        List<Float> ys = new ArrayList<>();

        // Tokenize: insert space before each command letter
        String cleaned = d.replaceAll("([MmLlHhVvCcSsQqTtAaZz])", " $1 ")
                .replaceAll("([0-9])-", "$1 -")  // handle "10-5" as "10 -5"
                .trim();
        String[] tokens = cleaned.split("[\\s,]+");

        char cmd = 'M';
        int argIdx = 0;
        float curX = 0, curY = 0;
        float startX = 0, startY = 0; // for Z command

        // We'll accumulate args then process per-command
        List<Float> args = new ArrayList<>();

        for (String token : tokens) {
            if (token.isEmpty()) continue;
            char first = token.charAt(0);

            if (Character.isLetter(first)) {
                // Process any accumulated args for previous command
                processPathCommand(cmd, args, xs, ys,
                        new float[]{curX}, new float[]{curY},
                        new float[]{startX}, new float[]{startY});
                // Update curX/curY from last processed command if possible
                if (!xs.isEmpty()) curX = xs.get(xs.size() - 1);
                if (!ys.isEmpty()) curY = ys.get(ys.size() - 1);

                cmd = first;
                args.clear();
                argIdx = 0;
            } else {
                try {
                    args.add(Float.parseFloat(token));
                    argIdx++;
                } catch (NumberFormatException ignored) {}
            }
        }
        // Process last command
        processPathCommand(cmd, args, xs, ys,
                new float[]{curX}, new float[]{curY},
                new float[]{startX}, new float[]{startY});

        if (xs.isEmpty() || ys.isEmpty()) return null;

        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (float x : xs) { if (x < minX) minX = x; if (x > maxX) maxX = x; }
        for (float y : ys) { if (y < minY) minY = y; if (y > maxY) maxY = y; }

        if (minX == Float.MAX_VALUE || minY == Float.MAX_VALUE) return null;
        return new RectF(minX, minY, maxX, maxY);
    }

    /**
     * Processes path command arguments and extracts absolute x/y coordinates.
     * curX[0] and curY[0] are read/written as current pen position.
     */
    private void processPathCommand(char cmd, List<Float> args,
                                    List<Float> xs, List<Float> ys,
                                    float[] curX, float[] curY,
                                    float[] startX, float[] startY) {
        if (args.isEmpty()) return;

        switch (cmd) {
            case 'M': // absolute moveto
                for (int i = 0; i + 1 < args.size(); i += 2) {
                    float x = args.get(i), y = args.get(i + 1);
                    xs.add(x); ys.add(y);
                    curX[0] = x; curY[0] = y;
                    if (i == 0) { startX[0] = x; startY[0] = y; }
                }
                break;
            case 'm': // relative moveto
                for (int i = 0; i + 1 < args.size(); i += 2) {
                    curX[0] += args.get(i); curY[0] += args.get(i + 1);
                    xs.add(curX[0]); ys.add(curY[0]);
                    if (i == 0) { startX[0] = curX[0]; startY[0] = curY[0]; }
                }
                break;
            case 'L': // absolute lineto
                for (int i = 0; i + 1 < args.size(); i += 2) {
                    float x = args.get(i), y = args.get(i + 1);
                    xs.add(x); ys.add(y);
                    curX[0] = x; curY[0] = y;
                }
                break;
            case 'l': // relative lineto
                for (int i = 0; i + 1 < args.size(); i += 2) {
                    curX[0] += args.get(i); curY[0] += args.get(i + 1);
                    xs.add(curX[0]); ys.add(curY[0]);
                }
                break;
            case 'H': // absolute horizontal
                for (float v : args) {
                    xs.add(v); ys.add(curY[0]);
                    curX[0] = v;
                }
                break;
            case 'h': // relative horizontal
                for (float v : args) {
                    curX[0] += v;
                    xs.add(curX[0]); ys.add(curY[0]);
                }
                break;
            case 'V': // absolute vertical
                for (float v : args) {
                    xs.add(curX[0]); ys.add(v);
                    curY[0] = v;
                }
                break;
            case 'v': // relative vertical
                for (float v : args) {
                    curY[0] += v;
                    xs.add(curX[0]); ys.add(curY[0]);
                }
                break;
            case 'C': // absolute cubic bezier (6 args each)
                for (int i = 0; i + 5 < args.size(); i += 6) {
                    xs.add(args.get(i));     ys.add(args.get(i + 1));
                    xs.add(args.get(i + 2)); ys.add(args.get(i + 3));
                    xs.add(args.get(i + 4)); ys.add(args.get(i + 5));
                    curX[0] = args.get(i + 4); curY[0] = args.get(i + 5);
                }
                break;
            case 'c': // relative cubic bezier
                for (int i = 0; i + 5 < args.size(); i += 6) {
                    xs.add(curX[0] + args.get(i));     ys.add(curY[0] + args.get(i + 1));
                    xs.add(curX[0] + args.get(i + 2)); ys.add(curY[0] + args.get(i + 3));
                    curX[0] += args.get(i + 4); curY[0] += args.get(i + 5);
                    xs.add(curX[0]); ys.add(curY[0]);
                }
                break;
            case 'S': // absolute smooth cubic (4 args)
                for (int i = 0; i + 3 < args.size(); i += 4) {
                    xs.add(args.get(i));     ys.add(args.get(i + 1));
                    xs.add(args.get(i + 2)); ys.add(args.get(i + 3));
                    curX[0] = args.get(i + 2); curY[0] = args.get(i + 3);
                }
                break;
            case 's': // relative smooth cubic
                for (int i = 0; i + 3 < args.size(); i += 4) {
                    xs.add(curX[0] + args.get(i));     ys.add(curY[0] + args.get(i + 1));
                    curX[0] += args.get(i + 2); curY[0] += args.get(i + 3);
                    xs.add(curX[0]); ys.add(curY[0]);
                }
                break;
            case 'Q': // absolute quadratic bezier (4 args)
                for (int i = 0; i + 3 < args.size(); i += 4) {
                    xs.add(args.get(i));     ys.add(args.get(i + 1));
                    xs.add(args.get(i + 2)); ys.add(args.get(i + 3));
                    curX[0] = args.get(i + 2); curY[0] = args.get(i + 3);
                }
                break;
            case 'q': // relative quadratic bezier
                for (int i = 0; i + 3 < args.size(); i += 4) {
                    xs.add(curX[0] + args.get(i));     ys.add(curY[0] + args.get(i + 1));
                    curX[0] += args.get(i + 2); curY[0] += args.get(i + 3);
                    xs.add(curX[0]); ys.add(curY[0]);
                }
                break;
            case 'T': // absolute smooth quadratic (2 args)
                for (int i = 0; i + 1 < args.size(); i += 2) {
                    float x = args.get(i), y = args.get(i + 1);
                    xs.add(x); ys.add(y);
                    curX[0] = x; curY[0] = y;
                }
                break;
            case 't': // relative smooth quadratic
                for (int i = 0; i + 1 < args.size(); i += 2) {
                    curX[0] += args.get(i); curY[0] += args.get(i + 1);
                    xs.add(curX[0]); ys.add(curY[0]);
                }
                break;
            case 'A': // absolute arc (7 args: rx ry x-rot large-arc sweep x y)
                for (int i = 0; i + 6 < args.size(); i += 7) {
                    float x = args.get(i + 5), y = args.get(i + 6);
                    xs.add(x); ys.add(y);
                    curX[0] = x; curY[0] = y;
                }
                break;
            case 'a': // relative arc
                for (int i = 0; i + 6 < args.size(); i += 7) {
                    curX[0] += args.get(i + 5); curY[0] += args.get(i + 6);
                    xs.add(curX[0]); ys.add(curY[0]);
                }
                break;
            case 'Z':
            case 'z':
                xs.add(startX[0]); ys.add(startY[0]);
                curX[0] = startX[0]; curY[0] = startY[0];
                break;
        }
    }

    private RectF parsePointsBounds(String points) {
        if (points == null || points.isEmpty()) return null;
        String[] tokens = points.trim().split("[\\s,]+");
        List<Float> xs = new ArrayList<>(), ys = new ArrayList<>();
        for (int i = 0; i + 1 < tokens.length; i += 2) {
            try {
                xs.add(Float.parseFloat(tokens[i]));
                ys.add(Float.parseFloat(tokens[i + 1]));
            } catch (NumberFormatException ignored) {}
        }
        if (xs.isEmpty()) return null;
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (float x : xs) { if (x < minX) minX = x; if (x > maxX) maxX = x; }
        for (float y : ys) { if (y < minY) minY = y; if (y > maxY) maxY = y; }
        return new RectF(minX, minY, maxX, maxY);
    }

    private Float parseFloat(Element el, String attr) {
        String v = el.getAttribute(attr);
        if (v == null || v.isEmpty()) return null;
        try { return Float.parseFloat(v.trim()); }
        catch (NumberFormatException e) { return null; }
    }

    // ==================== METADATA EXTRACTION ====================

    private String extractElementId(Element element) {
        // Search recursively for <metadata><elementId> inside this element
        return findElementIdInNode(element);
    }

    private String findElementIdInNode(Node node) {
        if (node instanceof Element) {
            Element el = (Element) node;
            String tagName = el.getTagName();
            // Strip namespace
            if (tagName.contains(":")) tagName = tagName.substring(tagName.indexOf(':') + 1);

            if ("elementId".equalsIgnoreCase(tagName)) {
                String text = el.getTextContent();
                return (text != null && !text.trim().isEmpty()) ? text.trim() : null;
            }
        }
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            String result = findElementIdInNode(children.item(i));
            if (result != null) return result;
        }
        return null;
    }

    // ==================== SVG RENDERING ====================

    private void renderSvg(SVG svg) {
        try {
            // Render at exact viewBox pixel size → drawable pixel == SVG unit (offset by vbX/vbY)
            // This makes coordinate mapping zoom-invariant and always accurate.
            int renderW = Math.max(1, (int) vbW);
            int renderH = Math.max(1, (int) vbH);
            Picture picture = svg.renderToPicture(renderW, renderH);
            PictureDrawable drawable = new PictureDrawable(picture);

            binding.svgView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            binding.svgView.setImageDrawable(drawable);
            binding.svgView.setVisibility(View.VISIBLE);
            binding.svgPlaceholder.setVisibility(View.GONE);
            binding.progressBar.setVisibility(View.GONE);

            Log.d(TAG, "SVG rendered: " + drawable.getIntrinsicWidth()
                    + "x" + drawable.getIntrinsicHeight());

            binding.svgView.post(() -> {
                fitToView();
                binding.svgView.invalidate();
            });
        } catch (Exception e) {
            Log.e(TAG, "Error rendering SVG", e);
            showPlaceholder(true);
        }
    }

    private void fitToView() {
        if (binding == null || binding.svgView.getDrawable() == null) return;

        float dW = binding.svgView.getDrawable().getIntrinsicWidth();
        float dH = binding.svgView.getDrawable().getIntrinsicHeight();
        float vW = binding.svgView.getWidth();
        float vH = binding.svgView.getHeight();

        if (dW <= 0 || dH <= 0 || vW <= 0 || vH <= 0) {
            Log.e(TAG, "fitToView: invalid dimensions dW=" + dW + " dH=" + dH
                    + " vW=" + vW + " vH=" + vH);
            return;
        }

        minZoom = Math.min(vW / dW, vH / dH);
        Log.d(TAG, "fitToView: minZoom=" + minZoom);

        matrix.reset();
        matrix.postScale(minZoom, minZoom);
        matrix.postTranslate((vW - dW * minZoom) / 2f, (vH - dH * minZoom) / 2f);
        binding.svgView.setImageMatrix(matrix);
    }

    private void reRenderSvg() {
        if (svgDocument == null) return;
        try {
            String svgStr = documentToString(svgDocument);
            if (svgStr.isEmpty()) return;
            SVG svg = SVG.getFromString(svgStr);
            int renderW = Math.max(1, (int) vbW);
            int renderH = Math.max(1, (int) vbH);
            Picture picture = svg.renderToPicture(renderW, renderH);
            PictureDrawable drawable = new PictureDrawable(picture);
            binding.svgView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            binding.svgView.setImageDrawable(drawable);
            binding.svgView.setImageMatrix(matrix);
            binding.svgView.invalidate();
        } catch (Exception e) {
            Log.e(TAG, "Error re-rendering SVG", e);
        }
    }

    private String documentToString(Document doc) {
        if (doc == null) return "";
        try {
            TransformerFactory tf = TransformerFactory.newInstance();
            Transformer t = tf.newTransformer();
            StringWriter sw = new StringWriter();
            t.transform(new DOMSource(doc), new StreamResult(sw));
            return sw.toString();
        } catch (Exception e) {
            Log.e(TAG, "documentToString error", e);
            return "";
        }
    }

    // ==================== TOUCH HANDLING ====================

    private void setupZoomAndPan() {
        binding.svgView.setScaleType(ImageView.ScaleType.MATRIX);
        scroller = new OverScroller(requireContext(), new DecelerateInterpolator(2.5f));

        scaleDetector = new ScaleGestureDetector(requireContext(),
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(ScaleGestureDetector detector) {
                        float scaleFactor = detector.getScaleFactor();
                        float current = getScale();
                        float next = Math.max(minZoom, Math.min(MAX_ZOOM, current * scaleFactor));
                        float effective = next / current;
                        matrix.postScale(effective, effective,
                                detector.getFocusX(), detector.getFocusY());
                        clampMatrix();
                        binding.svgView.setImageMatrix(matrix);
                        return true;
                    }
                });

        // GestureDetector only handles double-tap zoom + fling.
        // Single tap is handled instantly in ACTION_UP — no 300ms double-tap wait.
        gestureDetector = new GestureDetector(requireContext(),
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onDoubleTap(MotionEvent e) {
                        // Cancel any pending tap — this is actually a double tap
                        hasMoved = true; // prevents ACTION_UP from also firing a tap
                        float target = (getScale() > minZoom + 0.5f) ? minZoom : DOUBLE_TAP_ZOOM;
                        animateZoomTo(target, e.getX(), e.getY());
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

        // GestureDetector handles double-tap + fling
        gestureDetector.onTouchEvent(event);
        // ScaleGestureDetector handles pinch-zoom
        scaleDetector.onTouchEvent(event);

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (flingAnimator != null) flingAnimator.cancel();
                if (zoomAnimator != null) zoomAnimator.cancel();
                scroller.forceFinished(true);

                activePointerId = event.getPointerId(0);
                lastTouchX = event.getX();
                lastTouchY = event.getY();
                isDragging = true;

                // Record tap-down for instant tap detection on ACTION_UP
                tapDownX = event.getX();
                tapDownY = event.getY();
                tapDownTime = event.getEventTime();
                hasMoved = false;
                break;

            case MotionEvent.ACTION_POINTER_DOWN:
                // Multi-finger — cancel any tap intent
                isDragging = false;
                hasMoved = true;
                break;

            case MotionEvent.ACTION_MOVE:
                if (!scaleDetector.isInProgress()) {
                    int idx = event.findPointerIndex(activePointerId);
                    if (idx == -1) { activePointerId = event.getPointerId(0); break; }

                    float dx = event.getX(idx) - lastTouchX;
                    float dy = event.getY(idx) - lastTouchY;

                    // Track total displacement from tap-down for slop check
                    float totalDx = event.getX(idx) - tapDownX;
                    float totalDy = event.getY(idx) - tapDownY;
                    if ((float) Math.sqrt(totalDx * totalDx + totalDy * totalDy) > TAP_MOVE_SLOP) {
                        hasMoved = true;
                    }

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
                // Instant tap check — no double-tap wait
                if (!hasMoved
                        && !scaleDetector.isInProgress()
                        && (event.getEventTime() - tapDownTime) < TAP_MAX_DURATION) {
                    // It's a tap — fire immediately
                    handleSvgTap(tapDownX, tapDownY);
                }

                activePointerId = MotionEvent.INVALID_POINTER_ID;
                isDragging = false;
                hasMoved = false;
                if (velocityTracker != null) {
                    velocityTracker.recycle();
                    velocityTracker = null;
                }
                break;

            case MotionEvent.ACTION_CANCEL:
                activePointerId = MotionEvent.INVALID_POINTER_ID;
                isDragging = false;
                hasMoved = true; // cancel tap
                if (velocityTracker != null) {
                    velocityTracker.recycle();
                    velocityTracker = null;
                }
                break;

            case MotionEvent.ACTION_POINTER_UP:
                int pi = event.getActionIndex();
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

    // ==================== TAP / DEVICE SELECTION ====================

    private void handleSvgTap(float touchX, float touchY) {
        if (svgDocument == null || deviceMap.isEmpty()) return;

        float[] svgCoords = touchToSvgCoords(touchX, touchY);
        float svgX = svgCoords[0];
        float svgY = svgCoords[1];

        Log.d(TAG, "Tap → SVG coords: (" + svgX + ", " + svgY + ")");

        String hitId = findDeviceAt(svgX, svgY);
        if (hitId != null) {
            onDeviceSelected(hitId);
        } else {
            deselectCurrentDevice();
        }
    }

    /**
     * Converts touch screen coordinates to SVG document coordinates.
     *
     * Pipeline:
     *   touch (screen px) → drawable px (via inverse matrix) → SVG units (via viewBox)
     *
     * The SVG renderer maps viewBox onto the drawable 1:1 in pixel space,
     * so we map drawable pixels back to SVG coordinates using the viewBox.
     */
    private float[] touchToSvgCoords(float touchX, float touchY) {
        // Step 1: invert zoom/pan matrix → get drawable-space pixel coordinates
        Matrix inverse = new Matrix();
        if (!matrix.invert(inverse)) {
            Log.e(TAG, "Matrix inversion failed");
            return new float[]{touchX, touchY};
        }
        float[] pt = {touchX, touchY};
        inverse.mapPoints(pt);

        // Step 2: drawable was rendered at exactly (vbW x vbH) pixels
        // so drawable pixel (px, py) maps directly to SVG unit (vbX + px, vbY + py).
        // No division needed — it's a direct 1:1 mapping plus the viewBox origin offset.
        float svgX = vbX + pt[0];
        float svgY = vbY + pt[1];

        Log.d(TAG, "drawablePx=(" + pt[0] + "," + pt[1] + ")"
                + " vbOrigin=(" + vbX + "," + vbY + ")"
                + " → svg=(" + svgX + "," + svgY + ")");

        return new float[]{svgX, svgY};
    }

    /**
     * Finds the device whose bounds contain (svgX, svgY).
     * If multiple match, picks the one with smallest area (most specific hit).
     * Uses TAP_TOLERANCE inset to make small elements easier to tap.
     */
    private String findDeviceAt(float svgX, float svgY) {
        String bestId = null;
        float smallestArea = Float.MAX_VALUE;

        for (Map.Entry<String, DeviceInfo> entry : deviceMap.entrySet()) {
            RectF bounds = entry.getValue().bounds;

            // Expand small bounds for easier tapping
            RectF expanded = new RectF(bounds);
            float inset = -TAP_TOLERANCE;
            // For very small elements, expand even more
            if (bounds.width() < 20 || bounds.height() < 20) {
                inset = -Math.max(TAP_TOLERANCE, 15f);
            }
            expanded.inset(inset, inset);

            if (expanded.contains(svgX, svgY)) {
                float area = bounds.width() * bounds.height();
                if (area < smallestArea) {
                    smallestArea = area;
                    bestId = entry.getKey();
                }
            }
        }

        if (bestId != null) {
            Log.d(TAG, "Hit: " + bestId);
        } else {
            Log.d(TAG, "No device hit at svgX=" + svgX + " svgY=" + svgY);
            // Debug: log all device bounds
            for (Map.Entry<String, DeviceInfo> e : deviceMap.entrySet()) {
                Log.v(TAG, "  " + e.getKey() + " bounds=" + e.getValue().bounds.toShortString());
            }
        }

        return bestId;
    }

    private void onDeviceSelected(String deviceId) {
        // Tapping selected device deselects it
        if (deviceId.equals(selectedDeviceId)) {
            deselectCurrentDevice();
            Toast.makeText(requireContext(), "Deselected: " + deviceId, Toast.LENGTH_SHORT).show();
            return;
        }

        // Special navigation
        if ("l_10".equals(deviceId)) {
            Intent intent = new Intent(requireContext(), ScannerActivity.class);
            intent.putExtra("device_id", deviceId);
            startActivity(intent);
            return;
        }

        deselectCurrentDevice();
        selectDevice(deviceId);

        DeviceInfo device = deviceMap.get(deviceId);
        String msg = "Device: " + deviceId;
        if (device != null && device.elementId != null) {
            msg += " | ID: " + device.elementId;
        }
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show();
        Log.d(TAG, "Selected: " + deviceId);
    }

    private void selectDevice(String deviceId) {
        DeviceInfo device = deviceMap.get(deviceId);
        if (device == null) return;

        if (device.originalColor == null) {
            device.originalColor = getElementFill(device.element);
        }

        setElementFillDeep(device.element, "#ff0000");
        selectedDeviceId = deviceId;
        reRenderSvg();
    }

    private void deselectCurrentDevice() {
        if (selectedDeviceId == null) return;
        DeviceInfo device = deviceMap.get(selectedDeviceId);
        if (device != null) {
            String restoreColor = device.originalColor != null ? device.originalColor : "#fb0";
            setElementFillDeep(device.element, restoreColor);
        }
        selectedDeviceId = null;
        reRenderSvg();
    }

    /**
     * Gets the fill color of an element, checking the element itself first,
     * then its children, then falling back to default.
     */
    private String getElementFill(Element element) {
        // Check fill attribute
        String fill = element.getAttribute("fill");
        if (fill != null && !fill.isEmpty() && !"none".equals(fill)) return fill;

        // Check style attribute for fill
        String style = element.getAttribute("style");
        if (style != null && style.contains("fill:")) {
            int idx = style.indexOf("fill:") + 5;
            int end = style.indexOf(';', idx);
            String f = end > 0 ? style.substring(idx, end).trim() : style.substring(idx).trim();
            if (!f.isEmpty() && !"none".equals(f)) return f;
        }

        // Check children
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element) {
                String f = getElementFill((Element) child);
                if (f != null) return f;
            }
        }

        return "#fb0"; // Default amber (matches SVG devices)
    }

    /**
     * Sets fill color on element AND all descendant shape elements.
     * This ensures groups with nested shapes are fully recolored.
     */
    private void setElementFillDeep(Element element, String color) {
        String tag = element.getTagName().toLowerCase();
        if (tag.contains(":")) tag = tag.substring(tag.indexOf(':') + 1);

        // Set fill on shape elements directly
        if (!"g".equals(tag) && !"metadata".equals(tag) && !"defs".equals(tag)) {
            String existing = element.getAttribute("fill");
            // Don't recolor elements with fill="none" (they're strokes/outlines)
            if (!"none".equals(existing)) {
                element.setAttribute("fill", color);
            }
        }

        // Recurse into children
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element) {
                setElementFillDeep((Element) child, color);
            }
        }
    }

    // ==================== ZOOM & PAN UTILITIES ====================

    private float getScale() {
        matrix.getValues(matrixValues);
        return matrixValues[Matrix.MSCALE_X];
    }

    private void clampMatrix() {
        if (binding == null || binding.svgView.getDrawable() == null) return;

        matrix.getValues(matrixValues);
        float scale = Math.max(minZoom, Math.min(MAX_ZOOM, matrixValues[Matrix.MSCALE_X]));
        float dW = binding.svgView.getDrawable().getIntrinsicWidth() * scale;
        float dH = binding.svgView.getDrawable().getIntrinsicHeight() * scale;
        float vW = binding.svgView.getWidth();
        float vH = binding.svgView.getHeight();

        float transX = matrixValues[Matrix.MTRANS_X];
        float transY = matrixValues[Matrix.MTRANS_Y];

        float minTX = (dW < vW) ? (vW - dW) / 2f : Math.min(0f, vW - dW);
        float maxTX = (dW < vW) ? (vW - dW) / 2f : 0f;
        float minTY = (dH < vH) ? (vH - dH) / 2f : Math.min(0f, vH - dH);
        float maxTY = (dH < vH) ? (vH - dH) / 2f : 0f;

        matrixValues[Matrix.MSCALE_X] = scale;
        matrixValues[Matrix.MSCALE_Y] = scale;
        matrixValues[Matrix.MTRANS_X] = Math.max(minTX, Math.min(maxTX, transX));
        matrixValues[Matrix.MTRANS_Y] = Math.max(minTY, Math.min(maxTY, transY));
        matrix.setValues(matrixValues);
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
            float factor = val / getScale();
            matrix.postScale(factor, factor, pivotX, pivotY);
            clampMatrix();
            binding.svgView.setImageMatrix(matrix);
        });
        zoomAnimator.start();
    }

    private void startFling(float velocityX, float velocityY) {
        if (binding == null || binding.svgView.getDrawable() == null) return;

        matrix.getValues(matrixValues);
        float scale = matrixValues[Matrix.MSCALE_X];
        float dW = binding.svgView.getDrawable().getIntrinsicWidth() * scale;
        float dH = binding.svgView.getDrawable().getIntrinsicHeight() * scale;
        float vW = binding.svgView.getWidth();
        float vH = binding.svgView.getHeight();

        int startX = (int) matrixValues[Matrix.MTRANS_X];
        int startY = (int) matrixValues[Matrix.MTRANS_Y];
        int minX = (dW < vW) ? (int) ((vW - dW) / 2f) : (int) (vW - dW);
        int maxX = (dW < vW) ? (int) ((vW - dW) / 2f) : 0;
        int minY = (dH < vH) ? (int) ((vH - dH) / 2f) : (int) (vH - dH);
        int maxY = (dH < vH) ? (int) ((vH - dH) / 2f) : 0;

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