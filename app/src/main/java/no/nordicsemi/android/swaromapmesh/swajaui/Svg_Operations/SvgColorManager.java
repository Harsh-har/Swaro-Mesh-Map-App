package no.nordicsemi.android.swaromapmesh.swajaui.Svg_Operations;

import android.graphics.RectF;
import android.util.Log;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class SvgColorManager {

    private static final String TAG = "SvgColorManager";

    public static final String COLOR_SELECTED      = "#ff0000";
    public static final String COLOR_DEVICE_ACTIVE = "#ffbb00";
    public static final String COLOR_TRANSPARENT   = "transparent";

    private static final String STYLE_AREA_DEFAULT =
            "fill:none;stroke:white;stroke-miterlimit:10;stroke-width:3px;";
    private static final String STYLE_AREA_DIM =
            "fill:#000000;fill-opacity:0.72;stroke:#333333;"
                    + "stroke-width:1px;stroke-miterlimit:10;";
    private static final String STYLE_AREA_FOCUSED =
            "fill:none;stroke:none;stroke-miterlimit:10;";

    private Document   svgDocument;
    private SvgParsers parser;

    private final Map<Integer, String>  originalIconFillMap     = new HashMap<>();
    private final Map<Integer, Boolean> originalIconFillInStyle = new HashMap<>();
    private final Map<String, String>   originalAreaStyles      = new HashMap<>();

    private String dimmedAreaId = null;

    private Map<String, Element> userLayerMap = new HashMap<>();

    // ══════════════════════════════════════════════════════════════════════
    //  INIT
    // ══════════════════════════════════════════════════════════════════════

    public void init(Document document,
                     SvgParsers svgParser,
                     Map<String, DeviceInfo> deviceMap) {
        this.svgDocument = document;
        this.parser      = svgParser;

        originalIconFillMap.clear();
        originalIconFillInStyle.clear();
        originalAreaStyles.clear();
        dimmedAreaId = null;

        for (DeviceInfo info : deviceMap.values())
            snapshotIconRectFill(info.element);

        userLayerMap = svgParser.parseUserLayer(document);
        hideAllUserLayerElements();

        Log.d(TAG, "init: " + deviceMap.size() + " tech icons, "
                + userLayerMap.size() + " user-layer elements");
    }

    // ══════════════════════════════════════════════════════════════════════
    //  SNAPSHOT
    // ══════════════════════════════════════════════════════════════════════

    private void snapshotIconRectFill(Element iconGroup) {
        NodeList children = iconGroup.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (!(child instanceof Element)) continue;
            Element childEl = (Element) child;
            if (!"rect".equals(parser.normalizeTag(childEl.getTagName()))) continue;

            int key = System.identityHashCode(childEl);
            if (originalIconFillMap.containsKey(key)) return;

            String fillAttr = childEl.getAttribute("fill");
            if (fillAttr != null && !fillAttr.isEmpty()) {
                originalIconFillMap.put(key, fillAttr);
                originalIconFillInStyle.put(key, false);
                childEl.setAttribute("data-original-fill", fillAttr);
                return;
            }

            String styleAttr = childEl.getAttribute("style");
            if (styleAttr != null && styleAttr.contains("fill")) {
                String fillFromStyle = extractFillFromStyle(styleAttr);
                if (!COLOR_TRANSPARENT.equals(fillFromStyle)) {
                    originalIconFillMap.put(key, fillFromStyle);
                    originalIconFillInStyle.put(key, true);
                    childEl.setAttribute("data-original-fill", fillFromStyle);
                    return;
                }
            }

            originalIconFillMap.put(key, COLOR_TRANSPARENT);
            originalIconFillInStyle.put(key, false);
            childEl.setAttribute("data-original-fill", COLOR_TRANSPARENT);
        }
    }

    private String extractFillFromStyle(String style) {
        if (style == null) return COLOR_TRANSPARENT;
        for (String part : style.split(";")) {
            part = part.trim();
            if (part.startsWith("fill:")) return part.substring(5).trim();
        }
        return COLOR_TRANSPARENT;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  AREA FOCUS  — main entry point
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Called on initial load and on every area selection.
     *
     * What it does:
     *  1. Hides ALL User_Layer elements
     *  2. Hides ALL Selection / Selection-N layers
     *  3. Shows only the Furniture group belonging to focusedRoomGroupId
     *  4. Shows Technician_Layer icons for focusedRoomGroupId (original color)
     *  5. Hides Technician_Layer icons for all other rooms (transparent)
     */
    public void applyAreaFocus(String focusedRoomGroupId,
                               Map<String, DeviceInfo> deviceMap) {
        hideAllUserLayerElements();
        hideAllSelectionLayers();
        applyFurnitureVisibility(focusedRoomGroupId);

        for (Map.Entry<String, DeviceInfo> entry : deviceMap.entrySet()) {
            String     id   = entry.getKey();
            DeviceInfo info = entry.getValue();
            if (focusedRoomGroupId.equals(info.areaId)) {
                restoreIconGroupColor(info.element);
            } else {
                applyColorToIconGroup(info.element, COLOR_TRANSPARENT);
            }
        }

        Log.d(TAG, "applyAreaFocus: " + focusedRoomGroupId);
    }

    // ══════════════════════════════════════════════════════════════════════
    //  SELECTION LAYER  — hide / restore
    // ══════════════════════════════════════════════════════════════════════

    public void hideAllSelectionLayers() {
        if (svgDocument == null) return;
        toggleSelectionLayersRecursive(
                svgDocument.getDocumentElement(), false);
    }

    public void restoreAllSelectionLayers() {
        if (svgDocument == null) return;
        toggleSelectionLayersRecursive(
                svgDocument.getDocumentElement(), true);
    }

    private void toggleSelectionLayersRecursive(Element parent, boolean visible) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (!(child instanceof Element)) continue;
            Element el = (Element) child;
            String id = el.getAttribute("id");
            if (id != null) {
                String lower = id.toLowerCase();
                if (lower.equals("selection")
                        || lower.startsWith("selection-")
                        || lower.equals("selection_layer")) {
                    setElementVisible(el, visible);
                    continue;
                }
            }
            toggleSelectionLayersRecursive(el, visible);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  FURNITURE  — show / hide per room, full restore
    // ══════════════════════════════════════════════════════════════════════

    private void applyFurnitureVisibility(String focusedRoomGroupId) {
        if (svgDocument == null) return;
        applyFurnitureVisibilityRecursive(
                svgDocument.getDocumentElement(), focusedRoomGroupId);
    }

    private void applyFurnitureVisibilityRecursive(Element parent,
                                                   String focusedRoomGroupId) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (!(child instanceof Element)) continue;
            Element el = (Element) child;
            String id = el.getAttribute("id");
            if (id == null || id.isEmpty()) {
                applyFurnitureVisibilityRecursive(el, focusedRoomGroupId);
                continue;
            }
            if (id.equals("Furniture") || id.startsWith("Furniture-")) {
                Node parentNode = el.getParentNode();
                String parentId = (parentNode instanceof Element)
                        ? ((Element) parentNode).getAttribute("id") : "";
                boolean belongs = focusedRoomGroupId.equals(parentId);
                setElementVisible(el, belongs);
            } else {
                applyFurnitureVisibilityRecursive(el, focusedRoomGroupId);
            }
        }
    }

    public void restoreAllFurniture() {
        if (svgDocument == null) return;
        List<Element> groups = findAllFurnitureGroups();
        for (Element g : groups) setElementVisible(g, true);
    }

    private List<Element> findAllFurnitureGroups() {
        List<Element> result = new ArrayList<>();
        if (svgDocument == null) return result;
        collectFurnitureGroups(svgDocument.getDocumentElement(), result);
        return result;
    }

    private void collectFurnitureGroups(Element parent, List<Element> result) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (!(child instanceof Element)) continue;
            Element el = (Element) child;
            String id = el.getAttribute("id");
            if (id != null
                    && (id.equals("Furniture") || id.startsWith("Furniture-"))) {
                result.add(el);
            } else {
                collectFurnitureGroups(el, result);
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  TECHNICIAN_LAYER  — icon color API
    // ══════════════════════════════════════════════════════════════════════

    public void applyColorToIconGroup(Element iconGroup, String color) {
        if (iconGroup == null) return;
        NodeList children = iconGroup.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (!(child instanceof Element)) continue;
            Element childEl = (Element) child;
            if (!"rect".equals(parser.normalizeTag(childEl.getTagName()))) continue;

            int     key      = System.identityHashCode(childEl);
            boolean useStyle = Boolean.TRUE.equals(originalIconFillInStyle.get(key));

            if (useStyle) {
                String style = childEl.getAttribute("style");
                if (style != null && style.contains("fill:")) {
                    childEl.setAttribute("style",
                            style.replaceAll("fill\\s*:\\s*[^;]+", "fill:" + color));
                } else {
                    String newStyle = (style != null && !style.isEmpty())
                            ? style + ";fill:" + color : "fill:" + color;
                    childEl.setAttribute("style", newStyle);
                }
            } else {
                childEl.setAttribute("fill", color);
            }
            return;
        }
    }

    public void restoreIconGroupColor(Element iconGroup) {
        if (iconGroup == null) return;
        NodeList children = iconGroup.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (!(child instanceof Element)) continue;
            Element childEl = (Element) child;
            if (!"rect".equals(parser.normalizeTag(childEl.getTagName()))) continue;

            int    key      = System.identityHashCode(childEl);
            String origFill = originalIconFillMap.get(key);
            if (origFill == null) return;

            if (Boolean.TRUE.equals(originalIconFillInStyle.get(key))) {
                String style = childEl.getAttribute("style");
                if (style != null && style.contains("fill:")) {
                    childEl.setAttribute("style",
                            style.replaceAll("fill\\s*:\\s*[^;]+", "fill:" + origFill));
                } else {
                    childEl.setAttribute("fill", origFill);
                }
            } else {
                childEl.setAttribute("fill", origFill);
            }
            return;
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  USER_LAYER  — show / hide
    // ══════════════════════════════════════════════════════════════════════

    public void hideAllUserLayerElements() {
        for (Element el : userLayerMap.values())
            setUserLayerElementVisible(el, false);
        Log.d(TAG, "hideAllUserLayerElements: " + userLayerMap.size());
    }

    public void showUserLayerElement(String techIconId) {
        Element el = userLayerMap.get(techIconId);
        if (el != null) {
            setUserLayerElementVisible(el, true);
        } else {
            Log.w(TAG, "showUserLayerElement: no user-layer element for " + techIconId);
        }
    }

    public void hideUserLayerElement(String techIconId) {
        Element el = userLayerMap.get(techIconId);
        if (el != null) setUserLayerElementVisible(el, false);
    }

    private void setUserLayerElementVisible(Element el, boolean visible) {
        setElementVisible(el, visible);
    }

    // ══════════════════════════════════════════════════════════════════════
    //  FULL COLOR REFRESH  — provisioned-aware, non-focus mode
    // ══════════════════════════════════════════════════════════════════════

    public void refreshAllColors(Map<String, DeviceInfo> deviceMap,
                                 Set<String>             provisionedIds,
                                 String                  selectedDeviceId,
                                 String                  areaFilterId) {
        if (deviceMap.isEmpty()) return;
        hideAllUserLayerElements();

        for (Map.Entry<String, DeviceInfo> entry : deviceMap.entrySet()) {
            String     id   = entry.getKey();
            DeviceInfo info = entry.getValue();

            if (areaFilterId != null && !areaFilterId.equals(info.areaId)) {
                applyColorToIconGroup(info.element, COLOR_TRANSPARENT);
                continue;
            }

            boolean provisioned = provisionedIds != null
                    && provisionedIds.contains(id.trim().toLowerCase());

            if (provisioned) {
                applyColorToIconGroup(info.element, COLOR_TRANSPARENT);
                showUserLayerElement(id);
            } else if (id.equals(selectedDeviceId)) {
                applyColorToIconGroup(info.element, COLOR_SELECTED);
            } else {
                restoreIconGroupColor(info.element);
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  AREA DIM LOGIC  — legacy, kept for exitAreaZoom path
    // ══════════════════════════════════════════════════════════════════════

    public void dimOtherAreas(String              focusedAreaId,
                              Map<String,Element> selectionLayerElements,
                              Map<String,RectF>   selectionLayerBounds,
                              RectF               focusedAreaBounds) {
        if (focusedAreaId == null) {
            restoreAllAreas(selectionLayerElements, selectionLayerBounds);
            return;
        }

        dimmedAreaId = focusedAreaId;

        boolean hasSelectionLayer = !selectionLayerElements.isEmpty();
        if (hasSelectionLayer) {
            for (Map.Entry<String, Element> entry : selectionLayerElements.entrySet()) {
                String  areaId = entry.getKey();
                Element areaEl = entry.getValue();
                if (!originalAreaStyles.containsKey(areaId)) {
                    String orig = areaEl.getAttribute("style");
                    originalAreaStyles.put(areaId,
                            (orig == null || orig.isEmpty())
                                    ? STYLE_AREA_DEFAULT : orig);
                }
                areaEl.setAttribute("style",
                        areaId.equals(focusedAreaId)
                                ? STYLE_AREA_FOCUSED : STYLE_AREA_DIM);
            }
        }
    }

    public void restoreAllAreas(Map<String,Element> selectionLayerElements,
                                Map<String,RectF>   selectionLayerBounds) {
        for (Map.Entry<String, Element> entry : selectionLayerElements.entrySet()) {
            String  areaId = entry.getKey();
            Element areaEl = entry.getValue();
            String  orig   = originalAreaStyles.get(areaId);
            areaEl.setAttribute("style",
                    orig != null ? orig : STYLE_AREA_DEFAULT);
        }
        originalAreaStyles.clear();
        dimmedAreaId = null;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  SNAPSHOT RESET
    // ══════════════════════════════════════════════════════════════════════

    public void forceResnapshotAllDevices(Map<String, DeviceInfo> deviceMap) {
        for (DeviceInfo info : deviceMap.values()) {
            NodeList children = info.element.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node child = children.item(i);
                if (!(child instanceof Element)) continue;
                Element childEl = (Element) child;
                if (!"rect".equals(parser.normalizeTag(childEl.getTagName())))
                    continue;

                int key = System.identityHashCode(childEl);
                String original = childEl.getAttribute("data-original-fill");
                if (original != null && !original.isEmpty()) {
                    originalIconFillMap.put(key, original);
                    originalIconFillInStyle.put(key, false);
                    childEl.setAttribute("fill", original);
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  GENERIC ELEMENT VISIBILITY
    // ══════════════════════════════════════════════════════════════════════

    private void setElementVisible(Element el, boolean visible) {
        if (el == null) return;
        if (visible) {
            String style = el.getAttribute("style");
            if (style != null && style.contains("display:none")) {
                String cleaned = style.replace("display:none;", "")
                        .replace("display:none", "").trim();
                if (cleaned.isEmpty()) el.removeAttribute("style");
                else el.setAttribute("style", cleaned);
            }
        } else {
            String style = el.getAttribute("style");
            if (style == null || !style.contains("display:none")) {
                String newStyle = (style != null && !style.isEmpty())
                        ? "display:none;" + style : "display:none;";
                el.setAttribute("style", newStyle);
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  GETTERS
    // ══════════════════════════════════════════════════════════════════════

    public String getDimmedAreaId() { return dimmedAreaId; }

    public Map<String, Element> getUserLayerMap() { return userLayerMap; }
}