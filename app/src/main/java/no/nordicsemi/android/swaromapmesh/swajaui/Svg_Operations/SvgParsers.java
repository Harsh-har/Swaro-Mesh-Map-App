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

/**
 * Parses the NEW SVG format:
 *
 *  &lt;g id="RoomName_ROOMCODE"&gt;           ← room group
 *      &lt;g id="Technician_Layer"&gt;          ← icon definitions  (shown by default)
 *          &lt;g id="ROOMCODE_DEVCODE_N"&gt; … &lt;/g&gt;
 *      &lt;/g&gt;
 *      &lt;g id="User_Layer"&gt;               ← physical nodes    (hidden by default)
 *          &lt;* id="ROOMCODE_DEVCODE_N-2"&gt; … &lt;/&gt;
 *      &lt;/g&gt;
 *  &lt;/g&gt;
 *
 * Device ID format:  ROOMCODE_DEVCODE_INSTANCE[_SUBTYPE]
 *   e.g.  MBDR_CLE02_1   MBDR_PSS04_7   MBDR_IR01_1_AC
 */
public class SvgParsers {

    private static final String TAG = "SvgParsers";

    // ── ViewBox ───────────────────────────────────────────────────────────
    public float vbX = 0f, vbY = 0f, vbW = 1200f, vbH = 640f;

    // ── Area maps ─────────────────────────────────────────────────────────
    /** Full room-group id  →  list of Technician_Layer icon ids in that room */
    public final Map<String, List<String>>  areaMap              = new LinkedHashMap<>();

    /** Full room-group id  →  selection_layer Element (for dim logic) */
    public final Map<String, Element>       selectionLayerElements = new HashMap<>();

    /** Full room-group id  →  bounding box in SVG coordinates */
    public final Map<String, RectF>         selectionLayerBounds   = new HashMap<>();

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
                        "http://apache.org/xml/features/nonvalidating/load-external-dtd",
                        false);
            } catch (Exception ignored) {}
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setEntityResolver(
                    (pub, sys) -> new org.xml.sax.InputSource(
                            new java.io.StringReader("")));
            Document doc = builder.parse(inputStream);
            doc.getDocumentElement().normalize();
            return doc;
        } catch (Exception e) {
            Log.e(TAG, "parseDocument error", e);
            return null;
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  VIEWBOX
    // ══════════════════════════════════════════════════════════════════════

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
    //  DEVICE EXTRACTION  — Technician_Layer
    //
    //  Walk every top-level <g> that looks like a room group
    //  (its id ends with an uppercase code, e.g. "Master_Bedroom_MBDR").
    //  Inside each room group find the child <g id="Technician_Layer">
    //  and collect every leaf icon <g> whose id matches the pattern
    //  ROOMCODE_DEVCODE_INSTANCE[_SUBTYPE].
    // ══════════════════════════════════════════════════════════════════════

    public Map<String, DeviceInfo> extractDevices(Document document) {
        Map<String, DeviceInfo> devices = new LinkedHashMap<>();
        areaMap.clear();
        if (document == null) return devices;

        try {
            Element svgRoot = document.getDocumentElement();
            // ← Walk recursively, not just top-level
            collectRoomGroups(svgRoot, devices);
        } catch (Exception e) {
            Log.e(TAG, "extractDevices error", e);
        }
        return devices;
    }

    private void collectRoomGroups(Element parent,
                                   Map<String, DeviceInfo> devices) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (!(node instanceof Element)) continue;
            Element el = (Element) node;
            if (!"g".equals(normalizeTag(el.getTagName()))) continue;

            String roomGroupId = el.getAttribute("id");
            if (roomGroupId == null || roomGroupId.isEmpty()) continue;

            String roomCode = extractRoomCode(roomGroupId);
            Element techLayer = findDirectChildById(el, "Technician_Layer");

            if (roomCode != null && techLayer != null) {
                // This is a room group — collect its icons
                int before = devices.size();
                List<String> iconIds = new ArrayList<>();
                collectTechnicianIcons(techLayer, roomCode, roomGroupId,
                        devices, iconIds);
                areaMap.put(roomGroupId, iconIds);
                Log.d(TAG, "Room '" + roomGroupId + "' (code=" + roomCode
                        + ") → " + (devices.size() - before) + " icons");
            } else {
                // Not a room group — descend into it (handles wrappers like <g id="Other">)
                collectRoomGroups(el, devices);
            }
        }
    }
    /**
     * Walk direct-child &lt;g&gt; elements inside Technician_Layer.
     * Each child whose id matches ROOMCODE_DEVCODE_INSTANCE[_SUBTYPE]
     * becomes a DeviceInfo entry.
     */
    private void collectTechnicianIcons(Element techLayer,
                                        String  roomCode,
                                        String  roomGroupId,
                                        Map<String, DeviceInfo> devices,
                                        List<String> iconIds) {
        NodeList children = techLayer.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (!(child instanceof Element)) continue;
            Element el  = (Element) child;
            if (!"g".equals(normalizeTag(el.getTagName()))) continue;

            String id = el.getAttribute("id");
            if (id == null || id.isEmpty()) continue;

            // The id may be a wrapper group (e.g. MBDR_PSD02_4 that contains
            // further sub-groups).  Recursively scan for leaf icons.
            scanTechnicianGroup(el, roomCode, roomGroupId, devices, iconIds);
        }
    }

    /**
     * Recursively scans a &lt;g&gt; inside Technician_Layer.
     * A leaf icon is a &lt;g&gt; that:
     *   - has an id matching  ROOMCODE_DEVCODE_INSTANCE[_SUBTYPE]
     *   - has at least one direct &lt;rect&gt; child  (the colourable background)
     *   - does NOT have a direct &lt;g&gt; child (it is the leaf, not a container)
     *
     * Wrapper groups (which DO have &lt;g&gt; children) are descended into.
     */
    private void scanTechnicianGroup(Element el,
                                     String  roomCode,
                                     String  roomGroupId,
                                     Map<String, DeviceInfo> devices,
                                     List<String> iconIds) {
        String id = el.getAttribute("id");
        if (id == null || id.isEmpty()) return;

        boolean hasRect     = hasDirectRectChild(el);
        boolean hasGChild   = hasDirectGChild(el);

        if (hasRect && !hasGChild) {
            // This is a leaf icon — parse it
            DeviceInfo info = buildDeviceInfo(id, el, roomCode, roomGroupId);
            if (info != null && !devices.containsKey(id)) {
                devices.put(id, info);
                iconIds.add(id);
                Log.d(TAG, "  Icon: " + info);
            }
            return;
        }

        // Container group — descend
        NodeList children = el.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (!(child instanceof Element)) continue;
            Element childEl = (Element) child;
            if (!"g".equals(normalizeTag(childEl.getTagName()))) continue;
            scanTechnicianGroup(childEl, roomCode, roomGroupId, devices, iconIds);
        }
    }

    /**
     * Build a DeviceInfo by parsing the id string.
     *
     * Pattern:  ROOMCODE_DEVCODE_INSTANCE[_SUBTYPE]
     *   parts[0] = ROOMCODE   e.g. MBDR
     *   parts[1] = DEVCODE    e.g. CLE02
     *   parts[2] = INSTANCE   e.g. 1
     *   parts[3] = SUBTYPE    e.g. AC   (optional)
     *
     * Returns null if the id does not match the expected pattern or
     * if the roomCode does not match.
     */
    private DeviceInfo buildDeviceInfo(String  id,
                                       Element el,
                                       String  roomCode,
                                       String  roomGroupId) {
        String[] parts = id.split("_");
        // Minimum: ROOMCODE _ DEVCODE _ INSTANCE  → 3 parts
        if (parts.length < 3) return null;

        // parts[0] must match the room code
        if (!roomCode.equalsIgnoreCase(parts[0])) return null;

        String deviceCode = parts[1];

        int instance;
        try {
            instance = Integer.parseInt(parts[2]);
        } catch (NumberFormatException e) {
            return null;   // third segment is not a number
        }

        // Optional subtype
        String subType = (parts.length >= 4) ? parts[3] : "";

        RectF bounds = computeBounds(el);
        if (bounds == null || bounds.isEmpty()) return null;

        return new DeviceInfo(id, roomCode, deviceCode, instance,
                subType, el, bounds, roomGroupId);
    }

    // ══════════════════════════════════════════════════════════════════════
    //  USER_LAYER PARSING
    //
    //  For each room group, find <g id="User_Layer"> and collect every
    //  child element whose id ends with "-2"  (convention: tech id + "-2").
    //  Returns map:  technicianIconId → User_Layer Element
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Parse all User_Layer elements across all room groups.
     *
     * @return map  technician-icon-id  →  corresponding User_Layer Element
     */
    public Map<String, Element> parseUserLayer(Document document) {
        Map<String, Element> result = new LinkedHashMap<>();
        if (document == null) return result;
        collectUserLayerRecursive(
                document.getDocumentElement(), result);
        Log.d(TAG, "parseUserLayer: found " + result.size()
                + " user-layer elements");
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
            if ("User_Layer".equals(id)) {
                collectUserLayerElements(el, result);
            } else {
                collectUserLayerRecursive(el, result);
            }
        }
    }
    /**
     * Walk direct children of User_Layer and map each to its
     * technician icon id by stripping the "-2" suffix from the element id.
     *
     * Also handles wrapper groups (e.g. MBDR_PSD02_4-3 that contain
     * sub-elements MBDR_DNU02_1-4 etc.) — these are descended into.
     */
    private void collectUserLayerElements(Element userLayer,
                                          Map<String, Element> result) {
        NodeList children = userLayer.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (!(child instanceof Element)) continue;
            Element el = (Element) child;

            String elId = el.getAttribute("id");
            if (elId == null || elId.isEmpty()) continue;

            // If the element itself has the "-2" suffix, map it directly
            if (elId.endsWith("-2")) {
                String techId = elId.substring(0, elId.length() - 2);
                result.put(techId, el);
                Log.d(TAG, "  UserLayer: " + elId + " → tech=" + techId);
                continue;
            }

            // Might be a wrapper group (e.g. MBDR_PSD02_4-3 wrapping sub items)
            // The wrapper suffix "-3", "-4" etc. means it groups several
            // related tech icons.  Descend to find the actual leaf elements.
            String tag = normalizeTag(el.getTagName());
            if ("g".equals(tag)) {
                collectUserLayerElements(el, result);
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  SELECTION LAYER PARSING  (<g id="selection_layer">)
    //  Unchanged — still used for area dim/zoom logic.
    // ══════════════════════════════════════════════════════════════════════

    public void parseSelectionLayer(Document document) {
        selectionLayerBounds.clear();
        selectionLayerElements.clear();
        if (document == null) return;

        Element selLayer = findElementById(
                document.getDocumentElement(), "selection_layer");
        if (selLayer == null) {
            Log.w(TAG, "No <g id='selection_layer'> found in SVG");
            return;
        }

        NodeList children = selLayer.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (!(child instanceof Element)) continue;
            Element el  = (Element) child;
            String  tag = normalizeTag(el.getTagName());
            String  id  = el.getAttribute("id");
            if (id == null || id.isEmpty()) continue;

            selectionLayerElements.put(id, el);

            RectF bounds = null;
            if ("rect".equals(tag))         bounds = computeRectBounds(el);
            else if ("polygon".equals(tag)) bounds = computePolyBounds(el);

            if (bounds != null && !bounds.isEmpty()) {
                selectionLayerBounds.put(id, bounds);
                Log.d(TAG, "SelectionLayer '" + id + "' → " + bounds);
            }
        }
        Log.d(TAG, "selection_layer: " + selectionLayerBounds.size() + " areas");
    }

    // ══════════════════════════════════════════════════════════════════════
    //  ID PARSING HELPERS
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Extract the room code from a room-group id.
     * The room code is the last underscore-separated segment, e.g.
     *   "Master_Bedroom_MBDR" → "MBDR"
     *   "Living_Room_LR"      → "LR"
     *
     * Returns null if the id has fewer than 2 segments.
     */
    public String extractRoomCode(String roomGroupId) {
        if (roomGroupId == null || roomGroupId.isEmpty()) return null;
        int last = roomGroupId.lastIndexOf('_');
        if (last < 0 || last == roomGroupId.length() - 1) return null;
        String code = roomGroupId.substring(last + 1);
        // Room code should be all uppercase letters (e.g. MBDR, LR, KIT)
        if (!code.matches("[A-Z]+")) return null;
        return code;
    }

    /**
     * Parse a device id string into its parts.
     * Returns String[]{roomCode, deviceCode, instance, subType}
     * or null if parsing fails.
     */
    public String[] parseDeviceId(String id) {
        if (id == null || id.isEmpty()) return null;
        String[] parts = id.split("_");
        if (parts.length < 3) return null;
        try {
            Integer.parseInt(parts[2]);
        } catch (NumberFormatException e) {
            return null;
        }
        String subType = (parts.length >= 4) ? parts[3] : "";
        return new String[]{ parts[0], parts[1], parts[2], subType };
    }

    // ══════════════════════════════════════════════════════════════════════
    //  ELEMENT FINDER HELPERS
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Find a direct (non-recursive) child &lt;g&gt; with the given id.
     */
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

    /** Recursive depth-first search for an element by id. */
    public Element findElementById(Element root, String targetId) {
        if (root == null) return null;
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

    // ══════════════════════════════════════════════════════════════════════
    //  CHILD-ELEMENT CHECKS
    // ══════════════════════════════════════════════════════════════════════

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

    public RectF computeRectBounds(Element el) {
        Float x = fa(el,"x"), y = fa(el,"y");
        Float w = fa(el,"width"), h = fa(el,"height");
        if (w == null || h == null || w <= 0 || h <= 0) return null;
        float xv = x != null ? x : 0f, yv = y != null ? y : 0f;
        return new RectF(xv, yv, xv + w, yv + h);
    }

    public RectF computeCircleBounds(Element el) {
        Float cx = fa(el,"cx"), cy = fa(el,"cy"), r = fa(el,"r");
        if (r == null || r <= 0) return null;
        float cxv = cx != null ? cx : 0f, cyv = cy != null ? cy : 0f;
        return new RectF(cxv - r, cyv - r, cxv + r, cyv + r);
    }

    public RectF computeEllipseBounds(Element el) {
        Float cx = fa(el,"cx"), cy = fa(el,"cy");
        Float rx = fa(el,"rx"), ry = fa(el,"ry");
        if (rx == null || ry == null) return null;
        float cxv = cx != null ? cx : 0f, cyv = cy != null ? cy : 0f;
        return new RectF(cxv - rx, cyv - ry, cxv + rx, cyv + ry);
    }

    public RectF computePathBounds(Element el) {
        return parsePathBounds(el.getAttribute("d"));
    }

    public RectF computePolyBounds(Element el) {
        return parsePointsBounds(el.getAttribute("points"));
    }

    public RectF computeLineBounds(Element el) {
        Float x1 = fa(el,"x1"), y1 = fa(el,"y1");
        Float x2 = fa(el,"x2"), y2 = fa(el,"y2");
        float x1v = x1!=null?x1:0f, y1v = y1!=null?y1:0f;
        float x2v = x2!=null?x2:0f, y2v = y2!=null?y2:0f;
        return new RectF(Math.min(x1v,x2v), Math.min(y1v,y2v),
                Math.max(x1v,x2v), Math.max(y1v,y2v));
    }

    public RectF computeUseBounds(Element el) {
        Float x = fa(el,"x"), y = fa(el,"y");
        Float w = fa(el,"width"), h = fa(el,"height");
        if (w == null || h == null) return null;
        float xv = x!=null?x:0f, yv = y!=null?y:0f;
        return new RectF(xv, yv, xv+w, yv+h);
    }

    private Float fa(Element el, String attr) {
        String v = el.getAttribute(attr);
        if (v == null || v.isEmpty()) return null;
        try { return Float.parseFloat(v.trim()); }
        catch (NumberFormatException e) { return null; }
    }

    // ── Path bounds ───────────────────────────────────────────────────────

    private RectF parsePathBounds(String d) {
        if (d == null || d.isEmpty()) return null;
        List<Float> xs = new ArrayList<>(), ys = new ArrayList<>();
        String cleaned = d.replaceAll("([MmLlHhVvCcSsQqTtAaZz])"," $1 ")
                .replaceAll("([0-9])-","$1 -").trim();
        String[] tokens = cleaned.split("[\\s,]+");
        char cmd = 'M';
        float curX=0, curY=0, startX=0, startY=0;
        List<Float> args = new ArrayList<>();
        for (String token : tokens) {
            if (token.isEmpty()) continue;
            if (Character.isLetter(token.charAt(0))) {
                processPathCommand(cmd,args,xs,ys,
                        new float[]{curX},new float[]{curY},
                        new float[]{startX},new float[]{startY});
                if (!xs.isEmpty()) curX = xs.get(xs.size()-1);
                if (!ys.isEmpty()) curY = ys.get(ys.size()-1);
                cmd = token.charAt(0);
                args.clear();
            } else {
                try { args.add(Float.parseFloat(token)); }
                catch (NumberFormatException ignored) {}
            }
        }
        processPathCommand(cmd,args,xs,ys,
                new float[]{curX},new float[]{curY},
                new float[]{startX},new float[]{startY});
        if (xs.isEmpty()||ys.isEmpty()) return null;
        float minX=Float.MAX_VALUE,maxX=-Float.MAX_VALUE;
        float minY=Float.MAX_VALUE,maxY=-Float.MAX_VALUE;
        for (float x:xs){if(x<minX)minX=x;if(x>maxX)maxX=x;}
        for (float y:ys){if(y<minY)minY=y;if(y>maxY)maxY=y;}
        return minX==Float.MAX_VALUE?null:new RectF(minX,minY,maxX,maxY);
    }

    private void processPathCommand(char cmd, List<Float> args,
                                    List<Float> xs, List<Float> ys,
                                    float[] cx, float[] cy,
                                    float[] sx, float[] sy) {
        if (args.isEmpty()) return;
        switch (cmd) {
            case 'M':
                for(int i=0;i+1<args.size();i+=2){
                    float x=args.get(i),y=args.get(i+1);
                    xs.add(x);ys.add(y);cx[0]=x;cy[0]=y;
                    if(i==0){sx[0]=x;sy[0]=y;}
                } break;
            case 'm':
                for(int i=0;i+1<args.size();i+=2){
                    cx[0]+=args.get(i);cy[0]+=args.get(i+1);
                    xs.add(cx[0]);ys.add(cy[0]);
                    if(i==0){sx[0]=cx[0];sy[0]=cy[0];}
                } break;
            case 'L':
                for(int i=0;i+1<args.size();i+=2){
                    float x=args.get(i),y=args.get(i+1);
                    xs.add(x);ys.add(y);cx[0]=x;cy[0]=y;
                } break;
            case 'l':
                for(int i=0;i+1<args.size();i+=2){
                    cx[0]+=args.get(i);cy[0]+=args.get(i+1);
                    xs.add(cx[0]);ys.add(cy[0]);
                } break;
            case 'H':
                for(float v:args){xs.add(v);ys.add(cy[0]);cx[0]=v;} break;
            case 'h':
                for(float v:args){cx[0]+=v;xs.add(cx[0]);ys.add(cy[0]);} break;
            case 'V':
                for(float v:args){xs.add(cx[0]);ys.add(v);cy[0]=v;} break;
            case 'v':
                for(float v:args){cy[0]+=v;xs.add(cx[0]);ys.add(cy[0]);} break;
            case 'C':
                for(int i=0;i+5<args.size();i+=6){
                    xs.add(args.get(i));ys.add(args.get(i+1));
                    xs.add(args.get(i+2));ys.add(args.get(i+3));
                    xs.add(args.get(i+4));ys.add(args.get(i+5));
                    cx[0]=args.get(i+4);cy[0]=args.get(i+5);
                } break;
            case 'c':
                for(int i=0;i+5<args.size();i+=6){
                    xs.add(cx[0]+args.get(i));ys.add(cy[0]+args.get(i+1));
                    xs.add(cx[0]+args.get(i+2));ys.add(cy[0]+args.get(i+3));
                    cx[0]+=args.get(i+4);cy[0]+=args.get(i+5);
                    xs.add(cx[0]);ys.add(cy[0]);
                } break;
            case 'A':
                for(int i=0;i+6<args.size();i+=7){
                    float x=args.get(i+5),y=args.get(i+6);
                    xs.add(x);ys.add(y);cx[0]=x;cy[0]=y;
                } break;
            case 'a':
                for(int i=0;i+6<args.size();i+=7){
                    cx[0]+=args.get(i+5);cy[0]+=args.get(i+6);
                    xs.add(cx[0]);ys.add(cy[0]);
                } break;
            case 'Z': case 'z':
                xs.add(sx[0]);ys.add(sy[0]);cx[0]=sx[0];cy[0]=sy[0]; break;
        }
    }

    private RectF parsePointsBounds(String points) {
        if (points == null || points.isEmpty()) return null;
        String[] tokens = points.trim().split("[\\s,]+");
        List<Float> xs = new ArrayList<>(), ys = new ArrayList<>();
        for (int i=0;i+1<tokens.length;i+=2) {
            try {
                xs.add(Float.parseFloat(tokens[i]));
                ys.add(Float.parseFloat(tokens[i+1]));
            } catch (NumberFormatException ignored) {}
        }
        if (xs.isEmpty()) return null;
        float minX=Float.MAX_VALUE,maxX=-Float.MAX_VALUE;
        float minY=Float.MAX_VALUE,maxY=-Float.MAX_VALUE;
        for(float x:xs){if(x<minX)minX=x;if(x>maxX)maxX=x;}
        for(float y:ys){if(y<minY)minY=y;if(y>maxY)maxY=y;}
        return new RectF(minX,minY,maxX,maxY);
    }

    // ══════════════════════════════════════════════════════════════════════
    //  UTILITIES
    // ══════════════════════════════════════════════════════════════════════

    public String normalizeTag(String tag) {
        if (tag == null) return "";
        String t = tag.toLowerCase();
        int colon = t.indexOf(':');
        return colon >= 0 ? t.substring(colon + 1) : t;
    }

    public String normalize(String s) {
        if (s == null) return "";
        return s.toLowerCase().replace(" ","_").replace("-","_");
    }

    public boolean isFuzzyMatch(String normId, String normFocus) {
        if (normId.equals(normFocus))   return true;
        if (normFocus.contains(normId)) return true;
        if (normId.contains(normFocus)) return true;
        String[] idWords    = normId.split("_");
        String[] focusWords = normFocus.split("_");
        for (String iw : idWords) {
            if (iw.length() <= 2) continue;
            for (String fw : focusWords) {
                if (fw.length() <= 2) continue;
                if (iw.equals(fw)) return true;
            }
        }
        return false;
    }
}