package no.nordicsemi.android.swaromapmesh.swajaui;

import android.content.ContentResolver;
import android.net.Uri;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.InputStream;
import java.util.ArrayList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

public class SvgParser {

    public static ArrayList<String> parseAreaIds(ContentResolver resolver, Uri uri) {
        ArrayList<String> areaIds = new ArrayList<>();
        try {
            InputStream is = resolver.openInputStream(uri);
            if (is == null) return areaIds;

            Document document = parseDocument(is);
            is.close();

            if (document == null) return areaIds;

            Element iconsGroup = findElementById(document.getDocumentElement(), "Icons");
            if (iconsGroup == null) return areaIds;

            NodeList children = iconsGroup.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node node = children.item(i);
                if (!(node instanceof Element)) continue;
                Element el  = (Element) node;
                String  tag = el.getTagName().toLowerCase();
                if (tag.contains(":")) tag = tag.substring(tag.indexOf(':') + 1);
                if (!"g".equals(tag)) continue;

                String areaId = el.getAttribute("id");
                if (areaId != null && !areaId.isEmpty()) {
                    areaIds.add(areaId);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return areaIds;
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

    private static Element findElementById(Element root, String targetId) {
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
}