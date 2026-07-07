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
                    Log.d(TAG, "Parsed viewBox: " + vbX + " " + vbY + " " + vbW + " " + vbH);
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
            Element iconsGroup = findElementById(document.getDocumentElement(), "Technician Layer");
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

        if (!id.isEmpty() && !hasDirectGChild(el)) {
            // Include leaf groups with IDs (e.g., 'st' devices) even if they lack a direct rect child
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

        // ── Optimized Rect Finder ────────────────────────────────────────
        // Find all rects and pick the smallest one (likely the hit-box)
        RectF bounds = null;
        float smallestRectArea = Float.MAX_VALUE;
        NodeList children = el.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (!(child instanceof Element)) continue;
            Element childEl = (Element) child;
            if ("rect".equals(normalizeTag(childEl.getTagName()))) {
                RectF r = computeRectBounds(childEl);
                if (r != null && !r.isEmpty()) {
                    float a = r.width() * r.height();
                    if (a < smallestRectArea) {
                        smallestRectArea = a;
                        bounds = r;

                        // Apply element's own transform
                        String transformAttr = el.getAttribute("transform");
                        Matrix localMatrix = new Matrix(parentMatrix);
                        if (transformAttr != null && !transformAttr.isEmpty()) {
                            localMatrix.postConcat(parseTransform(transformAttr));
                        }
                        localMatrix.mapRect(bounds);
                    }
                }
            }
        }

        if (bounds == null || bounds.isEmpty()) {
            bounds = computeBounds(el, parentMatrix);
        }

        if (bounds == null || bounds.isEmpty()) {
            Log.v(TAG, "processDeviceElement: Skipping " + id + " - no bounds found");
            return;
        }

        // ── Parsing ID for ElementId, ReceiveId, DeviceName, and Count ─────
        // New structure: RoomName_DeviceName_Count_ElementId_ReceiveId
        // Example: GBDR_Strip Node_1_13_13
        String elementId = null;
        String receiveId = null;
        String parsedAreaId = areaId;
        String deviceName = null;
        String deviceCount = null;

        try {
            String[] parts = id.split("_");
            if (parts.length >= 5) {
                // Prioritize parsing from ID string
                // Normal Device: Area_Category_Count_EID_RID (5 parts)
                // LC Light: Area_Category_Count_Index_EID_RID (6 parts)
                // In both cases, RID is the last part, EID is the second to last.
                receiveId = parts[parts.length - 1];
                elementId = parts[parts.length - 2];
                
                // Working backwards to find the Category and Count
                int catIdx = -1;
                for (int i = parts.length - 3; i >= 0; i--) {
                    if (!parts[i].matches("\\d+")) {
                        catIdx = i;
                        break;
                    }
                }
                
                if (catIdx != -1) {
                    deviceName = parts[catIdx];
                    deviceCount = parts[catIdx + 1];
                    
                    StringBuilder areaBuilder = new StringBuilder();
                    for (int i = 0; i < catIdx; i++) {
                        if (i > 0) areaBuilder.append("_");
                        areaBuilder.append(parts[i]);
                    }
                    parsedAreaId = areaBuilder.toString();
                }
            } else if (parts.length >= 2) {
                // Handle semi-structured IDs like Area_Code or Area_Code_Count
                // Try to see if the last or middle part is a known count
                String lastPart = parts[parts.length - 1];
                if (lastPart.matches("\\d+")) {
                    deviceCount = lastPart;
                    if (parts.length >= 3) {
                        deviceName = parts[parts.length - 2];
                        StringBuilder areaBuilder = new StringBuilder();
                        for (int i = 0; i < parts.length - 2; i++) {
                            if (i > 0) areaBuilder.append("_");
                            areaBuilder.append(parts[i]);
                        }
                        parsedAreaId = areaBuilder.toString();
                    }
                } else {
                    // Area_Code
                    deviceName = lastPart;
                    StringBuilder areaBuilder = new StringBuilder();
                    for (int i = 0; i < parts.length - 1; i++) {
                        if (i > 0) areaBuilder.append("_");
                        areaBuilder.append(parts[i]);
                    }
                    parsedAreaId = areaBuilder.toString();
                }
            } else if (id.contains(":")) {
                // Handle manual devices or legacy format with ":"
                // manual_Name_Timestamp:Code or Area:Code
                String[] colonParts = id.split(":");
                if (colonParts.length > 0) {
                    deviceName = colonParts[colonParts.length - 1];
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Error parsing ID pattern for: " + id, e);
        }

        // Fallback to metadata if ID parsing didn't find values
        if (elementId == null) {
            elementId = extractElementId(el);
        }
        if (elementId == null) {
            elementId = id;
        }

        if (receiveId == null) {
            receiveId = extractReceiveId(el);
        }

        Log.d(TAG, "Parsed Device: id=" + id + " name=" + deviceName + " elementId=" + elementId + " receiveId=" + receiveId + " area=" + parsedAreaId + " count=" + deviceCount);

        DeviceInfo info = new DeviceInfo(id, el, bounds, elementId, parsedAreaId);
        info.receiveId = receiveId;
        info.deviceName = deviceName;
        info.deviceCount = deviceCount;
        devices.put(id, info);
    }
    private boolean hasDirectGChild(Element el) {
        NodeList children = el.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node c = children.item(i);
            if (c instanceof Element && "g".equals(normalizeTag(((Element) c).getTagName()))) return true;
        }
        return false;
    }

    public Map<String, Set<String>> parseRelations(Document document, Map<String, DeviceInfo> iconMap) {
        Map<String, Set<String>> relations = new HashMap<>();
        if (document == null || iconMap == null) return relations;

        Element root = document.getDocumentElement();
        Element devicesGroup = findElementById(root, "User Layer");
        if (devicesGroup == null) devicesGroup = findElementFuzzy(root, "User Layer");
        if (devicesGroup == null) devicesGroup = findElementById(root, "Devices");
        
        if (devicesGroup == null) return relations;

        // 1. Collect all valid IDs from the Devices/User layer
        List<String> physicalDeviceIds = new ArrayList<>();
        collectAllIds(devicesGroup, physicalDeviceIds);

        // 2. Build the mapping
        for (String iconId : iconMap.keySet()) {
            Set<String> relatedDevices = new HashSet<>();
            String iconKey = extractRelationKey(iconId);
            
            if (iconKey == null) continue;

            for (String devId : physicalDeviceIds) {
                String devKey = extractRelationKey(devId);
                if (iconKey.equalsIgnoreCase(devKey)) {
                    relatedDevices.add(devId);
                }
            }
            relations.put(iconId, relatedDevices);
            Log.d(TAG, "Relation: Icon '" + iconId + "' -> " + relatedDevices.size() + " devices");
        }
        return relations;
    }

    private void collectAllIds(Element element, List<String> idList) {
        String id = element.getAttribute("id");
        if (id != null && !id.isEmpty()) {
            idList.add(id);
        }
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element) {
                collectAllIds((Element) child, idList);
            }
        }
    }

    /**
     * Extracts a matching key from ID structure to relate Technician Icons to User Layer Devices.
     * Uses Area, Category, and Instance Count to create a unique binding key.
     */
    public String extractRelationKey(String fullId) {
        if (fullId == null || fullId.isEmpty()) return null;
        
        String id = fullId;
        if (id.endsWith("_phys")) {
            id = id.substring(0, id.length() - 5);
        }
        
        String[] parts = id.split("_");
        if (parts.length < 2) return id.toLowerCase().trim();

        // Find the Category code (the last non-numeric part before the ID block)
        int catIdx = -1;
        for (int i = parts.length - 1; i >= 0; i--) {
            if (!parts[i].matches("\\d+")) {
                catIdx = i;
                break;
            }
        }

        if (catIdx == -1) return id.toLowerCase().trim();

        try {
            String category = parts[catIdx];
            
            // The part immediately following category is the unique Instance Count/Sequence
            String count = "";
            if (catIdx + 1 < parts.length && parts[catIdx + 1].matches("\\d+")) {
                count = parts[catIdx + 1];
            }
            
            // Everything before the category is the Area/Room identifier
            StringBuilder areaBuilder = new StringBuilder();
            for (int i = 0; i < catIdx; i++) {
                if (i > 0) areaBuilder.append("_");
                areaBuilder.append(parts[i]);
            }
            String area = areaBuilder.toString();
            
            String key = (area + ":" + category + ":" + count).toLowerCase().trim();
            Log.v(TAG, "extractRelationKey: " + fullId + " -> " + key);
            return key;
        } catch (Exception e) {
            return id.toLowerCase().trim();
        }
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
                Element childEl = (Element) child;

                // Skip large transparent background rects that bloat device bounds
                if ("rect".equals(normalizeTag(childEl.getTagName()))) {
                    float w = fa(childEl, "width");
                    float h = fa(childEl, "height");
                    String fill = childEl.getAttribute("fill");
                    if ((w > 100 || h > 100) && ("none".equals(fill) || "transparent".equals(fill))) {
                        continue;
                    }
                }

                RectF b = computeBounds(childEl, currentMatrix);
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

        // ── Better Tokenization ──────────────────────────────────────────
        // This handles commands (M, L, etc.) and numbers like -10.5-20.3 (no separator)
        // or scientific notation 1.2e-3.
        List<String> tokens = new ArrayList<>();
        Matcher tm = Pattern.compile("[a-zA-Z]|[-+]?(?:\\d*\\.\\d+|\\d+\\.?)(?:[eE][-+]?\\d+)?").matcher(d);
        while (tm.find()) tokens.add(tm.group());

        float curX = 0, curY = 0;
        float startX = 0, startY = 0;

        for (int i = 0; i < tokens.size(); i++) {
            String t = tokens.get(i);
            char cmd = t.charAt(0);
            if (Character.isLetter(cmd)) {
                List<Float> params = new ArrayList<>();
                int j = i + 1;
                while (j < tokens.size()) {
                    String next = tokens.get(j);
                    if (Character.isLetter(next.charAt(0))) break;
                    try { params.add(Float.parseFloat(next)); } catch (Exception ignored) {}
                    j++;
                }
                i = j - 1;

                char lower = Character.toLowerCase(cmd);
                boolean relative = Character.isLowerCase(cmd);

                if (lower == 'z') {
                    curX = startX; curY = startY;
                } else if (lower == 'h') {
                    for (float p : params) {
                        curX = relative ? curX + p : p;
                        minX = Math.min(minX, curX); minY = Math.min(minY, curY);
                        maxX = Math.max(maxX, curX); maxY = Math.max(maxY, curY);
                    }
                } else if (lower == 'v') {
                    for (float p : params) {
                        curY = relative ? curY + p : p;
                        minX = Math.min(minX, curX); minY = Math.min(minY, curY);
                        maxX = Math.max(maxX, curX); maxY = Math.max(maxY, curY);
                    }
                } else if (lower == 'm' || lower == 'l' || lower == 't') {
                    for (int k = 0; k < params.size() - 1; k += 2) {
                        float px = params.get(k), py = params.get(k + 1);
                        if (relative) { px += curX; py += curY; }
                        minX = Math.min(minX, px); minY = Math.min(minY, py);
                        maxX = Math.max(maxX, px); maxY = Math.max(maxY, py);
                        curX = px; curY = py;
                        if (k == 0 && lower == 'm') { startX = curX; startY = curY; }
                    }
                } else if (lower == 'c') {
                    for (int k = 0; k < params.size() - 5; k += 6) {
                        for (int pIdx = 0; pIdx < 6; pIdx += 2) {
                            float px = params.get(k+pIdx), py = params.get(k+pIdx+1);
                            if (relative) { px += curX; py += curY; }
                            minX = Math.min(minX, px); minY = Math.min(minY, py);
                            maxX = Math.max(maxX, px); maxY = Math.max(maxY, py);
                            if (pIdx == 4) { curX = px; curY = py; }
                        }
                    }
                } else if (lower == 's' || lower == 'q') {
                    for (int k = 0; k < params.size() - 3; k += 4) {
                        for (int pIdx = 0; pIdx < 4; pIdx += 2) {
                            float px = params.get(k+pIdx), py = params.get(k+pIdx+1);
                            if (relative) { px += curX; py += curY; }
                            minX = Math.min(minX, px); minY = Math.min(minY, py);
                            maxX = Math.max(maxX, px); maxY = Math.max(maxY, py);
                            if (pIdx == 2) { curX = px; curY = py; }
                        }
                    }
                } else if (lower == 'a') {
                    for (int k = 0; k < params.size() - 6; k += 7) {
                        float px = params.get(k+5), py = params.get(k+6);
                        if (relative) { px += curX; py += curY; }
                        minX = Math.min(minX, px); minY = Math.min(minY, py);
                        maxX = Math.max(maxX, px); maxY = Math.max(maxY, py);
                        curX = px; curY = py;
                    }
                }
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

    public boolean contains(Element el, float x, float y) {
        return contains(el, x, y, 0f);
    }

    public boolean contains(Element el, float x, float y, float expansion) {
        Matrix m = getCumulativeTransform(el);
        Matrix inv = new Matrix();
        if (!m.invert(inv)) return false;

        float[] pts = {x, y};
        inv.mapPoints(pts);
        float lx = pts[0], ly = pts[1];

        String tag = normalizeTag(el.getTagName());
        if ("g".equals(tag)) {
            NodeList children = el.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node child = children.item(i);
                if (child instanceof Element) {
                    if (contains((Element) child, x, y, expansion)) return true;
                }
            }
            return false;
        }

        float tol = Math.max(0.5f, expansion);

        switch (tag) {
            case "rect":
                RectF rb = computeRectBounds(el);
                if (rb == null) return false;
                rb.inset(-tol, -tol);
                return rb.contains(lx, ly);
            case "circle":
                float cx = fa(el, "cx"), cy = fa(el, "cy"), r = fa(el, "r");
                return distSq(lx, ly, cx, cy) <= (r + tol) * (r + tol);
            case "ellipse":
                RectF eb = computeEllipseBounds(el);
                if (eb == null) return false;
                eb.inset(-tol, -tol);
                return eb.contains(lx, ly);
            case "line":
                return isPointNearSegment(lx, ly, fa(el, "x1"), fa(el, "y1"), fa(el, "x2"), fa(el, "y2"), tol);
            case "polyline":
            case "polygon":
                return isPointInPoly(el.getAttribute("points"), lx, ly, tol, "polygon".equals(tag));
            case "path":
                return isPointInPath(el.getAttribute("d"), lx, ly, tol);
        }
        return false;
    }

    private boolean isPointNearSegment(float px, float py, float x1, float y1, float x2, float y2, float tol) {
        float dx = x2 - x1, dy = y2 - y1;
        float l2 = dx * dx + dy * dy;
        if (l2 == 0) return distSq(px, py, x1, y1) <= tol * tol;
        float t = Math.max(0, Math.min(1, ((px - x1) * dx + (py - y1) * dy) / l2));
        return distSq(px, py, x1 + t * dx, y1 + t * dy) <= tol * tol;
    }

    private float distSq(float x1, float y1, float x2, float y2) {
        return (x1 - x2) * (x1 - x2) + (y1 - y2) * (y1 - y2);
    }

    private boolean isPointInPoly(String points, float px, float py, float tol, boolean closed) {
        if (points == null || points.isEmpty()) return false;
        String[] parts = points.trim().split("[\\s,]+");
        if (parts.length < 4) return false;

        float firstX = 0, firstY = 0, prevX = 0, prevY = 0;
        int crosses = 0;

        for (int i = 0; i < parts.length - 1; i += 2) {
            try {
                float curX = Float.parseFloat(parts[i]);
                float curY = Float.parseFloat(parts[i+1]);
                if (i == 0) { firstX = prevX = curX; firstY = prevY = curY; }
                else {
                    if (isPointNearSegment(px, py, prevX, prevY, curX, curY, tol)) return true;
                    if (closed && ((prevY > py) != (curY > py)) && (px < (curX - prevX) * (py - prevY) / (curY - prevY) + prevX)) crosses++;
                    prevX = curX; prevY = curY;
                }
            } catch (Exception ignored) {}
        }
        if (closed) {
            if (isPointNearSegment(px, py, prevX, prevY, firstX, firstY, tol)) return true;
            if (((prevY > py) != (firstY > py)) && (px < (firstX - prevX) * (py - prevY) / (firstY - prevY) + prevX)) crosses++;
            return (crosses % 2 != 0);
        }
        return false;
    }

    private boolean isPointInPath(String d, float px, float py, float tol) {
        if (d == null || d.isEmpty()) return false;

        List<float[]> segments = flattenPathToSegments(d);
        if (segments.isEmpty()) return false;

        int crosses = 0;
        for (float[] seg : segments) {
            float x1 = seg[0], y1 = seg[1], x2 = seg[2], y2 = seg[3];
            if (isPointNearSegment(px, py, x1, y1, x2, y2, tol)) return true;
            if (((y1 > py) != (y2 > py)) &&
                    (px < (x2 - x1) * (py - y1) / (y2 - y1) + x1)) {
                crosses++;
            }
        }
        return (crosses % 2 != 0);
    }

    private List<float[]> flattenPathToSegments(String d) {
        List<float[]> segments = new ArrayList<>();
        List<String> tokens = new ArrayList<>();
        Matcher tm = Pattern.compile("[a-zA-Z]|[-+]?(?:\\d*\\.\\d+|\\d+\\.?)(?:[eE][-+]?\\d+)?").matcher(d);
        while (tm.find()) tokens.add(tm.group());

        float curX = 0, curY = 0, startX = 0, startY = 0;
        float prevCtrlX = 0, prevCtrlY = 0;
        char  prevCmd = 0;

        for (int i = 0; i < tokens.size(); i++) {
            String t = tokens.get(i);
            char cmd = t.charAt(0);
            if (!Character.isLetter(cmd)) continue;

            List<Float> params = new ArrayList<>();
            int j = i + 1;
            while (j < tokens.size() && !Character.isLetter(tokens.get(j).charAt(0))) {
                try { params.add(Float.parseFloat(tokens.get(j))); } catch (Exception ignored) {}
                j++;
            }
            i = j - 1;

            char lower = Character.toLowerCase(cmd);
            boolean rel = Character.isLowerCase(cmd);
            char thisCmd = lower;

            switch (lower) {
                case 'm': {
                    for (int k = 0; k < params.size() - 1; k += 2) {
                        float nx = rel ? curX + params.get(k) : params.get(k);
                        float ny = rel ? curY + params.get(k + 1) : params.get(k + 1);
                        if (k > 0) segments.add(new float[]{curX, curY, nx, ny});
                        curX = nx; curY = ny;
                        if (k == 0) { startX = curX; startY = curY; }
                    }
                    break;
                }
                case 'l': {
                    for (int k = 0; k < params.size() - 1; k += 2) {
                        float nx = rel ? curX + params.get(k) : params.get(k);
                        float ny = rel ? curY + params.get(k + 1) : params.get(k + 1);
                        segments.add(new float[]{curX, curY, nx, ny});
                        curX = nx; curY = ny;
                    }
                    break;
                }
                case 'h': {
                    for (float p : params) {
                        float nx = rel ? curX + p : p;
                        segments.add(new float[]{curX, curY, nx, curY});
                        curX = nx;
                    }
                    break;
                }
                case 'v': {
                    for (float p : params) {
                        float ny = rel ? curY + p : p;
                        segments.add(new float[]{curX, curY, curX, ny});
                        curY = ny;
                    }
                    break;
                }
                case 'c': {
                    for (int k = 0; k + 5 < params.size(); k += 6) {
                        float x1 = params.get(k),     y1 = params.get(k + 1);
                        float x2 = params.get(k + 2), y2 = params.get(k + 3);
                        float ex = params.get(k + 4), ey = params.get(k + 5);
                        if (rel) { x1 += curX; y1 += curY; x2 += curX; y2 += curY; ex += curX; ey += curY; }
                        flattenCubic(segments, curX, curY, x1, y1, x2, y2, ex, ey);
                        prevCtrlX = x2; prevCtrlY = y2;
                        curX = ex; curY = ey;
                    }
                    break;
                }
                case 's': {
                    for (int k = 0; k + 3 < params.size(); k += 4) {
                        float x2 = params.get(k),     y2 = params.get(k + 1);
                        float ex = params.get(k + 2), ey = params.get(k + 3);
                        if (rel) { x2 += curX; y2 += curY; ex += curX; ey += curY; }
                        float x1, y1;
                        if (prevCmd == 'c' || prevCmd == 's') { x1 = 2 * curX - prevCtrlX; y1 = 2 * curY - prevCtrlY; }
                        else { x1 = curX; y1 = curY; }
                        flattenCubic(segments, curX, curY, x1, y1, x2, y2, ex, ey);
                        prevCtrlX = x2; prevCtrlY = y2;
                        curX = ex; curY = ey;
                    }
                    break;
                }
                case 'q': {
                    for (int k = 0; k + 3 < params.size(); k += 4) {
                        float x1 = params.get(k),     y1 = params.get(k + 1);
                        float ex = params.get(k + 2), ey = params.get(k + 3);
                        if (rel) { x1 += curX; y1 += curY; ex += curX; ey += curY; }
                        flattenQuadratic(segments, curX, curY, x1, y1, ex, ey);
                        prevCtrlX = x1; prevCtrlY = y1;
                        curX = ex; curY = ey;
                    }
                    break;
                }
                case 't': {
                    for (int k = 0; k + 1 < params.size(); k += 2) {
                        float ex = params.get(k), ey = params.get(k + 1);
                        if (rel) { ex += curX; ey += curY; }
                        float x1, y1;
                        if (prevCmd == 'q' || prevCmd == 't') { x1 = 2 * curX - prevCtrlX; y1 = 2 * curY - prevCtrlY; }
                        else { x1 = curX; y1 = curY; }
                        flattenQuadratic(segments, curX, curY, x1, y1, ex, ey);
                        prevCtrlX = x1; prevCtrlY = y1;
                        curX = ex; curY = ey;
                    }
                    break;
                }
                case 'a': {
                    for (int k = 0; k + 6 < params.size(); k += 7) {
                        float ex = params.get(k + 5), ey = params.get(k + 6);
                        if (rel) { ex += curX; ey += curY; }
                        segments.add(new float[]{curX, curY, ex, ey});
                        curX = ex; curY = ey;
                    }
                    break;
                }
                case 'z': {
                    segments.add(new float[]{curX, curY, startX, startY});
                    curX = startX; curY = startY;
                    break;
                }
            }
            prevCmd = thisCmd;
        }
        return segments;
    }

    private void flattenCubic(List<float[]> out, float x0, float y0,
                              float x1, float y1, float x2, float y2,
                              float x3, float y3) {
        int steps = 10;
        float px = x0, py = y0;
        for (int s = 1; s <= steps; s++) {
            float t = s / (float) steps;
            float mt = 1 - t;
            float x = mt*mt*mt*x0 + 3*mt*mt*t*x1 + 3*mt*t*t*x2 + t*t*t*x3;
            float y = mt*mt*mt*y0 + 3*mt*mt*t*y1 + 3*mt*t*t*y2 + t*t*t*y3;
            out.add(new float[]{px, py, x, y});
            px = x; py = y;
        }
    }

    private void flattenQuadratic(List<float[]> out, float x0, float y0,
                                  float x1, float y1, float x2, float y2) {
        int steps = 8;
        float px = x0, py = y0;
        for (int s = 1; s <= steps; s++) {
            float t = s / (float) steps;
            float mt = 1 - t;
            float x = mt*mt*x0 + 2*mt*t*x1 + t*t*x2;
            float y = mt*mt*y0 + 2*mt*t*y1 + t*t*y2;
            out.add(new float[]{px, py, x, y});
            px = x; py = y;
        }
    }
    public Matrix getCumulativeTransform(Element element) {
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

    /**
     * A more aggressive normalization for fuzzy matching.
     * Removes all spaces, underscores, and dashes.
     */
    private String superNormalize(String s) {
        if (s == null) return "";
        return s.toLowerCase().replaceAll("[\\s_-]+", "");
    }

    public boolean isFuzzyMatch(String id, String target) {
        if (id == null || target == null) return false;
        
        String sId = superNormalize(id);
        String sTarget = superNormalize(target);

        if (sId.equals(sTarget)) return true;
        if (sId.contains(sTarget)) return true;
        if (sTarget.contains(sId)) return true;

        return false;
    }
}
