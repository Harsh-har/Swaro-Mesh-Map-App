package no.nordicsemi.android.swaromapmesh.swajaui.Svg_Operations;

import android.graphics.RectF;
import android.util.Log;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import java.util.HashMap;
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
     * Toggles visibility of all map elements based on focus.
     * If focusedRoomGroupId is null, everything is shown (Background, Walls, etc).
     * If not null, only that room group is shown, everything else is hidden.
     */
    public void applyAreaFocus(String focusedRoomGroupId) {
        if (svgDocument == null) return;
        Element root = svgDocument.getDocumentElement();

        boolean resetMode = (focusedRoomGroupId == null);
        String target = resetMode ? null : focusedRoomGroupId.trim().toLowerCase();

        // Iterate through EVERY direct child of the SVG root
        NodeList children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (!(node instanceof Element)) continue;
            Element el = (Element) node;
            
            String id = el.getAttribute("id");
            if (id == null) id = "";
            String normalizedId = id.trim().toLowerCase();

            if (resetMode) {
                // Show everything
                setElementVisible(el, true);
            } else {
                // Determine if this element should be shown
                boolean isTarget = normalizedId.equals(target) 
                        || (normalizedId.contains(target) && !target.isEmpty())
                        || (target != null && target.contains(normalizedId) && !normalizedId.isEmpty());

                // If it's a known structural group (Background, Walls, Other) and not the target, hide it
                if (id.equals("Background") || id.equals("Walls") || id.equals("Other")) {
                    setElementVisible(el, isTarget);
                } else if (!id.isEmpty()) {
                    // It's a room or something else with an ID
                    setElementVisible(el, isTarget);
                } else {
                    // It's an anonymous element (path, rect, etc) at the top level
                    // Hide it to keep the focus clean
                    setElementVisible(el, false);
                }
            }
        }

        // 3. Selection Layer handling
        if (resetMode) {
            restoreAllSelectionLayers();
        } else {
            // Hide selection layers inside the focused room too so they don't dim the room
            hideAllSelectionLayers();
        }

        Log.d(TAG, "applyAreaFocus: focusedRoomGroupId=" + (resetMode ? "RESET" : focusedRoomGroupId));
    }

    // ══════════════════════════════════════════════════════════════════════
    //  SELECTION LAYER  — hide / restore
    // ══════════════════════════════════════════════════════════════════════

    public void hideAllSelectionLayers() {
        if (svgDocument == null) return;
        toggleSelectionLayersRecursive(svgDocument.getDocumentElement(), false);
    }

    public void restoreAllSelectionLayers() {
        if (svgDocument == null) return;
        toggleSelectionLayersRecursive(svgDocument.getDocumentElement(), true);
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
                // Match common selection layer IDs
                if (lower.equals("selection") || lower.startsWith("selection-") || lower.equals("selection_layer")) {
                    setElementVisible(el, visible);
                    continue; // No need to go deeper into a selection group usually
                }
            }
            toggleSelectionLayersRecursive(el, visible);
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
    //  FULL COLOR REFRESH
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

            // If an area filter is active, we don't necessarily hide the icon group here 
            // (visibility is handled by applyAreaFocus), but we skip processing if not matching.
            if (areaFilterId != null && !areaFilterId.equals(info.areaId)) {
                continue;
            }

            boolean provisioned = provisionedIds != null
                    && provisionedIds.contains(id.trim());

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
    //  GENERIC ELEMENT VISIBILITY
    // ══════════════════════════════════════════════════════════════════════

    private void setElementVisible(Element el, boolean visible) {
        if (el == null) return;
        if (visible) {
            el.removeAttribute("display");
            String style = el.getAttribute("style");
            if (style != null && style.contains("display:none")) {
                String cleaned = style.replace("display:none;", "")
                        .replace("display:none", "").trim();
                if (cleaned.isEmpty()) el.removeAttribute("style");
                else el.setAttribute("style", cleaned);
            }
        } else {
            el.setAttribute("display", "none");
            String style = el.getAttribute("style");
            if (style == null || !style.contains("display:none")) {
                String newStyle = (style != null && !style.isEmpty())
                        ? "display:none;" + style : "display:none;";
                el.setAttribute("style", newStyle);
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  LEGACY COMPAT (To keep code compilable)
    // ══════════════════════════════════════════════════════════════════════

    public void dimOtherAreas(String id, Map m1, Map m2, RectF r) {}
    public void restoreAllAreas(Map m1, Map m2) {}
    public void restoreAllFurniture() {}
    public void forceResnapshotAllDevices(Map m) {}
}
