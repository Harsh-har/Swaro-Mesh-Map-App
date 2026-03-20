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

import com.caverock.androidsvg.SVG;
import com.caverock.androidsvg.SVGParseException;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.InputStream;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import dagger.hilt.android.AndroidEntryPoint;
import no.nordicsemi.android.swaromapmesh.databinding.FragmentNetworkBinding;

@AndroidEntryPoint
public class NetworkFragment extends Fragment {

    private static final String TAG = "NetworkFragment";
    private static final float  MAX_ZOOM        = 10f;
    private static final float  DOUBLE_TAP_ZOOM = 2.5f;
    private static final float  TAP_TOLERANCE   = 8f;
    private static final long   ANIMATION_DURATION = 280L;
    private static final int    FLING_DURATION     = 2000;

    private static final String COLOR_SELECTED    = "#ff0000";
    private static final String COLOR_PROVISIONED = "#00aa00";

    private static final String PREFS_NAME              = "mesh_prefs";
    private static final String KEY_PROVISIONED_DEVICES = "provisioned_devices";

    private FragmentNetworkBinding binding;

    private final ExecutorService loadExecutor   = Executors.newSingleThreadExecutor();
    private final ExecutorService renderExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Future<?> pendingRender;

    // ==================== DATA MODEL ====================

    private static class DeviceInfo {
        String id;
        Element element;
        RectF bounds;
        String elementId;

        DeviceInfo(String id, Element element, RectF bounds, String elementId) {
            this.id        = id;
            this.element   = element;
            this.bounds    = bounds;
            this.elementId = elementId;
        }
    }

    // ==================== ZOOM / PAN STATE ====================

    private final Matrix  matrix       = new Matrix();
    private final float[] matrixValues = new float[9];
    private float minZoom = 1f;
    private float lastTouchX, lastTouchY;
    private boolean isDragging     = false;
    private int     activePointerId = MotionEvent.INVALID_POINTER_ID;

    private ScaleGestureDetector scaleDetector;
    private GestureDetector      gestureDetector;
    private OverScroller         scroller;
    private VelocityTracker      velocityTracker;
    private ValueAnimator        flingAnimator;
    private ValueAnimator        zoomAnimator;

    private float   tapDownX, tapDownY;
    private long    tapDownTime;
    private boolean hasMoved = false;
    private static final float TAP_MOVE_SLOP    = 10f;
    private static final long  TAP_MAX_DURATION = 250;

    // ==================== SVG STATE ====================

    private SVG      currentSvg;
    private Document svgDocument;
    private final Map<String, DeviceInfo> deviceMap = new LinkedHashMap<>();

    // Key = System.identityHashCode(rectElement) → original fill
    private final Map<Integer, String> originalFillMap = new HashMap<>();

    private String selectedDeviceId;

    private float vbX = 0f, vbY = 0f, vbW = 1200f, vbH = 640f;

    // ==================== LIFECYCLE ====================

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentNetworkBinding.inflate(inflater, container, false);
        setupZoomAndPan();
        showPlaceholder(true);
        loadSVGFromAssets("output.svg");
        return binding.getRoot();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (svgDocument == null || deviceMap.isEmpty()) return;
        selectedDeviceId = null;
        Set<String> provisioned = getProvisionedFromPrefs();
        Log.d(TAG, "onResume — provisioned: " + provisioned);
        refreshAllColors(provisioned);
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

    // ==================== PREFS ====================

    private Set<String> getProvisionedFromPrefs() {
        if (getContext() == null) return new HashSet<>();
        SharedPreferences prefs = requireContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        Set<String> raw = prefs.getStringSet(KEY_PROVISIONED_DEVICES, new HashSet<>());
        return new HashSet<>(raw);
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
        loadExecutor.execute(() -> {
            try {
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

                InputStream is1      = requireContext().getAssets().open(assetFileName);
                Document    document = parseDocument(is1);
                is1.close();

                InputStream is2 = requireContext().getAssets().open(assetFileName);
                SVG         svg = SVG.getFromInputStream(is2);
                is2.close();

                if (document != null) parseViewBox(document);
                Map<String, DeviceInfo> devices = extractDevices(document);

                mainHandler.post(() -> {
                    currentSvg  = svg;
                    svgDocument = document;
                    deviceMap.clear();
                    deviceMap.putAll(devices);

                    originalFillMap.clear();
                    for (DeviceInfo info : deviceMap.values()) {
                        snapshotRectFill(info.element);
                    }
                    Log.d(TAG, "Snapshotted " + originalFillMap.size()
                            + " rect fills for " + deviceMap.size() + " icon devices");

                    Set<String> provisioned = getProvisionedFromPrefs();
                    refreshAllColors(provisioned);
                    renderSvg(svg);
                    showLoading(false);
                    logDeviceMap();
                });

            } catch (SVGParseException e) {
                Log.e(TAG, "SVG parse error", e);
                mainHandler.post(() -> { showLoading(false); showPlaceholder(true); });
            } catch (Exception e) {
                Log.e(TAG, "Error loading SVG", e);
                mainHandler.post(() -> { showLoading(false); showPlaceholder(true); });
            }
        });
    }

    private void loadSvg(Uri uri) {
        showLoading(true);
        loadExecutor.execute(() -> {
            try {
                InputStream is1 = requireContext().getContentResolver().openInputStream(uri);
                if (is1 == null) {
                    mainHandler.post(() -> { showLoading(false); showPlaceholder(true); });
                    return;
                }
                SVG svg = SVG.getFromInputStream(is1);
                is1.close();

                InputStream is2      = requireContext().getContentResolver().openInputStream(uri);
                Document    document = parseDocument(is2);
                if (is2 != null) is2.close();

                if (document != null) parseViewBox(document);
                Map<String, DeviceInfo> devices = extractDevices(document);

                mainHandler.post(() -> {
                    currentSvg  = svg;
                    svgDocument = document;
                    deviceMap.clear();
                    deviceMap.putAll(devices);

                    originalFillMap.clear();
                    for (DeviceInfo info : deviceMap.values()) {
                        snapshotRectFill(info.element);
                    }

                    Set<String> provisioned = getProvisionedFromPrefs();
                    refreshAllColors(provisioned);
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
        if (deviceMap.isEmpty()) { Log.w(TAG, "No icon devices found"); return; }
        Log.d(TAG, deviceMap.size() + " icon devices found:");
        for (Map.Entry<String, DeviceInfo> e : deviceMap.entrySet()) {
            DeviceInfo d = e.getValue();
            Log.d(TAG, "  Icon: " + d.id + " elementId=" + d.elementId
                    + " bounds=" + d.bounds.toShortString());
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

    private void parseViewBox(Document document) {
        Element root = document.getDocumentElement();
        String  vb   = root.getAttribute("viewBox");
        if (vb != null && !vb.isEmpty()) {
            String[] parts = vb.trim().split("[\\s,]+");
            if (parts.length == 4) {
                try {
                    vbX = Float.parseFloat(parts[0]);
                    vbY = Float.parseFloat(parts[1]);
                    vbW = Float.parseFloat(parts[2]);
                    vbH = Float.parseFloat(parts[3]);
                } catch (NumberFormatException e) {
                    Log.e(TAG, "Invalid viewBox", e);
                }
            }
        } else {
            try {
                String w = root.getAttribute("width");
                String h = root.getAttribute("height");
                if (!w.isEmpty()) vbW = Float.parseFloat(w.replaceAll("[^0-9.]", ""));
                if (!h.isEmpty()) vbH = Float.parseFloat(h.replaceAll("[^0-9.]", ""));
            } catch (NumberFormatException ignored) {}
            vbX = 0; vbY = 0;
        }
    }

    // ==================== DEVICE EXTRACTION ====================

    /**
     * Scans <g id="Icons"> and registers only TRUE LEAF icon groups.
     *
     * A TRUE LEAF icon group = a <g> element that:
     *   1. Has a non-empty id
     *   2. Has at least one <rect> as a direct child
     *   3. Has NO <g> children (i.e., it is not a container of other groups)
     *
     * This correctly skips:
     *   - BCD, STD, ENGRD, ACDD, RCAD, VCRD etc. (containers with child <g> elements)
     * And registers:
     *   - BCRN6AMD_3, VCRSFD_1, ENGRSOS_1, RCAPSD_1 etc. (true leaf icons)
     */
    private Map<String, DeviceInfo> extractDevices(Document document) {
        Map<String, DeviceInfo> devices = new LinkedHashMap<>();
        if (document == null) return devices;
        try {
            Element iconsGroup = findElementById(document.getDocumentElement(), "Icons");
            if (iconsGroup == null) {
                Log.w(TAG, "No <g id='Icons'> found — scanning full document");
                scanForLeafIcons(document.getDocumentElement(), devices);
            } else {
                scanForLeafIcons(iconsGroup, devices);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error extracting icon devices", e);
        }
        return devices;
    }

    /**
     * Recursively walks the element tree.
     * Registers a <g> as a device ONLY IF:
     *   1. It has a non-empty id
     *   2. It has at least one direct <rect> child  → it IS an icon
     *   3. It has NO direct <g> children            → it is NOT a container
     *
     * If condition 3 fails (has child <g>), we recurse deeper to find leaf icons inside.
     */
    private void scanForLeafIcons(Element el, Map<String, DeviceInfo> devices) {
        String id = el.getAttribute("id");

        if (!id.isEmpty() && hasDirectRectChild(el)) {
            if (!hasDirectGChild(el)) {
                // TRUE LEAF: has rect, no child groups → register it
                processDeviceElement(el, devices);
                return; // Don't recurse further
            }
            // Has rect AND child groups → it's a container, recurse into children
        }

        // Recurse into children to find leaf icons deeper
        NodeList children = el.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element) {
                String tag = ((Element) child).getTagName().toLowerCase();
                if (tag.contains(":")) tag = tag.substring(tag.indexOf(':') + 1);
                if ("g".equals(tag)) {
                    scanForLeafIcons((Element) child, devices);
                }
            }
        }
    }

    /**
     * Returns true if this element has at least one <rect> as a DIRECT child.
     */
    private boolean hasDirectRectChild(Element el) {
        NodeList children = el.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element) {
                String tag = ((Element) child).getTagName().toLowerCase();
                if (tag.contains(":")) tag = tag.substring(tag.indexOf(':') + 1);
                if ("rect".equals(tag)) return true;
            }
        }
        return false;
    }

    /**
     * Returns true if this element has at least one <g> as a DIRECT child.
     * Used to distinguish leaf icons from container groups.
     */
    private boolean hasDirectGChild(Element el) {
        NodeList children = el.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element) {
                String tag = ((Element) child).getTagName().toLowerCase();
                if (tag.contains(":")) tag = tag.substring(tag.indexOf(':') + 1);
                if ("g".equals(tag)) return true;
            }
        }
        return false;
    }

    private void processDeviceElement(Element el, Map<String, DeviceInfo> devices) {
        String id = el.getAttribute("id");
        if (id == null || id.isEmpty() || devices.containsKey(id)) return;
        RectF bounds = computeBounds(el);
        if (bounds == null || bounds.isEmpty()) return;
        String elementId = extractElementId(el);
        if (elementId == null) elementId = id;
        devices.put(id, new DeviceInfo(id, el, bounds, elementId));
        Log.d(TAG, "Registered leaf icon: id=" + id
                + " elementId=" + elementId
                + " bounds=" + bounds.toShortString());
    }

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

    // ==================== BOUNDS ====================

    private RectF computeBounds(Element element) {
        String tag = element.getTagName().toLowerCase();
        if (tag.contains(":")) tag = tag.substring(tag.indexOf(':') + 1);
        switch (tag) {
            case "g":        return computeGroupBounds(element);
            case "rect":     return computeRectBounds(element);
            case "circle":   return computeCircleBounds(element);
            case "ellipse":  return computeEllipseBounds(element);
            case "path":     return computePathBounds(element);
            case "polygon":
            case "polyline": return computePolyBounds(element);
            case "line":     return computeLineBounds(element);
            case "use":      return computeUseBounds(element);
            default:         return null;
        }
    }

    private RectF computeGroupBounds(Element element) {
        RectF    union    = null;
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
        Float x = fa(el,"x"), y = fa(el,"y"),
                w = fa(el,"width"), h = fa(el,"height");
        if (w == null || h == null || w <= 0 || h <= 0) return null;
        float xv = x != null ? x : 0f, yv = y != null ? y : 0f;
        return new RectF(xv, yv, xv + w, yv + h);
    }

    private RectF computeCircleBounds(Element el) {
        Float cx = fa(el,"cx"), cy = fa(el,"cy"), r = fa(el,"r");
        if (r == null || r <= 0) return null;
        float cxv = cx != null ? cx : 0f, cyv = cy != null ? cy : 0f;
        return new RectF(cxv - r, cyv - r, cxv + r, cyv + r);
    }

    private RectF computeEllipseBounds(Element el) {
        Float cx = fa(el,"cx"), cy = fa(el,"cy"),
                rx = fa(el,"rx"), ry = fa(el,"ry");
        if (rx == null || ry == null) return null;
        float cxv = cx != null ? cx : 0f, cyv = cy != null ? cy : 0f;
        return new RectF(cxv - rx, cyv - ry, cxv + rx, cyv + ry);
    }

    private RectF computePathBounds(Element el) {
        return parsePathBounds(el.getAttribute("d"));
    }

    private RectF computePolyBounds(Element el) {
        return parsePointsBounds(el.getAttribute("points"));
    }

    private RectF computeLineBounds(Element el) {
        Float x1 = fa(el,"x1"), y1 = fa(el,"y1"),
                x2 = fa(el,"x2"), y2 = fa(el,"y2");
        float x1v=x1!=null?x1:0f, y1v=y1!=null?y1:0f,
                x2v=x2!=null?x2:0f, y2v=y2!=null?y2:0f;
        return new RectF(Math.min(x1v,x2v), Math.min(y1v,y2v),
                Math.max(x1v,x2v), Math.max(y1v,y2v));
    }

    private RectF computeUseBounds(Element el) {
        Float x = fa(el,"x"), y = fa(el,"y"),
                w = fa(el,"width"), h = fa(el,"height");
        if (w == null || h == null) return null;
        float xv=x!=null?x:0f, yv=y!=null?y:0f;
        return new RectF(xv, yv, xv + w, yv + h);
    }

    private Float fa(Element el, String attr) {
        String v = el.getAttribute(attr);
        if (v == null || v.isEmpty()) return null;
        try { return Float.parseFloat(v.trim()); }
        catch (NumberFormatException e) { return null; }
    }

    // ==================== PATH BOUNDS ====================

    private RectF parsePathBounds(String d) {
        if (d == null || d.isEmpty()) return null;
        List<Float> xs = new ArrayList<>(), ys = new ArrayList<>();
        String cleaned = d.replaceAll("([MmLlHhVvCcSsQqTtAaZz])", " $1 ")
                .replaceAll("([0-9])-", "$1 -").trim();
        String[]    tokens = cleaned.split("[\\s,]+");
        char        cmd    = 'M';
        float       curX = 0, curY = 0, startX = 0, startY = 0;
        List<Float> args   = new ArrayList<>();
        for (String token : tokens) {
            if (token.isEmpty()) continue;
            if (Character.isLetter(token.charAt(0))) {
                ppCmd(cmd, args, xs, ys,
                        new float[]{curX}, new float[]{curY},
                        new float[]{startX}, new float[]{startY});
                if (!xs.isEmpty()) curX = xs.get(xs.size() - 1);
                if (!ys.isEmpty()) curY = ys.get(ys.size() - 1);
                cmd = token.charAt(0);
                args.clear();
            } else {
                try { args.add(Float.parseFloat(token)); }
                catch (NumberFormatException ignored) {}
            }
        }
        ppCmd(cmd, args, xs, ys,
                new float[]{curX}, new float[]{curY},
                new float[]{startX}, new float[]{startY});
        if (xs.isEmpty() || ys.isEmpty()) return null;
        float minX=Float.MAX_VALUE, maxX=-Float.MAX_VALUE,
                minY=Float.MAX_VALUE, maxY=-Float.MAX_VALUE;
        for (float x : xs) { if (x<minX) minX=x; if (x>maxX) maxX=x; }
        for (float y : ys) { if (y<minY) minY=y; if (y>maxY) maxY=y; }
        return minX == Float.MAX_VALUE ? null : new RectF(minX, minY, maxX, maxY);
    }

    private void ppCmd(char cmd, List<Float> args, List<Float> xs, List<Float> ys,
                       float[] cx, float[] cy, float[] sx, float[] sy) {
        if (args.isEmpty()) return;
        switch (cmd) {
            case 'M': for(int i=0;i+1<args.size();i+=2){float x=args.get(i),y=args.get(i+1);xs.add(x);ys.add(y);cx[0]=x;cy[0]=y;if(i==0){sx[0]=x;sy[0]=y;}} break;
            case 'm': for(int i=0;i+1<args.size();i+=2){cx[0]+=args.get(i);cy[0]+=args.get(i+1);xs.add(cx[0]);ys.add(cy[0]);if(i==0){sx[0]=cx[0];sy[0]=cy[0];}} break;
            case 'L': for(int i=0;i+1<args.size();i+=2){float x=args.get(i),y=args.get(i+1);xs.add(x);ys.add(y);cx[0]=x;cy[0]=y;} break;
            case 'l': for(int i=0;i+1<args.size();i+=2){cx[0]+=args.get(i);cy[0]+=args.get(i+1);xs.add(cx[0]);ys.add(cy[0]);} break;
            case 'H': for(float v:args){xs.add(v);ys.add(cy[0]);cx[0]=v;} break;
            case 'h': for(float v:args){cx[0]+=v;xs.add(cx[0]);ys.add(cy[0]);} break;
            case 'V': for(float v:args){xs.add(cx[0]);ys.add(v);cy[0]=v;} break;
            case 'v': for(float v:args){cy[0]+=v;xs.add(cx[0]);ys.add(cy[0]);} break;
            case 'C': for(int i=0;i+5<args.size();i+=6){xs.add(args.get(i));ys.add(args.get(i+1));xs.add(args.get(i+2));ys.add(args.get(i+3));xs.add(args.get(i+4));ys.add(args.get(i+5));cx[0]=args.get(i+4);cy[0]=args.get(i+5);} break;
            case 'c': for(int i=0;i+5<args.size();i+=6){xs.add(cx[0]+args.get(i));ys.add(cy[0]+args.get(i+1));xs.add(cx[0]+args.get(i+2));ys.add(cy[0]+args.get(i+3));cx[0]+=args.get(i+4);cy[0]+=args.get(i+5);xs.add(cx[0]);ys.add(cy[0]);} break;
            case 'A': for(int i=0;i+6<args.size();i+=7){float x=args.get(i+5),y=args.get(i+6);xs.add(x);ys.add(y);cx[0]=x;cy[0]=y;} break;
            case 'a': for(int i=0;i+6<args.size();i+=7){cx[0]+=args.get(i+5);cy[0]+=args.get(i+6);xs.add(cx[0]);ys.add(cy[0]);} break;
            case 'Z': case 'z': xs.add(sx[0]);ys.add(sy[0]);cx[0]=sx[0];cy[0]=sy[0]; break;
        }
    }

    private RectF parsePointsBounds(String points) {
        if (points == null || points.isEmpty()) return null;
        String[]    tokens = points.trim().split("[\\s,]+");
        List<Float> xs = new ArrayList<>(), ys = new ArrayList<>();
        for (int i = 0; i + 1 < tokens.length; i += 2) {
            try {
                xs.add(Float.parseFloat(tokens[i]));
                ys.add(Float.parseFloat(tokens[i + 1]));
            } catch (NumberFormatException ignored) {}
        }
        if (xs.isEmpty()) return null;
        float minX=Float.MAX_VALUE, maxX=-Float.MAX_VALUE,
                minY=Float.MAX_VALUE, maxY=-Float.MAX_VALUE;
        for (float x : xs) { if (x<minX) minX=x; if (x>maxX) maxX=x; }
        for (float y : ys) { if (y<minY) minY=y; if (y>maxY) maxY=y; }
        return new RectF(minX, minY, maxX, maxY);
    }

    // ==================== METADATA ====================

    private String extractElementId(Element element) {
        return findElementIdInNode(element);
    }

    private String findElementIdInNode(Node node) {
        if (node instanceof Element) {
            Element el  = (Element) node;
            String  tag = el.getTagName();
            if (tag.contains(":")) tag = tag.substring(tag.indexOf(':') + 1);
            if ("elementId".equalsIgnoreCase(tag)) {
                String text = el.getTextContent();
                return (text != null && !text.trim().isEmpty()) ? text.trim() : null;
            }
        }
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            String r = findElementIdInNode(children.item(i));
            if (r != null) return r;
        }
        return null;
    }

    // ==================== SVG RENDERING ====================

    private void renderSvg(SVG svg) {
        try {
            int renderW = Math.max(1, (int) vbW);
            int renderH = Math.max(1, (int) vbH);
            Picture         picture  = svg.renderToPicture(renderW, renderH);
            PictureDrawable drawable = new PictureDrawable(picture);
            binding.svgView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            binding.svgView.setImageDrawable(drawable);
            binding.svgView.setVisibility(View.VISIBLE);
            binding.svgPlaceholder.setVisibility(View.GONE);
            binding.progressBar.setVisibility(View.GONE);
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
        if (dW <= 0 || dH <= 0 || vW <= 0 || vH <= 0) return;
        minZoom = Math.min(vW / dW, vH / dH);
        matrix.reset();
        matrix.postScale(minZoom, minZoom);
        matrix.postTranslate((vW - dW * minZoom) / 2f, (vH - dH * minZoom) / 2f);
        binding.svgView.setImageMatrix(matrix);
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
                SVG  svg     = SVG.getFromString(svgStr);
                int  renderW = Math.max(1, (int) vbW);
                int  renderH = Math.max(1, (int) vbH);
                Picture         picture  = svg.renderToPicture(renderW, renderH);
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

    // ==================== COLOR MANAGEMENT ====================

    /**
     * Snapshot the fill of the FIRST direct <rect> child of the icon group.
     */
    private void snapshotRectFill(Element iconGroup) {
        NodeList children = iconGroup.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element) {
                String tag = ((Element) child).getTagName().toLowerCase();
                if (tag.contains(":")) tag = tag.substring(tag.indexOf(':') + 1);
                if ("rect".equals(tag)) {
                    int    key  = System.identityHashCode((Element) child);
                    String fill = ((Element) child).getAttribute("fill");
                    if (fill != null && !fill.isEmpty()) {
                        originalFillMap.put(key, fill);
                        Log.d(TAG, "Snapshotted rect fill for "
                                + iconGroup.getAttribute("id") + " = " + fill);
                    }
                    return; // Only snapshot first rect
                }
            }
        }
    }

    /**
     * Apply color ONLY to the first direct <rect> child (background) of the icon group.
     * Inner paths/symbols are NOT touched.
     */
    private void applyColorToDevice(Element iconGroup, String color) {
        NodeList children = iconGroup.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element) {
                String tag = ((Element) child).getTagName().toLowerCase();
                if (tag.contains(":")) tag = tag.substring(tag.indexOf(':') + 1);
                if ("rect".equals(tag)) {
                    ((Element) child).setAttribute("fill", color);
                    return;
                }
            }
        }
    }

    /**
     * Restore ONLY the first direct <rect> child to its original snapshotted fill.
     */
    private void restoreOriginalColors(Element iconGroup) {
        NodeList children = iconGroup.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element) {
                String tag = ((Element) child).getTagName().toLowerCase();
                if (tag.contains(":")) tag = tag.substring(tag.indexOf(':') + 1);
                if ("rect".equals(tag)) {
                    int    key      = System.identityHashCode((Element) child);
                    String origFill = originalFillMap.get(key);
                    if (origFill != null) {
                        ((Element) child).setAttribute("fill", origFill);
                    }
                    return;
                }
            }
        }
    }

    /**
     * Master color refresh.
     * Provisioned → GREEN, Selected → RED, else → original color.
     */
    private void refreshAllColors(Set<String> provisionedIds) {
        if (deviceMap.isEmpty()) return;
        for (Map.Entry<String, DeviceInfo> entry : deviceMap.entrySet()) {
            String     id   = entry.getKey();
            DeviceInfo info = entry.getValue();

            boolean isProvisioned = provisionedIds != null && provisionedIds.contains(id);
            boolean isSelected    = id.equals(selectedDeviceId);

            if (isProvisioned) {
                applyColorToDevice(info.element, COLOR_PROVISIONED);
                Log.d(TAG, "GREEN → " + id);
            } else if (isSelected) {
                applyColorToDevice(info.element, COLOR_SELECTED);
                Log.d(TAG, "RED   → " + id);
            } else {
                restoreOriginalColors(info.element);
            }
        }
    }

    // ==================== TOUCH ====================

    private void setupZoomAndPan() {
        binding.svgView.setScaleType(ImageView.ScaleType.MATRIX);
        scroller = new OverScroller(requireContext(), new DecelerateInterpolator(2.5f));

        scaleDetector = new ScaleGestureDetector(requireContext(),
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override public boolean onScale(ScaleGestureDetector d) {
                        float cur  = getScale();
                        float next = Math.max(minZoom, Math.min(MAX_ZOOM,
                                cur * d.getScaleFactor()));
                        matrix.postScale(next / cur, next / cur,
                                d.getFocusX(), d.getFocusY());
                        clampMatrix();
                        binding.svgView.setImageMatrix(matrix);
                        return true;
                    }
                });

        gestureDetector = new GestureDetector(requireContext(),
                new GestureDetector.SimpleOnGestureListener() {
                    @Override public boolean onDoubleTap(MotionEvent e) {
                        hasMoved = true;
                        animateZoomTo(
                                getScale() > minZoom + 0.5f ? minZoom : DOUBLE_TAP_ZOOM,
                                e.getX(), e.getY());
                        return true;
                    }
                    @Override public boolean onFling(MotionEvent e1, MotionEvent e2,
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
                lastTouchX = event.getX(); lastTouchY = event.getY();
                isDragging  = true;
                tapDownX    = event.getX(); tapDownY = event.getY();
                tapDownTime = event.getEventTime(); hasMoved = false;
                break;

            case MotionEvent.ACTION_POINTER_DOWN:
                isDragging = false; hasMoved = true;
                break;

            case MotionEvent.ACTION_MOVE:
                if (!scaleDetector.isInProgress()) {
                    int idx = event.findPointerIndex(activePointerId);
                    if (idx == -1) { activePointerId = event.getPointerId(0); break; }
                    float dx  = event.getX(idx) - lastTouchX;
                    float dy  = event.getY(idx) - lastTouchY;
                    float tdx = event.getX(idx) - tapDownX;
                    float tdy = event.getY(idx) - tapDownY;
                    if ((float) Math.sqrt(tdx * tdx + tdy * tdy) > TAP_MOVE_SLOP)
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
                        && (event.getEventTime() - tapDownTime) < TAP_MAX_DURATION)
                    handleSvgTap(tapDownX, tapDownY);
                activePointerId = MotionEvent.INVALID_POINTER_ID;
                isDragging = false; hasMoved = false;
                if (velocityTracker != null) {
                    velocityTracker.recycle(); velocityTracker = null;
                }
                break;

            case MotionEvent.ACTION_CANCEL:
                activePointerId = MotionEvent.INVALID_POINTER_ID;
                isDragging = false; hasMoved = true;
                if (velocityTracker != null) {
                    velocityTracker.recycle(); velocityTracker = null;
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

    // ==================== TAP / HIT TEST ====================

    private void handleSvgTap(float touchX, float touchY) {
        if (svgDocument == null || deviceMap.isEmpty()) return;
        float[] c     = touchToSvgCoords(touchX, touchY);
        String  hitId = findDeviceAt(c[0], c[1]);
        if (hitId != null) onDeviceTapped(hitId);
        else               deselectCurrentDevice();
    }

    private float[] touchToSvgCoords(float touchX, float touchY) {
        Matrix inverse = new Matrix();
        if (!matrix.invert(inverse)) return new float[]{touchX, touchY};
        float[] pt = {touchX, touchY};
        inverse.mapPoints(pt);
        return new float[]{vbX + pt[0], vbY + pt[1]};
    }

    private String findDeviceAt(float svgX, float svgY) {
        String bestId      = null;
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

    // ==================== DEVICE TAP LOGIC ====================

    private void onDeviceTapped(String deviceId) {
        Set<String> provisioned = getProvisionedFromPrefs();

        if (provisioned.contains(deviceId)) {
            Toast.makeText(requireContext(),
                    deviceId + " is already provisioned",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        if (deviceId.equals(selectedDeviceId)) {
            deselectCurrentDevice();
            return;
        }

        deselectCurrentDevice();
        selectedDeviceId = deviceId;

        DeviceInfo device = deviceMap.get(deviceId);
        if (device != null) {
            applyColorToDevice(device.element, COLOR_SELECTED);
        }
        reRenderSvg();

        Intent intent = new Intent(requireContext(), DeviceDetailActivity.class);
        intent.putExtra(DeviceDetailActivity.EXTRA_DEVICE_ID, deviceId);
        intent.putExtra(DeviceDetailActivity.EXTRA_ELEMENT_ID,
                device != null ? device.elementId : null);
        intent.putExtra(DeviceDetailActivity.EXTRA_DEVICE_NAME, deviceId);
        startActivity(intent);
    }

    private void deselectCurrentDevice() {
        if (selectedDeviceId == null) return;
        DeviceInfo  device      = deviceMap.get(selectedDeviceId);
        Set<String> provisioned = getProvisionedFromPrefs();
        if (device != null && !provisioned.contains(selectedDeviceId)) {
            restoreOriginalColors(device.element);
            reRenderSvg();
        }
        selectedDeviceId = null;
    }

    // ==================== ZOOM & PAN ====================

    private float getScale() {
        matrix.getValues(matrixValues);
        return matrixValues[Matrix.MSCALE_X];
    }

    private void clampMatrix() {
        if (binding == null || binding.svgView.getDrawable() == null) return;
        matrix.getValues(matrixValues);
        float scale = Math.max(minZoom, Math.min(MAX_ZOOM, matrixValues[Matrix.MSCALE_X]));
        float dW    = binding.svgView.getDrawable().getIntrinsicWidth()  * scale;
        float dH    = binding.svgView.getDrawable().getIntrinsicHeight() * scale;
        float vW    = binding.svgView.getWidth();
        float vH    = binding.svgView.getHeight();
        float transX = matrixValues[Matrix.MTRANS_X];
        float transY = matrixValues[Matrix.MTRANS_Y];
        float minTX  = (dW < vW) ? (vW-dW)/2f : Math.min(0f, vW-dW);
        float maxTX  = (dW < vW) ? (vW-dW)/2f : 0f;
        float minTY  = (dH < vH) ? (vH-dH)/2f : Math.min(0f, vH-dH);
        float maxTY  = (dH < vH) ? (vH-dH)/2f : 0f;
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
            matrix.postScale(val / getScale(), val / getScale(), pivotX, pivotY);
            clampMatrix();
            binding.svgView.setImageMatrix(matrix);
        });
        zoomAnimator.start();
    }

    private void startFling(float velocityX, float velocityY) {
        if (binding == null || binding.svgView.getDrawable() == null) return;
        matrix.getValues(matrixValues);
        float scale  = matrixValues[Matrix.MSCALE_X];
        float dW     = binding.svgView.getDrawable().getIntrinsicWidth()  * scale;
        float dH     = binding.svgView.getDrawable().getIntrinsicHeight() * scale;
        float vW     = binding.svgView.getWidth();
        float vH     = binding.svgView.getHeight();
        int   startX = (int) matrixValues[Matrix.MTRANS_X];
        int   startY = (int) matrixValues[Matrix.MTRANS_Y];
        int   minX   = (dW < vW) ? (int)((vW-dW)/2f) : (int)(vW-dW);
        int   maxX   = (dW < vW) ? (int)((vW-dW)/2f) : 0;
        int   minY   = (dH < vH) ? (int)((vH-dH)/2f) : (int)(vH-dH);
        int   maxY   = (dH < vH) ? (int)((vH-dH)/2f) : 0;
        scroller.fling(startX, startY, (int)velocityX, (int)velocityY,
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