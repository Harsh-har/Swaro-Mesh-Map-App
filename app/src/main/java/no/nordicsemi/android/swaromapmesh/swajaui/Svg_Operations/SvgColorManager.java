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
    public static final String COLOR_DEVICE_ACTIVE = "#ffbb00";
    public static final String COLOR_TRANSPARENT   = "transparent";

    // ── Area dim overlay styles ───────────────────────────────────────────
    private static final String STYLE_AREA_DEFAULT =
            "fill:none;stroke:white;stroke-miterlimit:10;stroke-width:3px;";
    private static final String STYLE_AREA_DIM =
            "fill:#000000;fill-opacity:0.72;stroke:#333333;stroke-width:1px;stroke-miterlimit:10;";
    private static final String STYLE_AREA_FOCUSED =
            "fill:none;stroke:none;stroke-miterlimit:10;";

    // ── Dependencies (set once after SVG is loaded) ───────────────────────
    private Document   svgDocument;
    private SvgParsers parser;

    // ── Snapshot maps — original styles before modification ───────────────
    /**
     * Original fill of each icon's inner <rect>.
     * Key = identity hash of the rect Element.
     * Value = original fill string (may come from fill attr OR style attr).
     */
    private final Map<Integer, String> originalIconFillMap    = new HashMap<>();
    /**
     * Whether the original fill was stored inside the style attribute (true)
     * or as a standalone fill attribute (false).
     */
    private final Map<Integer, Boolean> originalIconFillInStyle = new HashMap<>();

    /** Original fill of elements in the Devices group, keyed by identity hash */
    private final Map<Integer, String>  devicesOriginalFillMap = new HashMap<>();
    /** Original style strings of selection_layer rects, keyed by area ID */
    private final Map<String, String>   originalAreaStyles     = new HashMap<>();

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
        originalIconFillInStyle.clear();
        devicesOriginalFillMap.clear();
        originalAreaStyles.clear();
        dimmedAreaId = null;

        for (DeviceInfo info : deviceMap.values())
            snapshotIconRectFill(info.element);

        snapshotDevicesGroupFills(document);
    }

    // ── Snapshot helpers ──────────────────────────────────────────────────

    /**
     * ✅ FIX: Snapshot the fill of the first <rect> child inside an icon group.
     * Checks BOTH fill attribute AND style attribute (fill:#xxx inside style="...").
     */
    private void snapshotIconRectFill(Element iconGroup) {
        NodeList children = iconGroup.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (!(child instanceof Element)) continue;
            Element childEl = (Element) child;
            if (!"rect".equals(parser.normalizeTag(childEl.getTagName()))) continue;

            int key = System.identityHashCode(childEl);

            // ✅ ALREADY SNAPSHOTTED? Skip
            if (originalIconFillMap.containsKey(key)) return;

            String fillAttr = childEl.getAttribute("fill");
            if (fillAttr != null && !fillAttr.isEmpty()) {
                originalIconFillMap.put(key, fillAttr);
                originalIconFillInStyle.put(key, false);
                // ✅ Store backup
                childEl.setAttribute("data-original-fill", fillAttr);
                return;
            }

            String styleAttr = childEl.getAttribute("style");
            if (styleAttr != null && styleAttr.contains("fill")) {
                String fillFromStyle = extractFillFromStyle(styleAttr);
                if (!fillFromStyle.equals(COLOR_TRANSPARENT)) {
                    originalIconFillMap.put(key, fillFromStyle);
                    originalIconFillInStyle.put(key, true);
                    childEl.setAttribute("data-original-fill", fillFromStyle);
                    return;
                }
            }

            originalIconFillMap.put(key, COLOR_TRANSPARENT);
            originalIconFillInStyle.put(key, false);
            childEl.setAttribute("data-original-fill", COLOR_TRANSPARENT);
            return;
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

    /**
     * ✅ FIX: Sets the fill of the first <rect> inside an icon group element.
     * Handles BOTH fill attribute AND fill inside style attribute.
     *
     * e.g. <rect fill="#ff0"/> → sets fill attribute directly
     *      <rect style="fill:#ffae42; stroke:#000;"/> → updates fill inside style
     */
    public void applyColorToIconGroup(Element iconGroup, String color) {
        NodeList children = iconGroup.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (!(child instanceof Element)) continue;
            Element childEl = (Element) child;
            if (!"rect".equals(parser.normalizeTag(childEl.getTagName()))) continue;

            int     key        = System.identityHashCode(childEl);
            Boolean inStyle    = originalIconFillInStyle.get(key);
            boolean useStyle   = Boolean.TRUE.equals(inStyle);

            if (useStyle) {
                // ── Update fill inside style attribute ────────────────────
                String style = childEl.getAttribute("style");
                if (style != null && style.contains("fill:")) {
                    // Replace existing fill:xxx inside style
                    String newStyle = style.replaceAll(
                            "fill\\s*:\\s*[^;]+", "fill:" + color);
                    childEl.setAttribute("style", newStyle);
                } else {
                    // style exists but no fill yet — append it
                    String newStyle = (style != null && !style.isEmpty())
                            ? style + ";fill:" + color
                            : "fill:" + color;
                    childEl.setAttribute("style", newStyle);
                }
            } else {
                // ── Set fill attribute directly ───────────────────────────
                childEl.setAttribute("fill", color);
            }
            return;
        }
    }

    /**
     * ✅ FIX: Restores the fill of an icon group's <rect> to its snapshotted value.
     * Handles BOTH fill attribute AND fill inside style attribute.
     */
    public void restoreIconGroupColor(Element iconGroup) {
        NodeList children = iconGroup.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (!(child instanceof Element)) continue;
            Element childEl = (Element) child;
            if (!"rect".equals(parser.normalizeTag(childEl.getTagName()))) continue;

            int     key      = System.identityHashCode(childEl);
            String  origFill = originalIconFillMap.get(key);
            Boolean inStyle  = originalIconFillInStyle.get(key);

            if (origFill == null) return; // nothing snapshotted

            if (Boolean.TRUE.equals(inStyle)) {
                // ── Restore fill inside style attribute ───────────────────
                String style = childEl.getAttribute("style");
                if (style != null && style.contains("fill:")) {
                    String restored = style.replaceAll(
                            "fill\\s*:\\s*[^;]+", "fill:" + origFill);
                    childEl.setAttribute("style", restored);
                } else {
                    // Fallback: set fill attr directly
                    childEl.setAttribute("fill", origFill);
                }
            } else {
                // ── Restore fill attribute directly ───────────────────────
                childEl.setAttribute("fill", origFill);
            }
            return;
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

            // ✅ normalize id before lookup — case mismatch fix
            boolean provisioned = provisionedIds != null
                    && (provisionedIds.contains(id.trim().toLowerCase())
                    || provisionedIds.contains(
                    id.trim().toLowerCase().replaceAll("\\s+", "_")));

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
                              Map<String, RectF>   selectionLayerBounds,
                              RectF                focusedAreaBounds)  {
        if (focusedAreaId == null) {
            restoreAllAreas(selectionLayerElements, selectionLayerBounds);
            return;
        }

        // ✅ FIX: If no selection_layer exists, skip overlay dim entirely
        // but still dim furniture for the focused area
        boolean hasSelectionLayer = !selectionLayerElements.isEmpty();

        // ✅ Always clear stale state first
        restoreFurnitureVisibility();
        setWallsOpacity(false);
        restoreAllDoors();

        dimmedAreaId = focusedAreaId;

        if (hasSelectionLayer) {
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
            setWallsOpacity(true);
        }

        dimFurnitureOutsideArea(focusedAreaId, focusedAreaBounds);
        RectF areaBounds = selectionLayerBounds.get(focusedAreaId);
        if (areaBounds != null) {
            highlightDoorsInArea(focusedAreaId, areaBounds);
        }
        // ✅ No warning log spam when bounds simply don't exist
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

    private void dimFurnitureOutsideArea(String focusedAreaId, RectF focusBounds) {
        if (svgDocument == null || focusedAreaId == null) return;
        Element furnitureGroup =
                parser.findElementById(svgDocument.getDocumentElement(), "Furniture");
        if (furnitureGroup == null) {
            Log.w(TAG, "dimFurnitureOutsideArea: Furniture group not found");
            return;
        }

        Log.d(TAG, "dimFurnitureOutsideArea: focusedAreaId=" + focusedAreaId
                + " focusBounds=" + focusBounds);

        restoreFurnitureVisibility();

        if (focusBounds == null && parser.selectionLayerBounds != null) {
            focusBounds = parser.selectionLayerBounds.get(focusedAreaId);
        }

        if (focusBounds == null) {
            Log.w(TAG, "dimFurnitureOutsideArea: no bounds available, skipping dim");
            return;
        }

        if (!furnitureGroup.hasAttribute("data-orig-group-style")) {
            String gs = furnitureGroup.getAttribute("style");
            furnitureGroup.setAttribute("data-orig-group-style",
                    (gs != null && !gs.isEmpty()) ? gs : "__visible__");
        }
        furnitureGroup.removeAttribute("style");

        NodeList children = furnitureGroup.getChildNodes();
        int matched = 0, dimmed = 0;

        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (!(child instanceof Element)) continue;
            Element el = (Element) child;

            // ✅ KEY FIX: Only process named <g> sub-groups, skip bare <path>/<line> etc.
            String tag = el.getTagName().toLowerCase().replace("svg:", "");
            if (!"g".equals(tag)) continue;

            String id = el.getAttribute("id");
            if (id == null || id.isEmpty()) continue;

            if (!el.hasAttribute("data-orig-display")) {
                String orig = el.getAttribute("style");
                el.setAttribute("data-orig-display",
                        (orig != null && !orig.isEmpty()) ? orig : "__visible__");
            }

            boolean belongsToFocus = false;

            // Strategy 1: ID fuzzy match
            belongsToFocus = parser.isFuzzyMatch(parser.normalize(id),
                    parser.normalize(focusedAreaId));

            // Strategy 2: Spatial containment
// Strategy 2: Spatial containment using passed bounds
            if (!belongsToFocus) {
                RectF elBounds = parser.computeBounds(el);
                if (elBounds != null && !elBounds.isEmpty()) {
                    Log.d(TAG, "  checking id=" + id + " bounds=" + elBounds);

                    // Center point check
                    if (focusBounds.contains(elBounds.centerX(), elBounds.centerY())) {
                        belongsToFocus = true;
                        Log.d(TAG, "  [CENTER MATCH] " + id);
                    }

                    // ✅ FIX: Any corner of focusBounds inside elBounds (reverse containment)
                    if (!belongsToFocus) {
                        if (elBounds.contains(focusBounds.centerX(), focusBounds.centerY())) {
                            belongsToFocus = true;
                            Log.d(TAG, "  [REVERSE CENTER MATCH] " + id);
                        }
                    }

                    // ✅ FIX: Lower overlap threshold to 5%
                    if (!belongsToFocus) {
                        RectF intersection = new RectF(elBounds);
                        if (intersection.intersect(focusBounds)) {
                            float overlap   = intersection.width() * intersection.height();
                            float elArea    = elBounds.width() * elBounds.height();
                            float focusArea = focusBounds.width() * focusBounds.height();
                            // ✅ Check against BOTH element area AND focus area
                            float overlapRatioEl    = elArea    > 0 ? (overlap / elArea)    : 0;
                            float overlapRatioFocus = focusArea > 0 ? (overlap / focusArea) : 0;
                            if (overlapRatioEl > 0.05f || overlapRatioFocus > 0.3f) {
                                belongsToFocus = true;
                                Log.d(TAG, "  [OVERLAP MATCH] " + id
                                        + " overlapEl%=" + (overlapRatioEl * 100)
                                        + " overlapFocus%=" + (overlapRatioFocus * 100));
                            }
                        }
                    }
                }
            }
            if (belongsToFocus) {
                matched++;
                String saved = el.getAttribute("data-orig-display");
                if ("__visible__".equals(saved)) el.removeAttribute("style");
                else el.setAttribute("style", saved);
            } else {
                dimmed++;
                el.setAttribute("style", "opacity:0.15;");
            }
        }

        Log.d(TAG, "dimFurnitureOutsideArea done: matched=" + matched + " dimmed=" + dimmed);
    }
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

            // ✅ Only named <g> sub-groups
            String tag = el.getTagName().toLowerCase().replace("svg:", "");
            if (!"g".equals(tag)) continue;

            if (el.hasAttribute("data-orig-display")) {
                String saved = el.getAttribute("data-orig-display");
                if ("__visible__".equals(saved)) el.removeAttribute("style");
                else el.setAttribute("style", saved);
                el.removeAttribute("data-orig-display");
            }
        }
    }
    // ── Walls ─────────────────────────────────────────────────────────────

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
                if (areaBounds.contains(doorBounds.centerX(), doorBounds.centerY()))
                    return true;
                RectF intersection = new RectF(doorBounds);
                if (intersection.intersect(areaBounds)) {
                    float overlap  = intersection.width() * intersection.height();
                    float doorArea = doorBounds.width() * doorBounds.height();
                    if (doorArea > 0 && (overlap / doorArea) > 0.3f) return true;
                }
            }
        }
        return false;
    }
    public void clearDeviceSnapshot(Element iconGroup) {
        NodeList children = iconGroup.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (!(child instanceof Element)) continue;
            Element childEl = (Element) child;
            if (!"rect".equals(parser.normalizeTag(childEl.getTagName()))) continue;

            int key = System.identityHashCode(childEl);
            originalIconFillMap.remove(key);
            originalIconFillInStyle.remove(key);

            // Re-snapshot the ORIGINAL from SVG source (not current modified state)
            reSnapshotIconRectFill(childEl);
            return;
        }
    }

    private void reSnapshotIconRectFill(Element rectEl) {
        int key = System.identityHashCode(rectEl);

        // Read from data-original-fill if we stored it earlier
        String original = rectEl.getAttribute("data-original-fill");
        if (original != null && !original.isEmpty()) {
            originalIconFillMap.put(key, original);
            originalIconFillInStyle.put(key, false);
            return;
        }

        // Fallback: re-parse from attribute
        String fill = rectEl.getAttribute("fill");
        if (fill != null && !fill.isEmpty()) {
            originalIconFillMap.put(key, fill);
            originalIconFillInStyle.put(key, false);
        }
    }
    public void forceResnapshotAllDevices(Map<String, DeviceInfo> deviceMap) {
        for (DeviceInfo info : deviceMap.values()) {
            // Clear existing snapshots for this device
            NodeList children = info.element.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node child = children.item(i);
                if (!(child instanceof Element)) continue;
                Element childEl = (Element) child;
                if ("rect".equals(parser.normalizeTag(childEl.getTagName()))) {
                    int key = System.identityHashCode(childEl);

                    // Restore from data-original-fill attribute
                    String original = childEl.getAttribute("data-original-fill");
                    if (original != null && !original.isEmpty()) {
                        originalIconFillMap.put(key, original);
                        originalIconFillInStyle.put(key, false);

                        // Also restore the actual fill
                        childEl.setAttribute("fill", original);
                    }
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  GETTERS
    // ══════════════════════════════════════════════════════════════════════

    public String getDimmedAreaId() { return dimmedAreaId; }
}