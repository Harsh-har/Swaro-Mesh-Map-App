package no.nordicsemi.android.swaromapmesh.swajaui.Svg_Operations;

import android.graphics.RectF;
import android.util.Log;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

public class SvgParsers {

    private static final String TAG = "SvgParsers";

    public float vbX = 0f, vbY = 0f, vbW = 1200f, vbH = 640f;

    public final Map<String, List<String>> areaMap             = new LinkedHashMap<>();
    public final Map<String, Element>      selectionLayerElements = new HashMap<>();
    public final Map<String, RectF>        selectionLayerBounds   = new HashMap<>();
    public final Map<String, RectF>        deviceBoundsCache      = new LinkedHashMap<>();

    private final Map<String, RectF>    originalSelectionBounds   = new HashMap<>();
    private final Map<String, Element>  originalSelectionElements = new HashMap<>();

    // ══════════════════════════════════════════════════════════════════════
    //  DOCUMENT PARSING
    // ══════════════════════════════════════════════════════════════════════

    public Document parseDocument(InputStream inputStream) {
        if (inputStream == null) return null;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setValidating(false);
            try {
                factory.setFeature(
                        "http://xml.org/sax/features/external-general-entities", false);
                factory.setFeature(
                        "http://xml.org/sax/features/external-parameter-entities", false);
                factory.setFeature(
                        "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            } catch (Exception ignored) {}
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setEntityResolver(
                    (pub, sys) -> new org.xml.sax.InputSource(new java.io.StringReader("")));
            Document doc = builder.parse(inputStream);
            doc.getDocumentElement().normalize();
            return doc;
        } catch (Exception e) {
            Log.e(TAG, "parseDocument error", e);
            return null;
        }
    }

    public void parseViewBox(Document document) {
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
                    return;
                } catch (NumberFormatException e) {
                    Log.e(TAG, "Invalid viewBox: " + vb, e);
                }
            }
        }
        try {
            String w = root.getAttribute("width");
            String h = root.getAttribute("height");
            if (w != null && !w.isEmpty())
                vbW = Float.parseFloat(w.replaceAll("[^0-9.]", ""));
            if (h != null && !h.isEmpty())
                vbH = Float.parseFloat(h.replaceAll("[^0-9.]", ""));
        } catch (NumberFormatException ignored) {}
        vbX = 0;
        vbY = 0;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  DEVICE EXTRACTION
    // ══════════════════════════════════════════════════════════════════════

    public Map<String, DeviceInfo> extractDevices(Document document) {
        Map<String, DeviceInfo> devices = new LinkedHashMap<>();
        areaMap.clear();
        deviceBoundsCache.clear();
        if (document == null) return devices;
        try {
            collectRoomGroups(document.getDocumentElement(), devices);
        } catch (Exception e) {
            Log.e(TAG, "extractDevices error", e);
        }
        return devices;
    }

    private void collectRoomGroups(Element parent, Map<String, DeviceInfo> devices) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (!(node instanceof Element)) continue;
            Element el = (Element) node;
            if (!"g".equals(normalizeTag(el.getTagName()))) continue;

            String roomGroupId = el.getAttribute("id");
            if (roomGroupId == null || roomGroupId.isEmpty()) continue;

            Element techLayer = findDirectChildByIdPrefix(el, "Technician_Layer");
            if (techLayer != null) {
                List<String> iconIds = new ArrayList<>();
                collectTechnicianIcons(
                        techLayer, extractRoomCode(roomGroupId),
                        roomGroupId, devices, iconIds);
                areaMap.put(roomGroupId, iconIds);
                Log.d(TAG, "Room: '" + roomGroupId + "' → " + iconIds.size() + " icons");
            } else {
                collectRoomGroups(el, devices);
            }
        }
    }

    private void collectTechnicianIcons(Element techLayer, String roomCode,
                                        String roomGroupId,
                                        Map<String, DeviceInfo> devices,
                                        List<String> iconIds) {
        NodeList children = techLayer.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (!(child instanceof Element)) continue;
            Element el = (Element) child;
            if (!"g".equals(normalizeTag(el.getTagName()))) continue;
            String id = el.getAttribute("id");
            if (id == null || id.isEmpty()) continue;
            scanTechnicianGroup(el, roomCode, roomGroupId, devices, iconIds);
        }
    }

    private void scanTechnicianGroup(Element el, String roomCode, String roomGroupId,
                                     Map<String, DeviceInfo> devices,
                                     List<String> iconIds) {
        String id = el.getAttribute("id");
        if (id == null || id.isEmpty()) return;

        boolean hasRect   = hasDirectRectChild(el);
        boolean hasGChild = hasDirectGChild(el);

        if (hasRect && !hasGChild) {
            DeviceInfo info = buildDeviceInfo(id, el, roomCode, roomGroupId);
            if (info != null && !devices.containsKey(id)) {
                devices.put(id, info);
                iconIds.add(id);
                deviceBoundsCache.put(id, info.bounds);
            }
            return;
        }

        NodeList children = el.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (!(child instanceof Element)) continue;
            Element childEl = (Element) child;
            if (!"g".equals(normalizeTag(childEl.getTagName()))) continue;
            scanTechnicianGroup(childEl, roomCode, roomGroupId, devices, iconIds);
        }
    }

    private DeviceInfo buildDeviceInfo(String id, Element el,
                                       String roomCode, String roomGroupId) {
        String[] parts = id.split("_");
        if (parts.length < 3) return null;
        if (!roomCode.equalsIgnoreCase(parts[0])) return null;

        String deviceCode = parts[1];
        int    instance;
        try {
            instance = Integer.parseInt(parts[2]);
        } catch (NumberFormatException e) {
            return null;
        }
        String subType = (parts.length >= 4) ? parts[3] : "";
        RectF  bounds  = computeBounds(el);
        if (bounds == null || bounds.isEmpty()) return null;
        return new DeviceInfo(id, roomCode, deviceCode, instance, subType,
                el, bounds, roomGroupId);
    }

    // ══════════════════════════════════════════════════════════════════════
    //  USER_LAYER PARSING
    // ══════════════════════════════════════════════════════════════════════

    public Map<String, Element> parseUserLayer(Document document) {
        Map<String, Element> result = new LinkedHashMap<>();
        if (document == null) return result;
        collectUserLayerRecursive(document.getDocumentElement(), result);
        Log.d(TAG, "parseUserLayer: " + result.size() + " elements");
        return result;
    }

    private void collectUserLayerRecursive(Element parent,
                                           Map<String, Element> result) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (!(node instanceof Element)) continue;
            Element el = (Element) node;
            if (!"g".equals(normalizeTag(el.getTagName()))) continue;
            String id = el.getAttribute("id");
            if (id != null && id.startsWith("User_Layer")) {
                collectUserLayerElements(el, result);
            } else {
                collectUserLayerRecursive(el, result);
            }
        }
    }

    private void collectUserLayerElements(Element userLayer,
                                          Map<String, Element> result) {
        NodeList children = userLayer.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (!(child instanceof Element)) continue;
            Element el    = (Element) child;
            String  elId  = el.getAttribute("id");
            if (elId == null || elId.isEmpty()) continue;
            if (elId.endsWith("-2")) {
                result.put(elId.substring(0, elId.length() - 2), el);
                continue;
            }
            if ("g".equals(normalizeTag(el.getTagName()))) {
                collectUserLayerElements(el, result);
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  SELECTION LAYER PARSING
    // ══════════════════════════════════════════════════════════════════════

    public void parseSelectionLayer(Document document) {
        selectionLayerBounds.clear();
        selectionLayerElements.clear();
        originalSelectionBounds.clear();
        originalSelectionElements.clear();
        if (document == null) return;

        collectSelectionGroups(document.getDocumentElement());
        originalSelectionBounds.putAll(selectionLayerBounds);
        originalSelectionElements.putAll(selectionLayerElements);

        Log.d(TAG, "parseSelectionLayer: " + originalSelectionBounds.size()
                + " raw selection areas");
    }

    private void collectSelectionGroups(Element parent) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (!(child instanceof Element)) continue;
            Element el = (Element) child;
            String  id = el.getAttribute("id");
            if (isSelectionGroup(id)) {
                parseSelectionGroupContents(el);
            } else {
                collectSelectionGroups(el);
            }
        }
    }

    private boolean isSelectionGroup(String id) {
        if (id == null || id.isEmpty()) return false;
        String lower = id.toLowerCase();
        return lower.equals("selection")
                || lower.startsWith("selection-")
                || lower.equals("selection_layer");
    }

    private void parseSelectionGroupContents(Element selGroup) {
        NodeList children = selGroup.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (!(child instanceof Element)) continue;
            Element el  = (Element) child;
            String  tag = normalizeTag(el.getTagName());
            String  id  = el.getAttribute("id");
            if (id == null || id.isEmpty()) continue;

            RectF bounds = null;
            if      ("rect".equals(tag))    bounds = computeRectBounds(el);
            else if ("polygon".equals(tag)) bounds = computePolyBounds(el);
            else if ("path".equals(tag))    bounds = computePathBounds(el);

            if (bounds != null && !bounds.isEmpty()) {
                selectionLayerElements.put(id, el);
                selectionLayerBounds.put(id, bounds);
                Log.d(TAG, "  SelectionArea '" + id + "' → " + bounds);
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  DYNAMIC REMAP  —  no hardcoded names
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Automatically maps each room group to the selection polygon whose
     * bounds best overlap the union of that room's device icon bounds.
     * No room names are hardcoded anywhere in this method.
     */
    public void remapSelectionBoundsToAreaIds() {
        if (originalSelectionBounds.isEmpty() || areaMap.isEmpty()) {
            Log.d(TAG, "remapSelectionBoundsToAreaIds: nothing to remap");
            return;
        }

        Map<String, RectF>   newBounds   = new LinkedHashMap<>();
        Map<String, Element> newElements = new LinkedHashMap<>();

        for (String areaGroupId : areaMap.keySet()) {
            RectF deviceUnion = buildDeviceUnion(areaGroupId);
            if (deviceUnion == null) {
                Log.w(TAG, "  No device union for '" + areaGroupId + "' — skipping");
                continue;
            }

            String bestKey   = null;
            float  bestScore = -1f;

            for (Map.Entry<String, RectF> entry : originalSelectionBounds.entrySet()) {
                float score = overlapScore(deviceUnion, entry.getValue());
                if (score > bestScore) {
                    bestScore = score;
                    bestKey   = entry.getKey();
                }
            }

            if (bestKey != null && bestScore > 0f) {
                newBounds.put(areaGroupId, originalSelectionBounds.get(bestKey));
                Element el = originalSelectionElements.get(bestKey);
                if (el != null) newElements.put(areaGroupId, el);
                Log.d(TAG, "  Auto-mapped '" + areaGroupId
                        + "' → '" + bestKey
                        + "' (score=" + String.format("%.3f", bestScore) + ")");
            } else {
                Log.w(TAG, "  No selection match for '" + areaGroupId + "'");
            }
        }

        selectionLayerBounds.clear();
        selectionLayerBounds.putAll(newBounds);
        selectionLayerElements.clear();
        selectionLayerElements.putAll(newElements);

        Log.d(TAG, "remapSelectionBoundsToAreaIds: mapped "
                + newBounds.size() + " / " + areaMap.size() + " areas");
        for (Map.Entry<String, RectF> e : selectionLayerBounds.entrySet()) {
            Log.d(TAG, "  Final: '" + e.getKey() + "' → " + e.getValue());
        }
    }

    // ── Build union of all device bounds for a room ────────────────────────

    private RectF buildDeviceUnion(String areaGroupId) {
        List<String> iconIds = areaMap.get(areaGroupId);
        if (iconIds == null || iconIds.isEmpty()) return null;
        RectF union = null;
        for (String iconId : iconIds) {
            RectF b = deviceBoundsCache.get(iconId);
            if (b == null) continue;
            if (union == null) union = new RectF(b);
            else union.union(b);
        }
        return union;
    }

    // ── Overlap score: intersection area / min(areaA, areaB)  →  0..1 ────

    private float overlapScore(RectF a, RectF b) {
        if (a == null || b == null) return 0f;
        float iL = Math.max(a.left,   b.left);
        float iT = Math.max(a.top,    b.top);
        float iR = Math.min(a.right,  b.right);
        float iB = Math.min(a.bottom, b.bottom);
        if (iR <= iL || iB <= iT) return 0f;
        float intersection = (iR - iL) * (iB - iT);
        float areaA        = a.width() * a.height();
        float areaB        = b.width() * b.height();
        float minArea      = Math.min(areaA, areaB);
        if (minArea <= 0f) return 0f;
        return intersection / minArea;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  HELPER METHODS
    // ══════════════════════════════════════════════════════════════════════

    public String extractRoomCode(String roomGroupId) {
        if (roomGroupId == null || roomGroupId.isEmpty()) return null;
        String trimmed = roomGroupId.trim();
        int    last    = trimmed.lastIndexOf('_');
        if (last < 0 || last == trimmed.length() - 1) return null;
        String code = trimmed.substring(last + 1);
        if (!code.matches("[A-Z0-9]+")) return null;
        return code;
    }

    public Element findDirectChildById(Element parent, String targetId) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (!(child instanceof Element)) continue;
            Element el = (Element) child;
            if (targetId.equals(el.getAttribute("id"))) return el;
        }
        return null;
    }

    public Element findDirectChildByIdPrefix(Element parent, String prefix) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (!(child instanceof Element)) continue;
            Element el = (Element) child;
            String  id = el.getAttribute("id");
            if (id != null && id.startsWith(prefix)) return el;
        }
        return null;
    }

    private boolean hasDirectRectChild(Element el) {
        NodeList children = el.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element
                    && "rect".equals(normalizeTag(((Element) child).getTagName())))
                return true;
        }
        return false;
    }

    private boolean hasDirectGChild(Element el) {
        NodeList children = el.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element
                    && "g".equals(normalizeTag(((Element) child).getTagName())))
                return true;
        }
        return false;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  BOUNDS COMPUTATION
    // ══════════════════════════════════════════════════════════════════════

    public RectF computeBounds(Element element) {
        String tag = normalizeTag(element.getTagName());
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

    public RectF computeGroupBounds(Element element) {
        RectF    union    = null;
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (!(child instanceof Element)) continue;
            RectF b = computeBounds((Element) child);
            if (b != null && !b.isEmpty()) {
                if (union == null) union = new RectF(b);
                else union.union(b);
            }
        }
        return union;
    }

    public RectF computeRectBounds(Element el) {
        Float x = fa(el, "x"), y = fa(el, "y");
        Float w = fa(el, "width"), h = fa(el, "height");
        if (w == null || h == null || w <= 0 || h <= 0) return null;
        float xv = x != null ? x : 0f;
        float yv = y != null ? y : 0f;
        return new RectF(xv, yv, xv + w, yv + h);
    }

    public RectF computeCircleBounds(Element el) {
        Float cx = fa(el, "cx"), cy = fa(el, "cy"), r = fa(el, "r");
        if (r == null || r <= 0) return null;
        float cxv = cx != null ? cx : 0f;
        float cyv = cy != null ? cy : 0f;
        return new RectF(cxv - r, cyv - r, cxv + r, cyv + r);
    }

    public RectF computeEllipseBounds(Element el) {
        Float cx = fa(el, "cx"), cy = fa(el, "cy");
        Float rx = fa(el, "rx"), ry = fa(el, "ry");
        if (rx == null || ry == null) return null;
        float cxv = cx != null ? cx : 0f;
        float cyv = cy != null ? cy : 0f;
        return new RectF(cxv - rx, cyv - ry, cxv + rx, cyv + ry);
    }

    public RectF computePathBounds(Element el) {
        return parsePathBounds(el.getAttribute("d"));
    }

    public RectF computePolyBounds(Element el) {
        return parsePointsBounds(el.getAttribute("points"));
    }

    public RectF computeLineBounds(Element el) {
        Float x1 = fa(el, "x1"), y1 = fa(el, "y1");
        Float x2 = fa(el, "x2"), y2 = fa(el, "y2");
        float x1v = x1 != null ? x1 : 0f, y1v = y1 != null ? y1 : 0f;
        float x2v = x2 != null ? x2 : 0f, y2v = y2 != null ? y2 : 0f;
        return new RectF(Math.min(x1v, x2v), Math.min(y1v, y2v),
                Math.max(x1v, x2v), Math.max(y1v, y2v));
    }

    public RectF computeUseBounds(Element el) {
        Float x = fa(el, "x"), y = fa(el, "y");
        Float w = fa(el, "width"), h = fa(el, "height");
        if (w == null || h == null) return null;
        float xv = x != null ? x : 0f;
        float yv = y != null ? y : 0f;
        return new RectF(xv, yv, xv + w, yv + h);
    }

    private Float fa(Element el, String attr) {
        String v = el.getAttribute(attr);
        if (v == null || v.isEmpty()) return null;
        try {
            return Float.parseFloat(v.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ── Path bounds parser ─────────────────────────────────────────────────

    private RectF parsePathBounds(String d) {
        if (d == null || d.isEmpty()) return null;
        List<Float> xs = new ArrayList<>(), ys = new ArrayList<>();
        String cleaned = d.replaceAll("([MmLlHhVvCcSsQqTtAaZz])", " $1 ")
                .replaceAll("([0-9])-", "$1 -").trim();
        String[] tokens = cleaned.split("[\\s,]+");
        char         cmd     = 'M';
        float        curX    = 0, curY = 0, startX = 0, startY = 0;
        List<Float>  args    = new ArrayList<>();
        for (String token : tokens) {
            if (token.isEmpty()) continue;
            if (Character.isLetter(token.charAt(0))) {
                processPathCommand(cmd, args, xs, ys,
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
        processPathCommand(cmd, args, xs, ys,
                new float[]{curX}, new float[]{curY},
                new float[]{startX}, new float[]{startY});
        if (xs.isEmpty() || ys.isEmpty()) return null;
        float minX =  Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
        float minY =  Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (float x : xs) { if (x < minX) minX = x; if (x > maxX) maxX = x; }
        for (float y : ys) { if (y < minY) minY = y; if (y > maxY) maxY = y; }
        return minX == Float.MAX_VALUE ? null : new RectF(minX, minY, maxX, maxY);
    }

    private void processPathCommand(char cmd, List<Float> args,
                                    List<Float> xs, List<Float> ys,
                                    float[] cx, float[] cy,
                                    float[] sx, float[] sy) {
        if (args.isEmpty()) return;
        switch (cmd) {
            case 'M':
                for (int i = 0; i + 1 < args.size(); i += 2) {
                    float x = args.get(i), y = args.get(i + 1);
                    xs.add(x); ys.add(y);
                    cx[0] = x; cy[0] = y;
                    if (i == 0) { sx[0] = x; sy[0] = y; }
                }
                break;
            case 'm':
                for (int i = 0; i + 1 < args.size(); i += 2) {
                    cx[0] += args.get(i); cy[0] += args.get(i + 1);
                    xs.add(cx[0]); ys.add(cy[0]);
                    if (i == 0) { sx[0] = cx[0]; sy[0] = cy[0]; }
                }
                break;
            case 'L':
                for (int i = 0; i + 1 < args.size(); i += 2) {
                    float x = args.get(i), y = args.get(i + 1);
                    xs.add(x); ys.add(y);
                    cx[0] = x; cy[0] = y;
                }
                break;
            case 'l':
                for (int i = 0; i + 1 < args.size(); i += 2) {
                    cx[0] += args.get(i); cy[0] += args.get(i + 1);
                    xs.add(cx[0]); ys.add(cy[0]);
                }
                break;
            case 'H':
                for (float v : args) { xs.add(v); ys.add(cy[0]); cx[0] = v; }
                break;
            case 'h':
                for (float v : args) { cx[0] += v; xs.add(cx[0]); ys.add(cy[0]); }
                break;
            case 'V':
                for (float v : args) { xs.add(cx[0]); ys.add(v); cy[0] = v; }
                break;
            case 'v':
                for (float v : args) { cy[0] += v; xs.add(cx[0]); ys.add(cy[0]); }
                break;
            case 'C':
                for (int i = 0; i + 5 < args.size(); i += 6) {
                    xs.add(args.get(i));     ys.add(args.get(i + 1));
                    xs.add(args.get(i + 2)); ys.add(args.get(i + 3));
                    xs.add(args.get(i + 4)); ys.add(args.get(i + 5));
                    cx[0] = args.get(i + 4); cy[0] = args.get(i + 5);
                }
                break;
            case 'c':
                for (int i = 0; i + 5 < args.size(); i += 6) {
                    xs.add(cx[0] + args.get(i));     ys.add(cy[0] + args.get(i + 1));
                    xs.add(cx[0] + args.get(i + 2)); ys.add(cy[0] + args.get(i + 3));
                    cx[0] += args.get(i + 4); cy[0] += args.get(i + 5);
                    xs.add(cx[0]); ys.add(cy[0]);
                }
                break;
            case 'A':
                for (int i = 0; i + 6 < args.size(); i += 7) {
                    float x = args.get(i + 5), y = args.get(i + 6);
                    xs.add(x); ys.add(y);
                    cx[0] = x; cy[0] = y;
                }
                break;
            case 'a':
                for (int i = 0; i + 6 < args.size(); i += 7) {
                    cx[0] += args.get(i + 5); cy[0] += args.get(i + 6);
                    xs.add(cx[0]); ys.add(cy[0]);
                }
                break;
            case 'Z':
            case 'z':
                xs.add(sx[0]); ys.add(sy[0]);
                cx[0] = sx[0]; cy[0] = sy[0];
                break;
        }
    }

    private RectF parsePointsBounds(String points) {
        if (points == null || points.isEmpty()) return null;
        String[]    tokens = points.trim().split("[\\s,]+");
        List<Float> xs     = new ArrayList<>(), ys = new ArrayList<>();
        for (int i = 0; i + 1 < tokens.length; i += 2) {
            try {
                xs.add(Float.parseFloat(tokens[i]));
                ys.add(Float.parseFloat(tokens[i + 1]));
            } catch (NumberFormatException ignored) {}
        }
        if (xs.isEmpty()) return null;
        float minX =  Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
        float minY =  Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (float x : xs) { if (x < minX) minX = x; if (x > maxX) maxX = x; }
        for (float y : ys) { if (y < minY) minY = y; if (y > maxY) maxY = y; }
        return new RectF(minX, minY, maxX, maxY);
    }

    // ══════════════════════════════════════════════════════════════════════
    //  TAG NORMALIZER
    // ══════════════════════════════════════════════════════════════════════

    public String normalizeTag(String tag) {
        if (tag == null) return "";
        String t     = tag.toLowerCase();
        int    colon = t.indexOf(':');
        return colon >= 0 ? t.substring(colon + 1) : t;
    }
}