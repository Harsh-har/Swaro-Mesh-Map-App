package no.nordicsemi.android.swaromapmesh.swajaui.Svg_Operations;

import android.graphics.RectF;
import android.util.Log;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class SvgColorManager {

    private static final String TAG = "SvgColorManager";

    // ── Icon color constants ──────────────────────────────────────────────
    public static final String COLOR_SELECTED      = "#ff0000";
    public static final String COLOR_DEVICE_ACTIVE = "#ff00bb";
    public static final String COLOR_TRANSPARENT   = "transparent";

    // ── Area dim overlay styles ───────────────────────────────────────────
    private static final String STYLE_AREA_DEFAULT =
            "fill:none;stroke:white;stroke-miterlimit:10;stroke-width:3px;";
    private static final String STYLE_AREA_DIM =
            "fill:#000000;fill-opacity:0.72;stroke:#333333;stroke-width:1px;stroke-miterlimit:10;";
    private static final String STYLE_AREA_FOCUSED =
            "fill:none;stroke:none;stroke-miterlimit:10;";

    // ── Dependencies (set once after SVG is loaded) ───────────────────────
    private Document svgDocument;
    private SvgParsers parser;

    // ── Snapshot maps — original styles before modification ───────────────
    /** Original fill of each icon's inner <rect>, keyed by identity hash */
    private final Map<Integer, String> originalIconFillMap    = new HashMap<>();
    /** Original fill of elements in the Devices group, keyed by identity hash */
    private final Map<Integer, String> devicesOriginalFillMap = new HashMap<>();
    /** Original style strings of selection_layer rects, keyed by area ID */
    private final Map<String, String>  originalAreaStyles     = new HashMap<>();

    /** Currently dimmed area (other areas get the dark overlay) */
    private String dimmedAreaId = null;

    // ══════════════════════════════════════════════════════════════════════
    //  INITIALISATION
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Call this every time a new SVG document is loaded.
     * Snapshots original colors so they can be restored later.
     */
    public void init(Document document, SvgParsers svgParser,
                     Map<String, DeviceInfo> deviceMap) {
        this.svgDocument = document;
        this.parser      = svgParser;
        originalIconFillMap.clear();
        devicesOriginalFillMap.clear();
        originalAreaStyles.clear();
        dimmedAreaId = null;

        for (DeviceInfo info : deviceMap.values())
            snapshotIconRectFill(info.element);

        snapshotDevicesGroupFills(document);
    }

    // ── Snapshot helpers ──────────────────────────────────────────────────

    /** Snapshot the fill of the first <rect> child inside an icon group. */
    private void snapshotIconRectFill(Element iconGroup) {
        NodeList children = iconGroup.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (!(child instanceof Element)) continue;
            if ("rect".equals(parser.normalizeTag(((Element) child).getTagName()))) {
                int    key  = System.identityHashCode(child);
                String fill = ((Element) child).getAttribute("fill");
                if (fill != null && !fill.isEmpty()) originalIconFillMap.put(key, fill);
                return;
            }
        }
    }

    private void snapshotDevicesGroupFills(Document document) {
        if (document == null) return;
        Element dg = parser.findElementById(document.getDocumentElement(), "Devices");
        if (dg == null) return;
        snapshotFillsRecursive(dg);
    }

    private void snapshotFillsRecursive(Element el) {
        String fill = el.getAttribute("fill");
        if (fill != null && !fill.isEmpty())
            devicesOriginalFillMap.put(System.identityHashCode(el), fill);
        String style = el.getAttribute("style");
        if (style != null && style.contains("fill"))
            devicesOriginalFillMap.put(System.identityHashCode(el),
                    extractFillFromStyle(style));
        NodeList children = el.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element) snapshotFillsRecursive((Element) child);
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
    //  ICON COLOR API
    // ══════════════════════════════════════════════════════════════════════

    /** Sets the fill of the first <rect> inside an icon group element. */
    public void applyColorToIconGroup(Element iconGroup, String color) {
        NodeList children = iconGroup.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (!(child instanceof Element)) continue;
            if ("rect".equals(parser.normalizeTag(((Element) child).getTagName()))) {
                ((Element) child).setAttribute("fill", color);
                return;
            }
        }
    }

    /** Restores the fill of an icon group's <rect> to its snapshotted value. */
    public void restoreIconGroupColor(Element iconGroup) {
        NodeList children = iconGroup.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (!(child instanceof Element)) continue;
            if ("rect".equals(parser.normalizeTag(((Element) child).getTagName()))) {
                int    key  = System.identityHashCode(child);
                String orig = originalIconFillMap.get(key);
                if (orig != null) ((Element) child).setAttribute("fill", orig);
                return;
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  DEVICE GROUP (physical Devices layer)
    // ══════════════════════════════════════════════════════════════════════

    public void showOnlyPhysicalDevices(Set<String> activeDeviceIds) {
        if (svgDocument == null) return;
        Element dg = parser.findElementById(svgDocument.getDocumentElement(), "Devices");
        if (dg == null) return;
        applyColorToAllElements(dg, COLOR_TRANSPARENT);
        for (String deviceId : activeDeviceIds) {
            Element deviceEl = parser.findElementById(dg, deviceId);
            if (deviceEl != null) applyColorToAllElements(deviceEl, COLOR_DEVICE_ACTIVE);
        }
    }

    /** Hides all elements in the Devices layer. */
    public void hideAllPhysicalDevices() {
        if (svgDocument == null) return;
        Element dg = parser.findElementById(svgDocument.getDocumentElement(), "Devices");
        if (dg != null) applyColorToAllElements(dg, COLOR_TRANSPARENT);
    }

    public void applyColorToAllElements(Element el, String color) {
        String fill = el.getAttribute("fill");
        if (fill != null && !fill.isEmpty()) el.setAttribute("fill", color);
        String style = el.getAttribute("style");
        if (style != null && !style.isEmpty() && style.contains("fill"))
            el.setAttribute("style",
                    style.replaceAll("fill\\s*:\\s*[^;]+", "fill:" + color));
        NodeList children = el.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element)
                applyColorToAllElements((Element) child, color);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  FULL COLOR REFRESH
    // ══════════════════════════════════════════════════════════════════════

    public void refreshAllColors(Map<String, DeviceInfo> deviceMap,
                                 Set<String> provisionedIds,
                                 String selectedDeviceId,
                                 Map<String, Set<String>> iconToDeviceRelations,
                                 String areaFilterId) {
        if (deviceMap.isEmpty()) return;
        Set<String> devicesToShow = new HashSet<>();

        for (Map.Entry<String, DeviceInfo> entry : deviceMap.entrySet()) {
            String     id   = entry.getKey();
            DeviceInfo info = entry.getValue();

            // Area filter: hide icons outside the focused area
            if (areaFilterId != null && !areaFilterId.equals(info.areaId)) {
                applyColorToIconGroup(info.element, COLOR_TRANSPARENT);
                continue;
            }

            boolean provisioned = provisionedIds != null && provisionedIds.contains(id);
            if (provisioned) {
                applyColorToIconGroup(info.element, COLOR_TRANSPARENT);
                Set<String> related = iconToDeviceRelations.get(id);
                if (related != null) devicesToShow.addAll(related);
            } else if (id.equals(selectedDeviceId)) {
                applyColorToIconGroup(info.element, COLOR_SELECTED);
            } else {
                restoreIconGroupColor(info.element);
            }
        }

        if (devicesToShow.isEmpty()) hideAllPhysicalDevices();
        else showOnlyPhysicalDevices(devicesToShow);
    }

    // ══════════════════════════════════════════════════════════════════════
    //  AREA DIM LOGIC
    // ══════════════════════════════════════════════════════════════════════

    public void dimOtherAreas(String focusedAreaId,
                              Map<String, Element> selectionLayerElements,
                              Map<String, RectF>   selectionLayerBounds) {
        if (focusedAreaId == null) {
            restoreAllAreas(selectionLayerElements, selectionLayerBounds);
            return;
        }
        dimmedAreaId = focusedAreaId;

        // 1. Style selection_layer rects
        for (Map.Entry<String, Element> entry : selectionLayerElements.entrySet()) {
            String  areaId = entry.getKey();
            Element areaEl = entry.getValue();

            if (!originalAreaStyles.containsKey(areaId)) {
                String orig = areaEl.getAttribute("style");
                originalAreaStyles.put(areaId,
                        (orig == null || orig.isEmpty()) ? STYLE_AREA_DEFAULT : orig);
            }

            if (areaId.equals(focusedAreaId))
                areaEl.setAttribute("style", STYLE_AREA_FOCUSED);
            else
                areaEl.setAttribute("style", STYLE_AREA_DIM);
        }

        // 2. Dim Walls layer
        setWallsOpacity(true);

        // 3. Dim furniture outside focused area
        dimFurnitureOutsideArea(focusedAreaId);

        // 4. Highlight doors in focused area
        highlightDoorsInArea(focusedAreaId, selectionLayerBounds.get(focusedAreaId));
    }

    /**
     * Restores all areas, furniture, walls, and doors to their original state.
     */
    public void restoreAllAreas(Map<String, Element> selectionLayerElements,
                                Map<String, RectF>   selectionLayerBounds) {
        // Restore selection_layer overlay styles
        for (Map.Entry<String, Element> entry : selectionLayerElements.entrySet()) {
            String  areaId = entry.getKey();
            Element areaEl = entry.getValue();
            String  orig   = originalAreaStyles.get(areaId);
            areaEl.setAttribute("style", orig != null ? orig : STYLE_AREA_DEFAULT);
        }
        originalAreaStyles.clear();
        dimmedAreaId = null;

        restoreFurnitureVisibility();
        setWallsOpacity(false);
        restoreAllDoors();
    }

    // ── Furniture ─────────────────────────────────────────────────────────
    private void dimFurnitureOutsideArea(String focusedAreaId) {
        if (svgDocument == null || focusedAreaId == null) return;
        Element furnitureGroup =
                parser.findElementById(svgDocument.getDocumentElement(), "Furniture");
        if (furnitureGroup == null) return;

        // Remove group-level opacity so children can be styled independently
        if (!furnitureGroup.hasAttribute("data-orig-group-style")) {
            String gs = furnitureGroup.getAttribute("style");
            furnitureGroup.setAttribute("data-orig-group-style",
                    (gs != null && !gs.isEmpty()) ? gs : "__visible__");
        }
        furnitureGroup.removeAttribute("style");

        String normFocus  = parser.normalize(focusedAreaId);
        NodeList children = furnitureGroup.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (!(child instanceof Element)) continue;
            Element el = (Element) child;
            String  id = el.getAttribute("id");
            if (id == null || id.isEmpty()) continue;

            if (!el.hasAttribute("data-orig-display")) {
                String orig = el.getAttribute("style");
                el.setAttribute("data-orig-display",
                        (orig != null && !orig.isEmpty()) ? orig : "__visible__");
            }

            if (parser.isFuzzyMatch(parser.normalize(id), normFocus)) {
                String saved = el.getAttribute("data-orig-display");
                if ("__visible__".equals(saved)) el.removeAttribute("style");
                else el.setAttribute("style", saved);
            } else {
                el.setAttribute("style", "opacity:0.25;");
            }
        }
    }

    /** Restores the Furniture group and all its children to their original styles. */
    private void restoreFurnitureVisibility() {
        if (svgDocument == null) return;
        Element furnitureGroup =
                parser.findElementById(svgDocument.getDocumentElement(), "Furniture");
        if (furnitureGroup == null) return;

        if (furnitureGroup.hasAttribute("data-orig-group-style")) {
            String saved = furnitureGroup.getAttribute("data-orig-group-style");
            if ("__visible__".equals(saved)) furnitureGroup.removeAttribute("style");
            else furnitureGroup.setAttribute("style", saved);
            furnitureGroup.removeAttribute("data-orig-group-style");
        }

        NodeList children = furnitureGroup.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (!(child instanceof Element)) continue;
            Element el = (Element) child;
            if (el.hasAttribute("data-orig-display")) {
                String saved = el.getAttribute("data-orig-display");
                if ("__visible__".equals(saved)) el.removeAttribute("style");
                else el.setAttribute("style", saved);
                el.removeAttribute("data-orig-display");
            }
        }
    }

    // ── Walls ─────────────────────────────────────────────────────────────

    /** Sets the Walls layer to 25% opacity (dim=true) or restores it (dim=false). */
    private void setWallsOpacity(boolean dim) {
        if (svgDocument == null) return;
        Element walls = parser.findElementById(svgDocument.getDocumentElement(), "Walls");
        if (walls == null) return;

        if (dim) {
            if (!walls.hasAttribute("data-orig-walls")) {
                String s = walls.getAttribute("style");
                walls.setAttribute("data-orig-walls",
                        (s != null && !s.isEmpty()) ? s : "__visible__");
            }
            walls.setAttribute("style", "opacity:0.25;");
        } else {
            if (walls.hasAttribute("data-orig-walls")) {
                String saved = walls.getAttribute("data-orig-walls");
                if ("__visible__".equals(saved)) walls.removeAttribute("style");
                else walls.setAttribute("style", saved);
                walls.removeAttribute("data-orig-walls");
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  DOOR HIGHLIGHTING
    // ══════════════════════════════════════════════════════════════════════
    public void highlightDoorsInArea(String areaId, RectF areaBounds) {
        if (svgDocument == null || areaId == null) return;
        restoreAllDoors();
        if (areaBounds == null) {
            Log.w(TAG, "highlightDoorsInArea: no bounds for area " + areaId);
            return;
        }

        Element furnitureGroup =
                parser.findElementById(svgDocument.getDocumentElement(), "Furniture");
        if (furnitureGroup == null) return;

        List<Element> doorElements = collectAllDoorElements(furnitureGroup);
        String normAreaId = parser.normalize(areaId);

        for (Element doorEl : doorElements) {
            // Snapshot original style once
            if (!doorEl.hasAttribute("data-orig-door-style")) {
                String orig = doorEl.getAttribute("style");
                doorEl.setAttribute("data-orig-door-style",
                        (orig != null && !orig.isEmpty()) ? orig : "");
            }

            if (isDoorBelongingToArea(doorEl, normAreaId, areaBounds)) {
                applyDoorHighlight(doorEl);
                Log.d(TAG, "Highlighted door: " + doorEl.getAttribute("id")
                        + " for area: " + areaId);
            } else {
                restoreDoorStyle(doorEl);
            }
        }
    }

    /** Restores all doors in the Furniture group to their original styles. */
    public void restoreAllDoors() {
        if (svgDocument == null) return;
        Element furnitureGroup =
                parser.findElementById(svgDocument.getDocumentElement(), "Furniture");
        if (furnitureGroup == null) return;

        NodeList children = furnitureGroup.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (!(child instanceof Element)) continue;
            Element el = (Element) child;
            String  id = el.getAttribute("id");
            if (id != null && id.toLowerCase().contains("door")
                    && el.hasAttribute("data-orig-door-style")) {
                restoreDoorStyle(el);
            }
        }
    }

    private void applyDoorHighlight(Element doorEl) {
        String tag = parser.normalizeTag(doorEl.getTagName());
        if ("polyline".equals(tag) || "path".equals(tag) || "line".equals(tag)) {
            doorEl.setAttribute("style",
                    "stroke:#ff0000;stroke-width:2.5px;fill:none;");
        } else {
            doorEl.setAttribute("style",
                    "fill:#ff0000;stroke:#cc0000;stroke-width:1px;");
        }
    }

    private void restoreDoorStyle(Element doorEl) {
        if (!doorEl.hasAttribute("data-orig-door-style")) return;
        String saved = doorEl.getAttribute("data-orig-door-style");
        if (saved != null && !saved.isEmpty()) doorEl.setAttribute("style", saved);
        else doorEl.removeAttribute("style");
        doorEl.removeAttribute("data-orig-door-style");
    }

    // ── Door helpers ──────────────────────────────────────────────────────

    /**
     * Recursively collects every element with "door" in its id attribute.
     */
    private List<Element> collectAllDoorElements(Element parent) {
        List<Element> doors = new ArrayList<>();
        String id = parent.getAttribute("id");
        if (id != null && id.toLowerCase().contains("door")) doors.add(parent);
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element)
                doors.addAll(collectAllDoorElements((Element) child));
        }
        return doors;
    }
    private boolean isDoorBelongingToArea(Element doorEl, String normAreaId,
                                          RectF areaBounds) {
        // Strategy 1: explicit data-area attribute
        String dataArea = doorEl.getAttribute("data-area");
        if (dataArea != null && !dataArea.isEmpty()
                && parser.normalize(dataArea).equals(normAreaId))
            return true;

        // Strategy 2: parent chain matching
        Node parent = doorEl.getParentNode();
        while (parent instanceof Element) {
            String parentId = ((Element) parent).getAttribute("id");
            if (parentId != null && !parentId.isEmpty()
                    && parser.isFuzzyMatch(parser.normalize(parentId), normAreaId))
                return true;
            parent = parent.getParentNode();
        }

        // Strategy 3: door's own ID
        String doorId = doorEl.getAttribute("id");
        if (doorId != null && parser.isFuzzyMatch(parser.normalize(doorId), normAreaId))
            return true;

        // Strategy 4: spatial containment
        if (areaBounds != null) {
            RectF doorBounds = parser.computeBounds(doorEl);
            if (doorBounds != null && !doorBounds.isEmpty()) {
                // Center point inside area
                if (areaBounds.contains(doorBounds.centerX(), doorBounds.centerY()))
                    return true;
                // ≥30% overlap
                RectF intersection = new RectF(doorBounds);
                if (intersection.intersect(areaBounds)) {
                    float overlap   = intersection.width() * intersection.height();
                    float doorArea  = doorBounds.width() * doorBounds.height();
                    if (doorArea > 0 && (overlap / doorArea) > 0.3f) return true;
                }
            }
        }
        return false;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  GETTERS
    // ══════════════════════════════════════════════════════════════════════

    public String getDimmedAreaId() { return dimmedAreaId; }
}