package no.nordicsemi.android.swaromapmesh.swajaui;

import android.content.ContentResolver;
import android.net.Uri;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

public class SvgParserList {

    private static final String TAG = "SvgParser";

    // ── Called by ImportMap_Activity ──────────────────────────────────────────
    public static ArrayList<String> parseAreaIds(ContentResolver resolver, Uri uri) {
        ArrayList<String> flat = new ArrayList<>();
        LinkedHashMap<String, List<String>> map = parseFloorAreas(resolver, uri);
        for (Map.Entry<String, List<String>> entry : map.entrySet()) {
            flat.add(entry.getKey());       // floor/area id
            flat.addAll(entry.getValue());  // device ids inside
        }
        return flat;
    }

    // ── Called by AreaListActivity (floor-grouped or area-grouped) ────────────
    public static LinkedHashMap<String, List<String>> parseFloorAreas(
            ContentResolver resolver, Uri uri) {

        LinkedHashMap<String, List<String>> result = new LinkedHashMap<>();

        try (InputStream is = resolver.openInputStream(uri)) {
            if (is == null) return result;

            Document doc = parseDocument(is);
            if (doc == null) return result;

            Element root = doc.getDocumentElement();

            // First, try to find floors structure (groups with Walls, furniture, Lights, Icons)
            boolean hasFloors = false;
            NodeList rootChildren = root.getChildNodes();

            for (int i = 0; i < rootChildren.getLength(); i++) {
                Node child = rootChildren.item(i);
                if (!(child instanceof Element)) continue;

                Element childEl = (Element) child;
                String tag = childEl.getTagName().toLowerCase();
                if (tag.contains(":")) tag = tag.substring(tag.indexOf(':') + 1);

                if ("g".equals(tag)) {
                    String id = childEl.getAttribute("id");
                    if (id != null && !id.isEmpty() && isFloorGroup(childEl)) {
                        hasFloors = true;
                        break;
                    }
                }
            }

            if (hasFloors) {
                // Case 1: Multiple floors - parse floor-wise
                android.util.Log.d(TAG, "Detected multiple floors structure");
                parseFloorsStructure(root, result);
            } else {
                // Case 2: Single floor / area-based structure - parse areas directly
                android.util.Log.d(TAG, "Detected single floor/area structure");
                parseAreasStructure(root, result);
            }

        } catch (Exception e) {
            e.printStackTrace();
        }

        return result;
    }

    /**
     * Check if a group is a floor group (contains Walls, furniture, Lights, or Icons)
     */
    private static boolean isFloorGroup(Element group) {
        NodeList children = group.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element) {
                Element el = (Element) child;
                String tag = el.getTagName().toLowerCase();
                if (tag.contains(":")) tag = tag.substring(tag.indexOf(':') + 1);
                if ("g".equals(tag)) {
                    String id = el.getAttribute("id");
                    if (id != null && (id.equals("Walls") || id.equals("furniture") ||
                            id.equals("Lights") || id.equals("Icons") ||
                            id.contains("Walls") || id.contains("Icons"))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Parse multiple floors structure
     */
    private static void parseFloorsStructure(Element root, LinkedHashMap<String, List<String>> result) {
        NodeList rootChildren = root.getChildNodes();

        for (int i = 0; i < rootChildren.getLength(); i++) {
            Node child = rootChildren.item(i);
            if (!(child instanceof Element)) continue;

            Element childEl = (Element) child;
            String tag = childEl.getTagName().toLowerCase();
            if (tag.contains(":")) tag = tag.substring(tag.indexOf(':') + 1);

            if ("g".equals(tag)) {
                String floorId = childEl.getAttribute("id");
                if (floorId == null || floorId.isEmpty()) continue;

                // Find Icons group inside this floor
                Element iconsGroup = findElementById(childEl, "Icons");
                if (iconsGroup != null) {
                    List<String> areas = extractAreasFromIconsGroup(iconsGroup);
                    if (!areas.isEmpty()) {
                        result.put(floorId, areas);
                        android.util.Log.d(TAG, "Floor '" + floorId + "' has " + areas.size() + " areas");
                    }
                }
            }
        }
    }

    /**
     * Parse single floor/area structure (areas directly under Icons group)
     */
    private static void parseAreasStructure(Element root, LinkedHashMap<String, List<String>> result) {
        // Find Icons group
        Element iconsGroup = findElementById(root, "Icons");

        if (iconsGroup == null) {
            // Try to find any group that contains device rects
            android.util.Log.w(TAG, "No Icons group found, scanning for area groups");
            parseAreasFromRoot(root, result);
            return;
        }

        // Parse areas from Icons group
        NodeList areaNodes = iconsGroup.getChildNodes();

        for (int i = 0; i < areaNodes.getLength(); i++) {
            Node aNode = areaNodes.item(i);
            if (!(aNode instanceof Element)) continue;

            Element aEl = (Element) aNode;
            String aTag = aEl.getTagName().toLowerCase();
            if (aTag.contains(":")) aTag = aTag.substring(aTag.indexOf(':') + 1);
            if (!"g".equals(aTag)) continue;

            String areaId = aEl.getAttribute("id");
            if (areaId == null || areaId.isEmpty()) continue;

            // Skip known non-area groups
            if (areaId.equals("Relation") || areaId.equals("Devices") ||
                    areaId.equals("selection_layer") || areaId.equals("Light") ||
                    areaId.startsWith("Light") || areaId.equals("Icons")) {
                continue;
            }

            // Extract device IDs from this area
            List<String> deviceIds = extractDeviceIdsFromGroup(aEl);

            // Also add the area itself if it has no devices but has rect
            if (deviceIds.isEmpty() && hasDeviceRect(aEl)) {
                deviceIds.add(areaId);
            }

            if (!deviceIds.isEmpty()) {
                result.put(areaId, deviceIds);
                android.util.Log.d(TAG, "Area '" + areaId + "' has " + deviceIds.size() + " devices");
            } else {
                // Still add the area even without devices (for display)
                result.put(areaId, new ArrayList<>());
                android.util.Log.d(TAG, "Area '" + areaId + "' has no devices");
            }
        }

        // If no areas found, try scanning root for area groups
        if (result.isEmpty()) {
            parseAreasFromRoot(root, result);
        }
    }

    /**
     * Extract area IDs from Icons group (each child <g> is an area)
     */
    private static List<String> extractAreasFromIconsGroup(Element iconsGroup) {
        List<String> areas = new ArrayList<>();

        NodeList children = iconsGroup.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (!(child instanceof Element)) continue;

            Element el = (Element) child;
            String tag = el.getTagName().toLowerCase();
            if (tag.contains(":")) tag = tag.substring(tag.indexOf(':') + 1);

            if ("g".equals(tag)) {
                String id = el.getAttribute("id");
                if (id != null && !id.isEmpty() && !id.equals("Relation")) {
                    areas.add(id);
                }
            }
        }

        return areas;
    }

    /**
     * Extract device IDs from a group (look for rect elements)
     */
    private static List<String> extractDeviceIdsFromGroup(Element group) {
        List<String> deviceIds = new ArrayList<>();

        NodeList children = group.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (!(child instanceof Element)) continue;

            Element el = (Element) child;
            String tag = el.getTagName().toLowerCase();
            if (tag.contains(":")) tag = tag.substring(tag.indexOf(':') + 1);

            if ("g".equals(tag)) {
                String id = el.getAttribute("id");
                // Check if this group has a rect (device indicator)
                if (hasDeviceRect(el)) {
                    if (id != null && !id.isEmpty() && !deviceIds.contains(id)) {
                        deviceIds.add(id);
                    }
                }
                // Also check deeper
                deviceIds.addAll(extractDeviceIdsFromGroup(el));
            }
        }

        return deviceIds;
    }

    /**
     * Check if a group contains a rect (indicating it's a device)
     */
    private static boolean hasDeviceRect(Element group) {
        NodeList children = group.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element) {
                Element el = (Element) child;
                String tag = el.getTagName().toLowerCase();
                if (tag.contains(":")) tag = tag.substring(tag.indexOf(':') + 1);
                if ("rect".equals(tag)) {
                    return true;
                }
                if ("g".equals(tag) && hasDeviceRect(el)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Fallback: parse areas directly from root (for single-level structure)
     */
    private static void parseAreasFromRoot(Element root, LinkedHashMap<String, List<String>> result) {
        NodeList children = root.getChildNodes();

        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (!(child instanceof Element)) continue;

            Element el = (Element) child;
            String tag = el.getTagName().toLowerCase();
            if (tag.contains(":")) tag = tag.substring(tag.indexOf(':') + 1);

            if ("g".equals(tag)) {
                String id = el.getAttribute("id");
                if (id != null && !id.isEmpty() &&
                        !id.equals("Icons") && !id.equals("Devices") &&
                        !id.equals("Relation") && !id.equals("selection_layer") &&
                        !id.equals("Light") && !id.startsWith("Light")) {

                    // Check if this group contains device rects
                    List<String> deviceIds = extractDeviceIdsFromGroup(el);
                    if (!deviceIds.isEmpty()) {
                        result.put(id, deviceIds);
                        android.util.Log.d(TAG, "Root area '" + id + "' has " + deviceIds.size() + " devices");
                    } else if (hasDeviceRect(el)) {
                        result.put(id, new ArrayList<>());
                        android.util.Log.d(TAG, "Root area '" + id + "' (no devices found)");
                    }
                }
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static Element findElementById(Element parent, String targetId) {
        if (targetId.equals(parent.getAttribute("id"))) return parent;
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element) {
                Element found = findElementById((Element) child, targetId);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static Document parseDocument(InputStream inputStream) {
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
            e.printStackTrace();
            return null;
        }
    }
}