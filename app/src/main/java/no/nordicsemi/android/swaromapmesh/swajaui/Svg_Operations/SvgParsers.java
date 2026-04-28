package no.nordicsemi.android.swaromapmesh.swajaui.Svg_Operations;

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

    // ViewBox values — updated by parseViewBox()
    public float vbX = 0f, vbY = 0f, vbW = 1200f, vbH = 640f;

    // Area ID → list of icon IDs in that area
    public final Map<String, List<String>> areaMap = new LinkedHashMap<>();

    // Area ID → selection_layer Element reference (for dim logic)
    public final Map<String, Element> selectionLayerElements = new HashMap<>();

    // Area ID → bounding box in SVG coordinates
    public final Map<String, RectF> selectionLayerBounds = new HashMap<>();

    // ══════════════════════════════════════════════════════════════════════
    //  DOCUMENT PARSING
    // ══════════════════════════════════════════════════════════════════════

    public Document parseDocument(InputStream inputStream) {
        if (inputStream == null) return null;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setValidating(false);
            try {
                factory.setFeature(
                        "http://xml.org/sax/features/external-general-entities", false);
                factory.setFeature(
                        "http://xml.org/sax/features/external-parameter-entities", false);
                factory.setFeature(
                        "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            } catch (Exception ignored) {}
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setEntityResolver(
                    (pub, sys) -> new org.xml.sax.InputSource(new java.io.StringReader("")));
            Document doc = builder.parse(inputStream);
            doc.getDocumentElement().normalize();
            return doc;
        } catch (Exception e) {
            Log.e(TAG, "Error parsing XML document", e);
            return null;
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  VIEWBOX
    // ══════════════════════════════════════════════════════════════════════

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

    // ══════════════════════════════════════════════════════════════════════
    //  DEVICE EXTRACTION  (<g id="Icons">)
    // ══════════════════════════════════════════════════════════════════════

    public Map<String, DeviceInfo> extractDevices(Document document) {
        Map<String, DeviceInfo> devices = new LinkedHashMap<>();
        areaMap.clear();
        if (document == null) return devices;
        try {
            Element iconsGroup = findElementById(document.getDocumentElement(), "Icons");
            if (iconsGroup == null) {
                scanForLeafIcons(document.getDocumentElement(), devices, null);
                return devices;
            }
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
                scanForLeafIcons(aEl, devices, areaId);

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

    private void scanForLeafIcons(Element el, Map<String, DeviceInfo> devices, String areaId) {
        String id = el.getAttribute("id");
        if (!id.isEmpty() && hasDirectRectChild(el) && !hasDirectGChild(el)) {
            processDeviceElement(el, devices, areaId);
            return;
        }
        NodeList children = el.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element) {
                String tag = normalizeTag(((Element) child).getTagName());
                if ("g".equals(tag))
                    scanForLeafIcons((Element) child, devices, areaId);
            }
        }
    }

    private void processDeviceElement(Element el, Map<String, DeviceInfo> devices, String areaId) {
        String id = el.getAttribute("id");
        if (id == null || id.isEmpty() || devices.containsKey(id)) return;
        RectF bounds = computeBounds(el);
        if (bounds == null || bounds.isEmpty()) return;
        String elementId = extractElementId(el);
        if (elementId == null) elementId = id;
        devices.put(id, new DeviceInfo(id, el, bounds, elementId, areaId));
    }

    private boolean hasDirectRectChild(Element el) {
        NodeList children = el.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element) {
                if ("rect".equals(normalizeTag(((Element) child).getTagName()))) return true;
            }
        }
        return false;
    }

    private boolean hasDirectGChild(Element el) {
        NodeList children = el.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element) {
                if ("g".equals(normalizeTag(((Element) child).getTagName()))) return true;
            }
        }
        return false;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  RELATION PARSING  (<g id="Relation">)
    // ══════════════════════════════════════════════════════════════════════

    public Map<String, Set<String>> parseRelations(Document document) {
        Map<String, Set<String>> result = new HashMap<>();
        if (document == null) return result;
        Element rg = findElementById(document.getDocumentElement(), "Relation");
        if (rg == null) return result;
        String rawText = rg.getTextContent();
        if (rawText == null || rawText.trim().isEmpty()) return result;
        Pattern p = Pattern.compile(
                "\\(\\s*([\\w:.\\-]+(?:\\s+[\\w:.\\-]+)*)\\s+([\\w:.\\-]+)\\s*\\)");
        Matcher m = p.matcher(rawText);
        while (m.find()) {
            String iconId   = m.group(1).trim();
            String deviceId = m.group(2).trim();
            if (!iconId.isEmpty() && !deviceId.isEmpty())
                result.computeIfAbsent(iconId, k -> new HashSet<>()).add(deviceId);
        }
        return result;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  SELECTION LAYER PARSING  (<g id="selection_layer">)
    // ══════════════════════════════════════════════════════════════════════

    public void parseSelectionLayer(Document document) {
        selectionLayerBounds.clear();
        selectionLayerElements.clear();
        if (document == null) return;

        Element selLayer = findElementById(document.getDocumentElement(), "selection_layer");
        if (selLayer == null) {
            Log.w(TAG, "No <g id='selection_layer'> found in SVG");
            return;
        }

        NodeList children = selLayer.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (!(child instanceof Element)) continue;
            Element el  = (Element) child;
            String  tag = normalizeTag(el.getTagName());
            String  id  = el.getAttribute("id");
            if (id == null || id.isEmpty()) continue;

            selectionLayerElements.put(id, el);

            RectF bounds = null;
            if ("rect".equals(tag))         bounds = computeRectBounds(el);
            else if ("polygon".equals(tag)) bounds = computePolyBounds(el);

            if (bounds != null && !bounds.isEmpty()) {
                selectionLayerBounds.put(id, bounds);
                Log.d(TAG, "SelectionLayer '" + id + "' → " + bounds);
            }
        }
        Log.d(TAG, "selection_layer parsed: " + selectionLayerBounds.size() + " areas");
    }

    // ══════════════════════════════════════════════════════════════════════
    //  ELEMENT ID METADATA  (<elementId> child tag)
    // ══════════════════════════════════════════════════════════════════════

    public String extractElementId(Element element) {
        return findElementIdInNode(element);
    }

    private String findElementIdInNode(Node node) {
        if (node instanceof Element) {
            Element el  = (Element) node;
            String  tag = el.getTagName();
            if (tag.contains(":")) tag = tag.substring(tag.indexOf(':') + 1);
            if ("elementId".equalsIgnoreCase(tag)) {
                String text = el.getTextContent();
                return (text != null && !text.trim().isEmpty()) ? text.trim() : null;
            }
        }
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            String r = findElementIdInNode(children.item(i));
            if (r != null) return r;
        }
        return null;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  ELEMENT FINDER
    // ══════════════════════════════════════════════════════════════════════

    public Element findElementById(Element root, String targetId) {
        if (targetId.equals(root.getAttribute("id"))) return root;
        NodeList children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element) {
                Element found = findElementById((Element) child, targetId);
                if (found != null) return found;
            }
        }
        return null;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  BOUNDS COMPUTATION
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Dispatches to the correct bounds-computation method based on SVG tag name.
     */
    public RectF computeBounds(Element element) {
        String tag = normalizeTag(element.getTagName());
        switch (tag) {
            case "g":        return computeGroupBounds(element);
            case "rect":     return computeRectBounds(element);
            case "circle":   return computeCircleBounds(element);
            case "ellipse":  return computeEllipseBounds(element);
            case "path":     return computePathBounds(element);
            case "polygon":
            case "polyline": return computePolyBounds(element);
            case "line":     return computeLineBounds(element);
            case "use":      return computeUseBounds(element);
            default:         return null;
        }
    }

    public RectF computeGroupBounds(Element element) {
        RectF union = null;
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element) {
                RectF b = computeBounds((Element) child);
                if (b != null && !b.isEmpty()) {
                    if (union == null) union = new RectF(b);
                    else union.union(b);
                }
            }
        }
        return union;
    }

    public RectF computeRectBounds(Element el) {
        Float x = fa(el, "x"), y = fa(el, "y");
        Float w = fa(el, "width"), h = fa(el, "height");
        if (w == null || h == null || w <= 0 || h <= 0) return null;
        float xv = x != null ? x : 0f, yv = y != null ? y : 0f;
        return new RectF(xv, yv, xv + w, yv + h);
    }

    public RectF computeCircleBounds(Element el) {
        Float cx = fa(el, "cx"), cy = fa(el, "cy"), r = fa(el, "r");
        if (r == null || r <= 0) return null;
        float cxv = cx != null ? cx : 0f, cyv = cy != null ? cy : 0f;
        return new RectF(cxv - r, cyv - r, cxv + r, cyv + r);
    }

    public RectF computeEllipseBounds(Element el) {
        Float cx = fa(el, "cx"), cy = fa(el, "cy");
        Float rx = fa(el, "rx"), ry = fa(el, "ry");
        if (rx == null || ry == null) return null;
        float cxv = cx != null ? cx : 0f, cyv = cy != null ? cy : 0f;
        return new RectF(cxv - rx, cyv - ry, cxv + rx, cyv + ry);
    }

    public RectF computePathBounds(Element el) {
        return parsePathBounds(el.getAttribute("d"));
    }

    public RectF computePolyBounds(Element el) {
        return parsePointsBounds(el.getAttribute("points"));
    }

    public RectF computeLineBounds(Element el) {
        Float x1 = fa(el, "x1"), y1 = fa(el, "y1");
        Float x2 = fa(el, "x2"), y2 = fa(el, "y2");
        float x1v = x1 != null ? x1 : 0f, y1v = y1 != null ? y1 : 0f;
        float x2v = x2 != null ? x2 : 0f, y2v = y2 != null ? y2 : 0f;
        return new RectF(Math.min(x1v, x2v), Math.min(y1v, y2v),
                Math.max(x1v, x2v), Math.max(y1v, y2v));
    }

    public RectF computeUseBounds(Element el) {
        Float x = fa(el, "x"), y = fa(el, "y");
        Float w = fa(el, "width"), h = fa(el, "height");
        if (w == null || h == null) return null;
        float xv = x != null ? x : 0f, yv = y != null ? y : 0f;
        return new RectF(xv, yv, xv + w, yv + h);
    }

    private Float fa(Element el, String attr) {
        String v = el.getAttribute(attr);
        if (v == null || v.isEmpty()) return null;
        try { return Float.parseFloat(v.trim()); }
        catch (NumberFormatException e) { return null; }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  PATH BOUNDS
    // ══════════════════════════════════════════════════════════════════════

    private RectF parsePathBounds(String d) {
        if (d == null || d.isEmpty()) return null;
        List<Float> xs = new ArrayList<>(), ys = new ArrayList<>();
        String cleaned = d.replaceAll("([MmLlHhVvCcSsQqTtAaZz])", " $1 ")
                .replaceAll("([0-9])-", "$1 -").trim();
        String[] tokens = cleaned.split("[\\s,]+");
        char    cmd   = 'M';
        float   curX  = 0, curY = 0, startX = 0, startY = 0;
        List<Float> args = new ArrayList<>();
        for (String token : tokens) {
            if (token.isEmpty()) continue;
            if (Character.isLetter(token.charAt(0))) {
                processPathCommand(cmd, args, xs, ys,
                        new float[]{curX}, new float[]{curY},
                        new float[]{startX}, new float[]{startY});
                if (!xs.isEmpty()) curX = xs.get(xs.size() - 1);
                if (!ys.isEmpty()) curY = ys.get(ys.size() - 1);
                cmd = token.charAt(0);
                args.clear();
            } else {
                try { args.add(Float.parseFloat(token)); }
                catch (NumberFormatException ignored) {}
            }
        }
        processPathCommand(cmd, args, xs, ys,
                new float[]{curX}, new float[]{curY},
                new float[]{startX}, new float[]{startY});
        if (xs.isEmpty() || ys.isEmpty()) return null;
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (float x : xs) { if (x < minX) minX = x; if (x > maxX) maxX = x; }
        for (float y : ys) { if (y < minY) minY = y; if (y > maxY) maxY = y; }
        return minX == Float.MAX_VALUE ? null : new RectF(minX, minY, maxX, maxY);
    }

    private void processPathCommand(char cmd, List<Float> args,
                                    List<Float> xs, List<Float> ys,
                                    float[] cx, float[] cy,
                                    float[] sx, float[] sy) {
        if (args.isEmpty()) return;
        switch (cmd) {
            case 'M':
                for (int i = 0; i + 1 < args.size(); i += 2) {
                    float x = args.get(i), y = args.get(i + 1);
                    xs.add(x); ys.add(y); cx[0] = x; cy[0] = y;
                    if (i == 0) { sx[0] = x; sy[0] = y; }
                }
                break;
            case 'm':
                for (int i = 0; i + 1 < args.size(); i += 2) {
                    cx[0] += args.get(i); cy[0] += args.get(i + 1);
                    xs.add(cx[0]); ys.add(cy[0]);
                    if (i == 0) { sx[0] = cx[0]; sy[0] = cy[0]; }
                }
                break;
            case 'L':
                for (int i = 0; i + 1 < args.size(); i += 2) {
                    float x = args.get(i), y = args.get(i + 1);
                    xs.add(x); ys.add(y); cx[0] = x; cy[0] = y;
                }
                break;
            case 'l':
                for (int i = 0; i + 1 < args.size(); i += 2) {
                    cx[0] += args.get(i); cy[0] += args.get(i + 1);
                    xs.add(cx[0]); ys.add(cy[0]);
                }
                break;
            case 'H':
                for (float v : args) { xs.add(v); ys.add(cy[0]); cx[0] = v; }
                break;
            case 'h':
                for (float v : args) { cx[0] += v; xs.add(cx[0]); ys.add(cy[0]); }
                break;
            case 'V':
                for (float v : args) { xs.add(cx[0]); ys.add(v); cy[0] = v; }
                break;
            case 'v':
                for (float v : args) { cy[0] += v; xs.add(cx[0]); ys.add(cy[0]); }
                break;
            case 'C':
                for (int i = 0; i + 5 < args.size(); i += 6) {
                    xs.add(args.get(i));     ys.add(args.get(i + 1));
                    xs.add(args.get(i + 2)); ys.add(args.get(i + 3));
                    xs.add(args.get(i + 4)); ys.add(args.get(i + 5));
                    cx[0] = args.get(i + 4); cy[0] = args.get(i + 5);
                }
                break;
            case 'c':
                for (int i = 0; i + 5 < args.size(); i += 6) {
                    xs.add(cx[0] + args.get(i));     ys.add(cy[0] + args.get(i + 1));
                    xs.add(cx[0] + args.get(i + 2)); ys.add(cy[0] + args.get(i + 3));
                    cx[0] += args.get(i + 4); cy[0] += args.get(i + 5);
                    xs.add(cx[0]); ys.add(cy[0]);
                }
                break;
            case 'A':
                for (int i = 0; i + 6 < args.size(); i += 7) {
                    float x = args.get(i + 5), y = args.get(i + 6);
                    xs.add(x); ys.add(y); cx[0] = x; cy[0] = y;
                }
                break;
            case 'a':
                for (int i = 0; i + 6 < args.size(); i += 7) {
                    cx[0] += args.get(i + 5); cy[0] += args.get(i + 6);
                    xs.add(cx[0]); ys.add(cy[0]);
                }
                break;
            case 'Z': case 'z':
                xs.add(sx[0]); ys.add(sy[0]); cx[0] = sx[0]; cy[0] = sy[0];
                break;
        }
    }

    private RectF parsePointsBounds(String points) {
        if (points == null || points.isEmpty()) return null;
        String[] tokens = points.trim().split("[\\s,]+");
        List<Float> xs = new ArrayList<>(), ys = new ArrayList<>();
        for (int i = 0; i + 1 < tokens.length; i += 2) {
            try {
                xs.add(Float.parseFloat(tokens[i]));
                ys.add(Float.parseFloat(tokens[i + 1]));
            } catch (NumberFormatException ignored) {}
        }
        if (xs.isEmpty()) return null;
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (float x : xs) { if (x < minX) minX = x; if (x > maxX) maxX = x; }
        for (float y : ys) { if (y < minY) minY = y; if (y > maxY) maxY = y; }
        return new RectF(minX, minY, maxX, maxY);
    }

    // ══════════════════════════════════════════════════════════════════════
    //  UTILITIES
    // ══════════════════════════════════════════════════════════════════════

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
        if (normId.equals(normFocus))   return true;
        if (normFocus.contains(normId)) return true;
        if (normId.contains(normFocus)) return true;
        String[] idWords    = normId.split("_");
        String[] focusWords = normFocus.split("_");
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