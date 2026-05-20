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

/**
 * Manages all SVG color/visibility changes for the NEW format:
 *
 *  Technician_Layer  → icon &lt;g&gt; groups, shown by default
 *  User_Layer        → physical node elements, hidden by default
 *
 * Visibility rules:
 *   ┌─────────────────────────────────────────────────────────────────┐
 *   │ State              │ Technician_Layer icon │ User_Layer element │
 *   ├─────────────────────────────────────────────────────────────────┤
 *   │ Not provisioned    │ original color        │ hidden             │
 *   │ Selected (unprov.) │ COLOR_SELECTED (red)  │ hidden             │
 *   │ Provisioned        │ transparent (hidden)  │ visible (active)   │
 *   └─────────────────────────────────────────────────────────────────┘
 */
public class SvgColorManager {

    private static final String TAG = "SvgColorManager";

    // ── Icon color constants ──────────────────────────────────────────────
    public static final String COLOR_SELECTED      = "#ff0000";
    public static final String COLOR_DEVICE_ACTIVE = "#ffbb00";
    public static final String COLOR_TRANSPARENT   = "transparent";

    // ── Area overlay styles ───────────────────────────────────────────────
    private static final String STYLE_AREA_DEFAULT =
            "fill:none;stroke:white;stroke-miterlimit:10;stroke-width:3px;";
    private static final String STYLE_AREA_DIM =
            "fill:#000000;fill-opacity:0.72;stroke:#333333;"
                    + "stroke-width:1px;stroke-miterlimit:10;";
    private static final String STYLE_AREA_FOCUSED =
            "fill:none;stroke:none;stroke-miterlimit:10;";

    // ── User_Layer display constants ──────────────────────────────────────
    /** Style applied to a User_Layer element when it should be VISIBLE */
    private static final String STYLE_USER_VISIBLE = "";   // remove style → use SVG default
    /** Style applied to a User_Layer element when it should be HIDDEN */
    private static final String STYLE_USER_HIDDEN  = "display:none;";

    // ── Dependencies ──────────────────────────────────────────────────────
    private Document   svgDocument;
    private SvgParsers parser;

    // ── Snapshot maps ─────────────────────────────────────────────────────
    /**
     * Original fill of the first &lt;rect&gt; inside each Technician_Layer icon.
     * Key = System.identityHashCode(rectElement)
     */
    private final Map<Integer, String>  originalIconFillMap     = new HashMap<>();
    /**
     * True  → fill was inside the style attribute
     * False → fill was a standalone attribute
     */
    private final Map<Integer, Boolean> originalIconFillInStyle = new HashMap<>();

    /** Original style strings of selection_layer elements, keyed by area id */
    private final Map<String, String>   originalAreaStyles      = new HashMap<>();

    /** Currently focused area id (for dim logic) */
    private String dimmedAreaId = null;

    // ── User_Layer element map ────────────────────────────────────────────
    /**
     * technician-icon-id  →  corresponding User_Layer Element.
     * Populated by init() via SvgParsers.parseUserLayer().
     */
    private Map<String, Element> userLayerMap = new HashMap<>();

    // ══════════════════════════════════════════════════════════════════════
    //  INITIALISATION
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Call every time a new SVG is loaded.
     *
     * 1. Snapshot original Technician_Layer icon colors.
     * 2. Parse User_Layer element map.
     * 3. Hide ALL User_Layer elements (default hidden state).
     */
    public void init(Document document,
                     SvgParsers svgParser,
                     Map<String, DeviceInfo> deviceMap) {
        this.svgDocument = document;
        this.parser      = svgParser;

        originalIconFillMap.clear();
        originalIconFillInStyle.clear();
        originalAreaStyles.clear();
        dimmedAreaId = null;

        // 1. Snapshot Technician_Layer icon fills
        for (DeviceInfo info : deviceMap.values())
            snapshotIconRectFill(info.element);

        // 2. Parse User_Layer
        userLayerMap = svgParser.parseUserLayer(document);

        // 3. Hide all User_Layer elements at startup
        hideAllUserLayerElements();

        Log.d(TAG, "init: " + deviceMap.size() + " tech icons, "
                + userLayerMap.size() + " user-layer elements");
    }

    // ── Snapshot helpers ──────────────────────────────────────────────────

    private void snapshotIconRectFill(Element iconGroup) {
        NodeList children = iconGroup.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (!(child instanceof Element)) continue;
            Element childEl = (Element) child;
            if (!"rect".equals(parser.normalizeTag(childEl.getTagName()))) continue;

            int key = System.identityHashCode(childEl);
            if (originalIconFillMap.containsKey(key)) return;   // already snapshotted

            // Try standalone fill attribute first
            String fillAttr = childEl.getAttribute("fill");
            if (fillAttr != null && !fillAttr.isEmpty()) {
                originalIconFillMap.put(key, fillAttr);
                originalIconFillInStyle.put(key, false);
                childEl.setAttribute("data-original-fill", fillAttr);
                return;
            }

            // Try fill inside style attribute
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

            // Fallback
            originalIconFillMap.put(key, COLOR_TRANSPARENT);
            originalIconFillInStyle.put(key, false);
            childEl.setAttribute("data-original-fill", COLOR_TRANSPARENT);
            return;
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
    //  TECHNICIAN_LAYER  — icon color API
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Set the fill of the first &lt;rect&gt; inside a Technician_Layer icon group.
     * Handles both standalone fill attribute and fill-inside-style.
     */
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
            return;   // only process first rect
        }
    }

    /**
     * Restore the first &lt;rect&gt; of an icon group to its original fill.
     */
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
    //  USER_LAYER  — show / hide individual elements
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Hide ALL User_Layer elements (called on init and on full reset).
     */
    public void hideAllUserLayerElements() {
        for (Element el : userLayerMap.values())
            setUserLayerElementVisible(el, false);
        Log.d(TAG, "hideAllUserLayerElements: " + userLayerMap.size() + " hidden");
    }

    /**
     * Show the User_Layer element that corresponds to the given
     * technician icon id.
     */
    public void showUserLayerElement(String techIconId) {
        Element el = userLayerMap.get(techIconId);
        if (el != null) {
            setUserLayerElementVisible(el, true);
        } else {
            Log.w(TAG, "showUserLayerElement: no user-layer element for " + techIconId);
        }
    }

    /**
     * Hide the User_Layer element that corresponds to the given
     * technician icon id.
     */
    public void hideUserLayerElement(String techIconId) {
        Element el = userLayerMap.get(techIconId);
        if (el != null) setUserLayerElementVisible(el, false);
    }

    private void setUserLayerElementVisible(Element el, boolean visible) {
        if (el == null) return;
        if (visible) {
            // Remove display:none — restore original visibility
            String style = el.getAttribute("style");
            if (style != null && style.contains("display:none")) {
                String cleaned = style.replace("display:none;", "")
                        .replace("display:none", "").trim();
                if (cleaned.isEmpty()) el.removeAttribute("style");
                else el.setAttribute("style", cleaned);
            }
        } else {
            // Apply display:none
            String style = el.getAttribute("style");
            if (style == null || !style.contains("display:none")) {
                String newStyle = (style != null && !style.isEmpty())
                        ? "display:none;" + style : "display:none;";
                el.setAttribute("style", newStyle);
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  FULL COLOR REFRESH
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Refresh all icon colors and User_Layer visibility based on current state.
     *
     * Rules:
     *  - Provisioned icon     → tech icon transparent, user-layer element visible
     *  - Selected (unprovis.) → tech icon red, user-layer element hidden
     *  - Normal (unprovis.)   → tech icon original color, user-layer element hidden
     *  - Area filter active   → icons outside the focused area transparent
     */
    public void refreshAllColors(Map<String, DeviceInfo> deviceMap,
                                 Set<String>             provisionedIds,
                                 String                  selectedDeviceId,
                                 String                  areaFilterId) {
        if (deviceMap.isEmpty()) return;

        // Start by hiding all user-layer elements
        hideAllUserLayerElements();

        for (Map.Entry<String, DeviceInfo> entry : deviceMap.entrySet()) {
            String     id   = entry.getKey();
            DeviceInfo info = entry.getValue();

            // Area filter — hide icons outside focused area
            if (areaFilterId != null
                    && !areaFilterId.equals(info.areaId)) {
                applyColorToIconGroup(info.element, COLOR_TRANSPARENT);
                continue;
            }

            boolean provisioned = provisionedIds != null
                    && provisionedIds.contains(id.trim().toLowerCase());

            if (provisioned) {
                // Hide the tech icon, show the user-layer counterpart
                applyColorToIconGroup(info.element, COLOR_TRANSPARENT);
                showUserLayerElement(id);

            } else if (id.equals(selectedDeviceId)) {
                // Highlight the tech icon
                applyColorToIconGroup(info.element, COLOR_SELECTED);

            } else {
                // Restore tech icon to its original color
                restoreIconGroupColor(info.element);
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  AREA DIM LOGIC
    // ══════════════════════════════════════════════════════════════════════

    public void dimOtherAreas(String              focusedAreaId,
                              Map<String,Element> selectionLayerElements,
                              Map<String,RectF>   selectionLayerBounds,
                              RectF               focusedAreaBounds) {
        if (focusedAreaId == null) {
            restoreAllAreas(selectionLayerElements, selectionLayerBounds);
            return;
        }

        restoreFurnitureVisibility();
        setWallsOpacity(false);
        restoreAllDoors();
        dimmedAreaId = focusedAreaId;

        boolean hasSelectionLayer = !selectionLayerElements.isEmpty();
        if (hasSelectionLayer) {
            for (Map.Entry<String, Element> entry : selectionLayerElements.entrySet()) {
                String  areaId = entry.getKey();
                Element areaEl = entry.getValue();
                if (!originalAreaStyles.containsKey(areaId)) {
                    String orig = areaEl.getAttribute("style");
                    originalAreaStyles.put(areaId,
                            (orig==null||orig.isEmpty())
                                    ? STYLE_AREA_DEFAULT : orig);
                }
                areaEl.setAttribute("style",
                        areaId.equals(focusedAreaId)
                                ? STYLE_AREA_FOCUSED : STYLE_AREA_DIM);
            }
            setWallsOpacity(true);
        }

        dimFurnitureOutsideArea(focusedAreaId, focusedAreaBounds);
        RectF areaBounds = selectionLayerBounds.get(focusedAreaId);
        if (areaBounds != null) highlightDoorsInArea(focusedAreaId, areaBounds);
    }

    public void restoreAllAreas(Map<String,Element> selectionLayerElements,
                                Map<String,RectF>   selectionLayerBounds) {
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

        // In new SVG format, Furniture is nested inside the room group.
        // Search for it globally.
        Element furnitureGroup = findFurnitureGroup();
        if (furnitureGroup == null) {
            Log.w(TAG, "dimFurnitureOutsideArea: Furniture group not found");
            return;
        }

        restoreFurnitureVisibility();

        if (focusBounds == null && parser.selectionLayerBounds != null)
            focusBounds = parser.selectionLayerBounds.get(focusedAreaId);

        if (focusBounds == null) {
            Log.w(TAG, "dimFurnitureOutsideArea: no bounds for " + focusedAreaId);
            return;
        }

        if (!furnitureGroup.hasAttribute("data-orig-group-style")) {
            String gs = furnitureGroup.getAttribute("style");
            furnitureGroup.setAttribute("data-orig-group-style",
                    (gs!=null&&!gs.isEmpty()) ? gs : "__visible__");
        }
        furnitureGroup.removeAttribute("style");

        NodeList children = furnitureGroup.getChildNodes();
        int matched=0, dimmed=0;

        for (int i=0; i<children.getLength(); i++) {
            Node child = children.item(i);
            if (!(child instanceof Element)) continue;
            Element el = (Element) child;
            if (!"g".equals(el.getTagName().toLowerCase().replace("svg:","")))
                continue;
            String id = el.getAttribute("id");
            if (id==null||id.isEmpty()) continue;

            if (!el.hasAttribute("data-orig-display")) {
                String orig = el.getAttribute("style");
                el.setAttribute("data-orig-display",
                        (orig!=null&&!orig.isEmpty()) ? orig : "__visible__");
            }

            boolean belongs = parser.isFuzzyMatch(
                    parser.normalize(id), parser.normalize(focusedAreaId));

            if (!belongs) {
                RectF elBounds = parser.computeBounds(el);
                if (elBounds!=null && !elBounds.isEmpty()) {
                    if (focusBounds.contains(elBounds.centerX(),elBounds.centerY())) {
                        belongs = true;
                    }
                    if (!belongs && elBounds.contains(
                            focusBounds.centerX(), focusBounds.centerY())) {
                        belongs = true;
                    }
                    if (!belongs) {
                        RectF inter = new RectF(elBounds);
                        if (inter.intersect(focusBounds)) {
                            float overlap   = inter.width()*inter.height();
                            float elArea    = elBounds.width()*elBounds.height();
                            float focusArea = focusBounds.width()*focusBounds.height();
                            float ratioEl   = elArea>0?(overlap/elArea):0;
                            float ratioFocus= focusArea>0?(overlap/focusArea):0;
                            if (ratioEl>0.05f || ratioFocus>0.3f) belongs=true;
                        }
                    }
                }
            }

            if (belongs) {
                matched++;
                String saved = el.getAttribute("data-orig-display");
                if ("__visible__".equals(saved)) el.removeAttribute("style");
                else el.setAttribute("style", saved);
            } else {
                dimmed++;
                el.setAttribute("style","opacity:0.15;");
            }
        }
        Log.d(TAG,"dimFurniture: matched="+matched+" dimmed="+dimmed);
    }

    private void restoreFurnitureVisibility() {
        Element furnitureGroup = findFurnitureGroup();
        if (furnitureGroup == null) return;

        if (furnitureGroup.hasAttribute("data-orig-group-style")) {
            String saved = furnitureGroup.getAttribute("data-orig-group-style");
            if ("__visible__".equals(saved)) furnitureGroup.removeAttribute("style");
            else furnitureGroup.setAttribute("style", saved);
            furnitureGroup.removeAttribute("data-orig-group-style");
        }

        NodeList children = furnitureGroup.getChildNodes();
        for (int i=0; i<children.getLength(); i++) {
            Node child = children.item(i);
            if (!(child instanceof Element)) continue;
            Element el = (Element) child;
            if (!"g".equals(el.getTagName().toLowerCase().replace("svg:","")))
                continue;
            if (el.hasAttribute("data-orig-display")) {
                String saved = el.getAttribute("data-orig-display");
                if ("__visible__".equals(saved)) el.removeAttribute("style");
                else el.setAttribute("style", saved);
                el.removeAttribute("data-orig-display");
            }
        }
    }

    /**
     * Find the Furniture group. In the new SVG it may be a direct child of
     * a room group (e.g. &lt;g id="Furniture"&gt; inside Master_Bedroom_MBDR)
     * or at the top level. Search recursively.
     */
    private Element findFurnitureGroup() {
        if (svgDocument == null) return null;
        return parser.findElementById(
                svgDocument.getDocumentElement(), "Furniture");
    }

    // ── Walls ─────────────────────────────────────────────────────────────

    private void setWallsOpacity(boolean dim) {
        if (svgDocument == null) return;
        Element walls = parser.findElementById(
                svgDocument.getDocumentElement(), "Walls");
        if (walls == null) return;

        if (dim) {
            if (!walls.hasAttribute("data-orig-walls")) {
                String s = walls.getAttribute("style");
                walls.setAttribute("data-orig-walls",
                        (s!=null&&!s.isEmpty()) ? s : "__visible__");
            }
            walls.setAttribute("style","opacity:0.25;");
        } else {
            if (walls.hasAttribute("data-orig-walls")) {
                String saved = walls.getAttribute("data-orig-walls");
                if ("__visible__".equals(saved)) walls.removeAttribute("style");
                else walls.setAttribute("style", saved);
                walls.removeAttribute("data-orig-walls");
            }
        }
    }

    // ── Doors ─────────────────────────────────────────────────────────────

    public void highlightDoorsInArea(String areaId, RectF areaBounds) {
        if (svgDocument==null||areaId==null) return;
        restoreAllDoors();
        if (areaBounds==null) return;

        Element furnitureGroup = findFurnitureGroup();
        if (furnitureGroup==null) return;

        List<Element> doorElements = collectAllDoorElements(furnitureGroup);
        String normAreaId = parser.normalize(areaId);

        for (Element doorEl : doorElements) {
            if (!doorEl.hasAttribute("data-orig-door-style")) {
                String orig = doorEl.getAttribute("style");
                doorEl.setAttribute("data-orig-door-style",
                        (orig!=null&&!orig.isEmpty()) ? orig : "");
            }
            if (isDoorBelongingToArea(doorEl, normAreaId, areaBounds))
                applyDoorHighlight(doorEl);
            else
                restoreDoorStyle(doorEl);
        }
    }

    public void restoreAllDoors() {
        if (svgDocument==null) return;
        Element furnitureGroup = findFurnitureGroup();
        if (furnitureGroup==null) return;

        NodeList children = furnitureGroup.getChildNodes();
        for (int i=0; i<children.getLength(); i++) {
            Node child = children.item(i);
            if (!(child instanceof Element)) continue;
            Element el = (Element) child;
            String id = el.getAttribute("id");
            if (id!=null && id.toLowerCase().contains("door")
                    && el.hasAttribute("data-orig-door-style"))
                restoreDoorStyle(el);
        }
    }

    private void applyDoorHighlight(Element doorEl) {
        String tag = parser.normalizeTag(doorEl.getTagName());
        if ("polyline".equals(tag)||"path".equals(tag)||"line".equals(tag))
            doorEl.setAttribute("style","stroke:#ff0000;stroke-width:2.5px;fill:none;");
        else
            doorEl.setAttribute("style","fill:#ff0000;stroke:#cc0000;stroke-width:1px;");
    }

    private void restoreDoorStyle(Element doorEl) {
        if (!doorEl.hasAttribute("data-orig-door-style")) return;
        String saved = doorEl.getAttribute("data-orig-door-style");
        if (saved!=null&&!saved.isEmpty()) doorEl.setAttribute("style",saved);
        else doorEl.removeAttribute("style");
        doorEl.removeAttribute("data-orig-door-style");
    }

    private List<Element> collectAllDoorElements(Element parent) {
        List<Element> doors = new ArrayList<>();
        String id = parent.getAttribute("id");
        if (id!=null&&id.toLowerCase().contains("door")) doors.add(parent);
        NodeList children = parent.getChildNodes();
        for (int i=0; i<children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element)
                doors.addAll(collectAllDoorElements((Element) child));
        }
        return doors;
    }

    private boolean isDoorBelongingToArea(Element doorEl,
                                          String  normAreaId,
                                          RectF   areaBounds) {
        String dataArea = doorEl.getAttribute("data-area");
        if (dataArea!=null&&!dataArea.isEmpty()
                && parser.normalize(dataArea).equals(normAreaId)) return true;

        Node parent = doorEl.getParentNode();
        while (parent instanceof Element) {
            String parentId = ((Element)parent).getAttribute("id");
            if (parentId!=null&&!parentId.isEmpty()
                    && parser.isFuzzyMatch(parser.normalize(parentId),normAreaId))
                return true;
            parent = parent.getParentNode();
        }

        String doorId = doorEl.getAttribute("id");
        if (doorId!=null && parser.isFuzzyMatch(parser.normalize(doorId),normAreaId))
            return true;

        if (areaBounds!=null) {
            RectF doorBounds = parser.computeBounds(doorEl);
            if (doorBounds!=null&&!doorBounds.isEmpty()) {
                if (areaBounds.contains(doorBounds.centerX(),doorBounds.centerY()))
                    return true;
                RectF inter = new RectF(doorBounds);
                if (inter.intersect(areaBounds)) {
                    float overlap  = inter.width()*inter.height();
                    float doorArea = doorBounds.width()*doorBounds.height();
                    if (doorArea>0&&(overlap/doorArea)>0.3f) return true;
                }
            }
        }
        return false;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  SNAPSHOT RESET HELPERS
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Re-snapshot all device icons from the data-original-fill attribute.
     * Call this on resume to handle any state drift.
     */
    public void forceResnapshotAllDevices(Map<String, DeviceInfo> deviceMap) {
        for (DeviceInfo info : deviceMap.values()) {
            NodeList children = info.element.getChildNodes();
            for (int i=0; i<children.getLength(); i++) {
                Node child = children.item(i);
                if (!(child instanceof Element)) continue;
                Element childEl = (Element) child;
                if (!"rect".equals(parser.normalizeTag(childEl.getTagName())))
                    continue;

                int key = System.identityHashCode(childEl);
                String original = childEl.getAttribute("data-original-fill");
                if (original!=null&&!original.isEmpty()) {
                    originalIconFillMap.put(key, original);
                    originalIconFillInStyle.put(key, false);
                    childEl.setAttribute("fill", original);
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  GETTERS
    // ══════════════════════════════════════════════════════════════════════

    public String getDimmedAreaId() { return dimmedAreaId; }

    /** Returns the User_Layer element map (techIconId → Element). */
    public Map<String, Element> getUserLayerMap() { return userLayerMap; }
}