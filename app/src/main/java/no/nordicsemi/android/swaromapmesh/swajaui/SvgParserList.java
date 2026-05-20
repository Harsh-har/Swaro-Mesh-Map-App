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
        for (Map.Entry<String, List<String>> entry : parseFloorAreas(resolver, uri).entrySet()) {
            flat.add(entry.getKey());
            flat.addAll(entry.getValue());
        }
        return flat;
    }

    // ── Called by AreaListActivity ────────────────────────────────────────────
    public static LinkedHashMap<String, List<String>> parseFloorAreas(
            ContentResolver resolver, Uri uri) {

        LinkedHashMap<String, List<String>> result = new LinkedHashMap<>();
        try (InputStream is = resolver.openInputStream(uri)) {
            if (is == null) return result;
            Document doc = parseDocument(is);
            if (doc == null) return result;
            parseAreas(doc.getDocumentElement(), result);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return result;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  CORE PARSING
    // ══════════════════════════════════════════════════════════════════════

    private static void parseAreas(Element root,
                                   LinkedHashMap<String, List<String>> result) {

        scanForAreas(root, result, 0);
    }

    private static void scanForAreas(Element parent,
                                     LinkedHashMap<String, List<String>> result, int depth) {

        if (depth > 3) return;

        NodeList children = parent.getChildNodes();
        android.util.Log.d(TAG, "Scanning at depth=" + depth
                + " parent='" + parent.getAttribute("id") + "'"
                + " children=" + children.getLength());

        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (!(child instanceof Element)) continue;
            Element el = (Element) child;
            if (!"g".equals(localTag(el))) continue;

            String id = el.getAttribute("id");
            if (id == null || id.isEmpty() || isStructural(id)) continue;

            // Technician_Layer* is direct child
            Element techLayer = findChildByIdPrefix(el, "Technician_Layer");
            if (techLayer != null) {
                List<String> devices = extractDevices(techLayer);
                result.put(id, devices);
                android.util.Log.d(TAG, "Area '" + id + "' → "
                        + devices.size() + " devices (depth=" + depth + ")");
            } else {
                // Technician_Layer not found
                scanForAreas(el, result, depth + 1);
            }
        }
    }
    // ══════════════════════════════════════════════════════════════════════
    //  DEVICE EXTRACTION
    // ══════════════════════════════════════════════════════════════════════

    private static List<String> extractDevices(Element techLayer) {
        List<String> devices = new ArrayList<>();
        NodeList children = techLayer.getChildNodes();

        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (!(child instanceof Element)) continue;
            Element el = (Element) child;
            if (!"g".equals(localTag(el))) continue;

            String id = el.getAttribute("id");
            if (id == null || id.isEmpty() || isStructural(id)) continue;

            if (hasDirectRect(el)) {
                devices.add(id);
                android.util.Log.d(TAG, "  Device: " + id);
            }
        }
        return devices;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  UTILITIES
    // ══════════════════════════════════════════════════════════════════════
    private static Element findChildByIdPrefix(Element parent, String idPrefix) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element) {
                Element el = (Element) child;
                String id = el.getAttribute("id");
                if (id != null && id.startsWith(idPrefix)) return el;
            }
        }
        return null;
    }

    /** True if group has at least one <rect> as direct child. */
    private static boolean hasDirectRect(Element group) {
        NodeList children = group.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element && "rect".equals(localTag((Element) child)))
                return true;
        }
        return false;
    }

    /** Groups that are never areas or devices. */
    private static boolean isStructural(String id) {
        switch (id) {
            case "Background":
            case "Walls":
            case "Furniture":
            case "User_Layer":
            case "Devices":
            case "selection_layer":
                return true;
            default:
                // Technician_Layer, Technician_Layer-2, Technician_Layer-3 sab skip
                return id.startsWith("Technician_Layer")
                        || id.startsWith("Furniture")
                        || id.startsWith("User_Layer")
                        || id.startsWith("Light");
        }
    }

    /** Strip XML namespace prefix and lowercase. */
    private static String localTag(Element el) {
        String tag = el.getTagName().toLowerCase();
        int colon = tag.indexOf(':');
        return colon >= 0 ? tag.substring(colon + 1) : tag;
    }

    private static Document parseDocument(InputStream is) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setValidating(false);
            try { factory.setFeature(
                    "http://xml.org/sax/features/external-general-entities", false);
            } catch (Exception ignored) {}
            try { factory.setFeature(
                    "http://xml.org/sax/features/external-parameter-entities", false);
            } catch (Exception ignored) {}
            try { factory.setFeature(
                    "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            } catch (Exception ignored) {}

            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setEntityResolver((pub, sys) ->
                    new org.xml.sax.InputSource(new java.io.StringReader("")));

            Document doc = builder.parse(is);
            doc.getDocumentElement().normalize();
            return doc;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}