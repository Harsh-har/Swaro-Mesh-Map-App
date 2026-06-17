package no.nordicsemi.android.swaromapmesh.swajaui.Svg_Operations;

import android.graphics.Matrix;
import android.graphics.RectF;
import android.util.Log;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

public class SvgParsers {

    private static final String TAG = "SvgParsers";

    public float vbX = 0f, vbY = 0f, vbW = 1200f, vbH = 640f;
    public final Map<String, List<String>> areaMap = new LinkedHashMap<>();
    public final Map<String, Element> selectionLayerElements = new HashMap<>();
    public final Map<String, RectF> selectionLayerBounds = new HashMap<>();

    public Document parseDocument(InputStream is) {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(false);
            DocumentBuilder db = dbf.newDocumentBuilder();
            return db.parse(is);
        } catch (Exception e) {
            Log.e(TAG, "Error parsing SVG document", e);
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
                } catch (NumberFormatException e) {
                    Log.e(TAG, "Invalid viewBox: " + vb, e);
                }
            }
        } else {
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
    }

    public Map<String, DeviceInfo> extractDevices(Document document) {
        Map<String, DeviceInfo> devices = new LinkedHashMap<>();
        areaMap.clear();
        if (document == null) return devices;
        try {
            Element iconsGroup = findElementById(document.getDocumentElement(), "Icons");
            if (iconsGroup == null) {
                scanForLeafIcons(document.getDocumentElement(), devices, null, new Matrix());
                return devices;
            }
            Matrix iconsMatrix = getCumulativeTransform(iconsGroup);
            NodeList areaNodes = iconsGroup.getChildNodes();
            for (int i = 0; i < areaNodes.getLength(); i++) {
                Node aNode = areaNodes.item(i);
                if (!(aNode instanceof Element)) continue;
                Element aEl  = (Element) aNode;
                String  aTag = normalizeTag(aEl.getTagName());
                if (!"g".equals(aTag)) continue;
                String areaId = aEl.getAttribute("id");
                if (areaId == null || areaId.isEmpty()) continue;

                int before = devices.size();
                scanForLeafIcons(aEl, devices, areaId, iconsMatrix);

                List<String> iconIds = new ArrayList<>();
                for (Map.Entry<String, DeviceInfo> e : devices.entrySet())
                    if (areaId.equals(e.getValue().areaId)) iconIds.add(e.getKey());
                areaMap.put(areaId, iconIds);
                Log.d(TAG, "Area '" + areaId + "' → " + (devices.size() - before) + " icons");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error extracting devices", e);
        }
        return devices;
    }

    private void scanForLeafIcons(Element el, Map<String, DeviceInfo> devices, String areaId, Matrix parentMatrix) {
        String id = el.getAttribute("id");
        String transformAttr = el.getAttribute("transform");
        Matrix currentMatrix = parentMatrix;
        if (transformAttr != null && !transformAttr.isEmpty()) {
            currentMatrix = new Matrix(parentMatrix);
            currentMatrix.postConcat(parseTransform(transformAttr));
        }

        if (!id.isEmpty() && hasDirectRectChild(el) && !hasDirectGChild(el)) {
            processDeviceElement(el, devices, areaId, parentMatrix);
            return;
        }
        NodeList children = el.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element) {
                String tag = normalizeTag(((Element) child).getTagName());
                if ("g".equals(tag))
                    scanForLeafIcons((Element) child, devices, areaId, currentMatrix);
            }
        }
    }

    private void processDeviceElement(Element el, Map<String, DeviceInfo> devices,
                                      String areaId, Matrix parentMatrix) {
        String id = el.getAttribute("id");
        if (id == null || id.isEmpty() || devices.containsKey(id)) return;

        // ── FIX: Only use the RECT child bounds + parent transform ──
        // computeBounds(el, parentMatrix) recurses into all children (paths, strips etc.)
        // which gives huge wrong bounds. Instead find the direct <rect> and use only that.
        RectF bounds = null;
        NodeList children = el.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (!(child instanceof Element)) continue;
            Element childEl = (Element) child;
            if ("rect".equals(normalizeTag(childEl.getTagName()))) {
                // Apply element's own transform on top of parent
                String transformAttr = el.getAttribute("transform");
                Matrix localMatrix = new Matrix(parentMatrix);
                if (transformAttr != null && !transformAttr.isEmpty()) {
                    localMatrix.postConcat(parseTransform(transformAttr));
                }
                bounds = computeRectBounds(childEl);
                if (bounds != null && !bounds.isEmpty()) {
                    localMatrix.mapRect(bounds);
                }
                break; // sirf pehla rect kafi hai
            }
        }

        // Fallback: agar rect nahi mila toh purana method
        if (bounds == null || bounds.isEmpty()) {
            bounds = computeBounds(el, parentMatrix);
        }

        if (bounds == null || bounds.isEmpty()) return;

        String elementId = extractElementId(el);
        if (elementId == null) elementId = id;
        String receiveId = extractReceiveId(el);

        Log.d(TAG, "processDevice: id=" + id + " elementId=" + elementId + " bounds=" + bounds);

        DeviceInfo info = new DeviceInfo(id, el, bounds, elementId, areaId);
        info.receiveId = receiveId;
        devices.put(id, info);
    }
    private boolean hasDirectRectChild(Element el) {
        NodeList children = el.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node c = children.item(i);
            if (c instanceof Element && "rect".equals(normalizeTag(((Element) c).getTagName()))) return true;
        }
        return false;
    }

    private boolean hasDirectGChild(Element el) {
        NodeList children = el.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node c = children.item(i);
            if (c instanceof Element && "g".equals(normalizeTag(((Element) c).getTagName()))) return true;
        }
        return false;
    }

    public Map<String, Set<String>> parseRelations(Document document) {
        Map<String, Set<String>> relations = new HashMap<>();
        Element root = document.getDocumentElement();
        Element relationGroup = findElementById(root, "Relation");
        if (relationGroup == null) return relations;

        String text = relationGroup.getTextContent();
        if (text == null) return relations;

        Pattern p = Pattern.compile("\\(([^|]+)\\|([^)]+)\\)");
        Matcher m = p.matcher(text);
        while (m.find()) {
            String iconId = m.group(1).trim();
            String deviceList = m.group(2).trim();
            Set<String> devices = new HashSet<>();
            for (String d : deviceList.split(",")) {
                String trimmed = d.trim();
                if (!trimmed.isEmpty()) devices.add(trimmed);
            }
            relations.put(iconId, devices);
        }
        return relations;
    }

    public void parseSelectionLayer(Document document) {
        selectionLayerBounds.clear();
        selectionLayerElements.clear();
        if (document == null) return;

        Element selLayer = findElementById(document.getDocumentElement(), "selection_layer");
        if (selLayer == null) selLayer = findElementFuzzy(document.getDocumentElement(), "selection");
        if (selLayer == null) return;

        Matrix parentMatrix = getCumulativeTransform((Element) selLayer);

        NodeList children = selLayer.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (!(child instanceof Element)) continue;
            Element el = (Element) child;
            String id = el.getAttribute("id");
            if (id == null || id.isEmpty()) continue;

            selectionLayerElements.put(id, el);
            RectF bounds = computeBounds(el, parentMatrix);
            if (bounds != null && !bounds.isEmpty()) {
                selectionLayerBounds.put(id, bounds);
            }
        }
        remapSelectionBoundsToAreaIds();
    }

    private void remapSelectionBoundsToAreaIds() {
        Map<String, RectF> remapped = new HashMap<>();
        for (String selId : selectionLayerBounds.keySet()) {
            for (String areaId : areaMap.keySet()) {
                if (isFuzzyMatch(normalize(selId), normalize(areaId))) {
                    remapped.put(areaId, selectionLayerBounds.get(selId));
                    break;
                }
            }
        }
        selectionLayerBounds.putAll(remapped);
    }

    public Element findElementFuzzy(Element root, String target) {
        String normTarget = normalize(target);
        NodeList allGroups = root.getElementsByTagName("g");
        for (int i = 0; i < allGroups.getLength(); i++) {
            Element el = (Element) allGroups.item(i);
            if (isFuzzyMatch(normalize(el.getAttribute("id")), normTarget)) return el;
        }
        return null;
    }

    public String extractElementId(Element element) {
        return findElementIdInNode(element);
    }

    private String findElementIdInNode(Node node) {
        if (node instanceof Element) {
            Element el = (Element) node;
            String eid = el.getAttribute("elementId");
            if (!eid.isEmpty()) return eid;

            // Search in all descendants for <elementId>
            NodeList all = el.getElementsByTagName("*");
            for (int i = 0; i < all.getLength(); i++) {
                Element sub = (Element) all.item(i);
                if ("elementid".equals(normalizeTag(sub.getTagName()))) {
                    String content = sub.getTextContent().trim();
                    if (!content.isEmpty()) return content;
                }
            }
        }
        return null;
    }

    public String extractReceiveId(Element element) {
        return findReceiveIdInNode(element);
    }

    private String findReceiveIdInNode(Node node) {
        if (node instanceof Element) {
            Element el = (Element) node;
            String rid = el.getAttribute("reciveId");
            if (rid.isEmpty()) rid = el.getAttribute("receiveId");
            if (!rid.isEmpty()) return rid;

            // Search in all descendants for <reciveId> or <receiveId>
            NodeList all = el.getElementsByTagName("*");
            for (int i = 0; i < all.getLength(); i++) {
                Element sub = (Element) all.item(i);
                String tag = normalizeTag(sub.getTagName());
                if ("reciveid".equals(tag) || "receiveid".equals(tag)) {
                    String content = sub.getTextContent().trim();
                    if (!content.isEmpty()) return content;
                }
            }
        }
        return null;
    }

    public Element findElementById(Element root, String id) {
        if (id.equals(root.getAttribute("id"))) return root;
        NodeList children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element) {
                Element found = findElementById((Element) child, id);
                if (found != null) return found;
            }
        }
        return null;
    }

    public RectF computeBounds(Element element) {
        if (element == null) return null;
        Node parent = element.getParentNode();
        Matrix parentMatrix = (parent instanceof Element) ? getCumulativeTransform((Element) parent) : new Matrix();
        return computeBounds(element, parentMatrix);
    }

    public RectF computeBounds(Element element, Matrix parentMatrix) {
        String transform = element.getAttribute("transform");
        Matrix localMatrix = parentMatrix;
        if (transform != null && !transform.isEmpty()) {
            localMatrix = new Matrix(parentMatrix);
            localMatrix.postConcat(parseTransform(transform));
        }

        String tag = normalizeTag(element.getTagName());
        if ("g".equals(tag)) {
            return computeGroupBounds(element, localMatrix);
        }

        RectF bounds = null;
        switch (tag) {
            case "rect":     bounds = computeRectBounds(element); break;
            case "circle":   bounds = computeCircleBounds(element); break;
            case "ellipse":  bounds = computeEllipseBounds(element); break;
            case "path":     bounds = computePathBounds(element); break;
            case "polygon":
            case "polyline": bounds = computePolyBounds(element); break;
            case "line":     bounds = computeLineBounds(element); break;
            case "use":      bounds = computeUseBounds(element); break;
        }

        if (bounds != null && !bounds.isEmpty()) {
            localMatrix.mapRect(bounds);
        }
        return bounds;
    }

    public RectF computeGroupBounds(Element element, Matrix currentMatrix) {
        RectF union = null;
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element) {
                RectF b = computeBounds((Element) child, currentMatrix);
                if (b != null && !b.isEmpty()) {
                    if (union == null) union = new RectF(b);
                    else union.union(b);
                }
            }
        }
        return union;
    }

    public RectF computeRectBounds(Element element) {
        float x = fa(element, "x");
        float y = fa(element, "y");
        float w = fa(element, "width");
        float h = fa(element, "height");
        return new RectF(x, y, x + w, y + h);
    }

    public RectF computeCircleBounds(Element element) {
        float cx = fa(element, "cx");
        float cy = fa(element, "cy");
        float r  = fa(element, "r");
        return new RectF(cx - r, cy - r, cx + r, cy + r);
    }

    public RectF computeEllipseBounds(Element element) {
        float cx = fa(element, "cx");
        float cy = fa(element, "cy");
        float rx = fa(element, "rx");
        float ry = fa(element, "ry");
        return new RectF(cx - rx, cy - ry, cx + rx, cy + ry);
    }

    public RectF computePathBounds(Element element) {
        String d = element.getAttribute("d");
        return parsePathBounds(d);
    }

    public RectF computePolyBounds(Element element) {
        String pts = element.getAttribute("points");
        return parsePointsBounds(pts);
    }

    public RectF computeLineBounds(Element element) {
        float x1 = fa(element, "x1");
        float y1 = fa(element, "y1");
        float x2 = fa(element, "x2");
        float y2 = fa(element, "y2");
        return new RectF(Math.min(x1, x2), Math.min(y1, y2), Math.max(x1, x2), Math.max(y1, y2));
    }

    public RectF computeUseBounds(Element element) {
        float x = fa(element, "x");
        float y = fa(element, "y");
        // For simplicity, treat 'use' as a point if we don't resolve the ref
        return new RectF(x, y, x + 1, y + 1);
    }

    private Float fa(Element el, String attr) {
        String val = el.getAttribute(attr);
        if (val.isEmpty()) return 0f;
        try { return Float.parseFloat(val.replaceAll("[^0-9.-]", "")); }
        catch (NumberFormatException e) { return 0f; }
    }

    public RectF parsePathBounds(String d) {
        if (d == null || d.isEmpty()) return null;
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        // ── FIX: was [a-df-z] (lowercase only) — uppercase commands like M/Z/L/C/H/V
        // were never recognized as token boundaries, corrupting bounds for any path
        // that uses absolute commands (very common, e.g. "M...Z" subpaths).
        String[] tokens = d.split("(?=[a-zA-Z])|(?<=[a-zA-Z])|[,\\s]+");
        float curX = 0, curY = 0;
        float startX = 0, startY = 0;
        for (int i = 0; i < tokens.length; i++) {
            String t = tokens[i].trim();
            if (t.isEmpty()) continue;
            char cmd = t.charAt(0);
            if (Character.isLetter(cmd)) {
                List<Float> params = new ArrayList<>();
                int j = i + 1;
                while (j < tokens.length) {
                    String next = tokens[j].trim();
                    if (next.isEmpty()) { j++; continue; }
                    if (Character.isLetter(next.charAt(0))) break;
                    try { params.add(Float.parseFloat(next)); } catch (Exception ignored) {}
                    j++;
                }
                i = j - 1;

                char lower = Character.toLowerCase(cmd);
                boolean relative = Character.isLowerCase(cmd);

                if (lower == 'z') {
                    curX = startX; curY = startY;
                    continue;
                }
                // Simple point extraction (pairs), good enough for bounding box purposes
                for (int k = 0; k < params.size() - 1; k += 2) {
                    float px = params.get(k), py = params.get(k + 1);
                    if (relative) { px += curX; py += curY; }
                    minX = Math.min(minX, px); minY = Math.min(minY, py);
                    maxX = Math.max(maxX, px); maxY = Math.max(maxY, py);
                    curX = px; curY = py;
                }
                if (lower == 'm') { startX = curX; startY = curY; }
            }
        }
        return (minX == Float.MAX_VALUE) ? null : new RectF(minX, minY, maxX, maxY);
    }
    public RectF parsePointsBounds(String pts) {
        if (pts == null || pts.isEmpty()) return null;
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        String[] coords = pts.trim().split("[\\s,]+");
        for (int i = 0; i < coords.length - 1; i += 2) {
            try {
                float x = Float.parseFloat(coords[i]);
                float y = Float.parseFloat(coords[i + 1]);
                minX = Math.min(minX, x); minY = Math.min(minY, y);
                maxX = Math.max(maxX, x); maxY = Math.max(maxY, y);
            } catch (Exception ignored) {}
        }
        return (minX == Float.MAX_VALUE) ? null : new RectF(minX, minY, maxX, maxY);
    }

    private Matrix getCumulativeTransform(Element element) {
        Matrix m = new Matrix();
        if (element == null) return m;
        Node parent = element.getParentNode();
        if (parent instanceof Element) {
            m = getCumulativeTransform((Element) parent);
        }
        String transform = element.getAttribute("transform");
        if (transform != null && !transform.isEmpty()) {
            m.postConcat(parseTransform(transform));
        }
        return m;
    }

    private Matrix parseTransform(String transform) {
        Matrix matrix = new Matrix();
        if (transform == null || transform.isEmpty()) return matrix;
        Pattern p = Pattern.compile("(\\w+)\\s*\\(([^)]+)\\)");
        Matcher m = p.matcher(transform);
        while (m.find()) {
            String cmdGroup = m.group(1);
            if (cmdGroup == null) continue;
            String cmd = cmdGroup.toLowerCase();
            String argsStr = m.group(2);
            String[] args = argsStr.trim().split("[\\s,]+");
            float[] params = new float[args.length];
            for (int i = 0; i < args.length; i++) {
                try { params[i] = Float.parseFloat(args[i]); } catch (Exception ignored) {}
            }
            switch (cmd) {
                case "translate":
                    if (params.length >= 2) matrix.postTranslate(params[0], params[1]);
                    else if (params.length == 1) matrix.postTranslate(params[0], 0);
                    break;
                case "scale":
                    if (params.length >= 2) matrix.postScale(params[0], params[1]);
                    else if (params.length == 1) matrix.postScale(params[0], params[0]);
                    break;
                case "rotate":
                    if (params.length >= 3) matrix.postRotate(params[0], params[1], params[2]);
                    else if (params.length >= 1) matrix.postRotate(params[0]);
                    break;
                case "matrix":
                    if (params.length == 6) {
                        Matrix m6 = new Matrix();
                        m6.setValues(new float[]{params[0], params[2], params[4], params[1], params[3], params[5], 0, 0, 1});
                        matrix.postConcat(m6);
                    }
                    break;
            }
        }
        return matrix;
    }

    public String normalizeTag(String tag) {
        if (tag == null) return "";
        String t = tag.toLowerCase();
        int colon = t.indexOf(':');
        return colon >= 0 ? t.substring(colon + 1) : t;
    }

    public String normalize(String s) {
        if (s == null) return "";
        return s.toLowerCase().replace(" ", "_").replace("-", "_");
    }

    public boolean isFuzzyMatch(String normId, String normFocus) {
        if (normId.equals(normFocus)) return true;
        if (normFocus.contains(normId)) return true;
        if (normId.contains(normFocus)) return true;
        String[] idWords = normId.split("_"), focusWords = normFocus.split("_");
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
