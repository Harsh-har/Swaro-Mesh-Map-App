package no.nordicsemi.android.swaromapmesh.swajaui;

import android.content.ContentResolver;
import android.net.Uri;
import android.util.Log;
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

    public static ArrayList<String> parseAreaIds(ContentResolver resolver, Uri uri) {
        ArrayList<String> flat = new ArrayList<>();
        LinkedHashMap<String, List<String>> map = parseFloorAreas(resolver, uri);
        for (Map.Entry<String, List<String>> entry : map.entrySet()) {
            flat.add(entry.getKey());
            flat.addAll(entry.getValue());
        }
        return flat;
    }

    public static LinkedHashMap<String, List<String>> parseFloorAreas(ContentResolver resolver, Uri uri) {
        LinkedHashMap<String, List<String>> result = new LinkedHashMap<>();
        try (InputStream is = resolver.openInputStream(uri)) {
            if (is == null) return result;
            Document doc = parseDocument(is);
            if (doc == null) return result;

            Element root = doc.getDocumentElement();
            
            // 1. Prioritize Technician Layer / Icons (Single Floor logic)
            Element techLayer = findElementByPossibleIds(root, "Technician Layer", "TechnicianLayer", "Icons");
            if (techLayer != null) {
                Log.d(TAG, "Parsing Technician Layer...");
                parseRoomsFromParent(techLayer, result);
            }

            // 2. Scan root for any groups that might be floors or rooms outside the tech layer
            NodeList rootChildren = root.getChildNodes();
            for (int i = 0; i < rootChildren.getLength(); i++) {
                Node node = rootChildren.item(i);
                if (!(node instanceof Element)) continue;
                Element el = (Element) node;
                String id = el.getAttribute("id");
                
                if (id == null || id.isEmpty() || isStructuralGroup(id)) continue;

                if (isFloorGroup(el)) {
                    Log.d(TAG, "Found potential floor: " + id);
                    parseFloor(el, result);
                } else if (!result.containsKey(id)) {
                    // It might be a room directly at the root
                    List<String> devices = extractDeviceIdsFromGroup(el);
                    if (!devices.isEmpty()) {
                        Log.d(TAG, "Found root room: " + id);
                        result.put(id, devices);
                    }
                }
            }
            
            Log.d(TAG, "Final Area Count: " + result.size());

        } catch (Exception e) {
            Log.e(TAG, "Error parsing SVG", e);
        }
        return result;
    }

    private static void parseFloor(Element floorEl, LinkedHashMap<String, List<String>> result) {
        String floorId = floorEl.getAttribute("id");
        LinkedHashMap<String, List<String>> rooms = new LinkedHashMap<>();
        
        // Inside a floor, look for Technician Layer first
        Element tech = findElementByPossibleIds(floorEl, "Technician Layer", "Icons");
        if (tech != null) {
            parseRoomsFromParent(tech, rooms);
        } else {
            // Otherwise scan all children
            parseRoomsFromParent(floorEl, rooms);
        }
        
        if (!rooms.isEmpty()) {
            result.put(floorId, new ArrayList<>(rooms.keySet()));
            result.putAll(rooms);
        }
    }

    private static void parseRoomsFromParent(Element parent, LinkedHashMap<String, List<String>> result) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node instanceof Element) {
                Element el = (Element) node;
                String tag = normalizeTag(el.getTagName());
                if ("g".equals(tag)) {
                    String id = el.getAttribute("id");
                    if (id != null && !id.isEmpty() && !isStructuralGroup(id)) {
                        List<String> devices = extractDeviceIdsFromGroup(el);
                        // Add if it has devices OR if it's a leaf group with a Swaro ID
                        if (!devices.isEmpty() || isSwaroId(id)) {
                            result.put(id, devices);
                            Log.d(TAG, "Room Identified: " + id + " (devices=" + devices.size() + ")");
                        }
                    }
                }
            }
        }
    }

    private static List<String> extractDeviceIdsFromGroup(Element group) {
        List<String> deviceIds = new ArrayList<>();
        NodeList children = group.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (!(child instanceof Element)) continue;
            Element el = (Element) child;
            String tag = normalizeTag(el.getTagName());
            String id = el.getAttribute("id");

            if ("g".equals(tag)) {
                if (isSwaroId(id)) {
                    deviceIds.add(id);
                } else {
                    deviceIds.addAll(extractDeviceIdsFromGroup(el));
                }
            } else if (isSwaroId(id)) {
                deviceIds.add(id);
            }
        }
        return deviceIds;
    }

    private static boolean isSwaroId(String id) {
        if (id == null || id.isEmpty()) return false;
        // Standard Swaro ID has at least 2 underscores: Area_Type_Index
        // e.g., KDR_CLF01_1
        int count = 0;
        for (char c : id.toCharArray()) if (c == '_') count++;
        return count >= 2;
    }

    private static boolean isStructuralGroup(String id) {
        if (id == null) return true;
        String low = id.toLowerCase().replace("_", " ").replace("-", " ");
        return low.contains("layer") || low.contains("walls") || low.contains("furniture") || 
               low.contains("background") || low.contains("selection") || 
               low.equals("icons") || low.equals("walls") || low.equals("furniture");
    }

    private static boolean isFloorGroup(Element group) {
        String id = group.getAttribute("id");
        if (id != null && (id.toLowerCase().contains("floor"))) return true;
        NodeList children = group.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element) {
                String cid = ((Element) children.item(i)).getAttribute("id");
                if (cid != null && (cid.equalsIgnoreCase("Walls") || cid.equalsIgnoreCase("Furniture"))) return true;
            }
        }
        return false;
    }

    private static Element findElementByPossibleIds(Element parent, String... ids) {
        for (String id : ids) {
            Element found = findElementById(parent, id);
            if (found != null) return found;
        }
        return null;
    }

    private static Element findElementById(Element parent, String targetId) {
        if (targetId.equals(parent.getAttribute("id"))) return parent;
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element) {
                Element found = findElementById((Element) children.item(i), targetId);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static String normalizeTag(String tag) {
        if (tag == null) return "";
        int colon = tag.indexOf(':');
        return (colon >= 0 ? tag.substring(colon + 1) : tag).toLowerCase();
    }

    private static Document parseDocument(InputStream is) {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(false);
            return dbf.newDocumentBuilder().parse(is);
        } catch (Exception e) {
            return null;
        }
    }
}
