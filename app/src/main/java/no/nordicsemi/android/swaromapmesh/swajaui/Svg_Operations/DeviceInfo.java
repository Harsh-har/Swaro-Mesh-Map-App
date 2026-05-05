package no.nordicsemi.android.swaromapmesh.swajaui.Svg_Operations;

import android.graphics.RectF;
import org.w3c.dom.Element;

public class DeviceInfo {

    /** Unique icon ID from the SVG (e.g. "LivingRoom:Light_1") */
    public final String id;

    /** The SVG <g> element representing this device icon */
    public final Element element;

    /** Bounding box in SVG coordinate space */
    public final RectF bounds;

    /** Element ID extracted from <elementId> child node (e.g. "PDRD", "VCRD", "1") */
    public final String elementId;

    /** Node ID extracted from <nodeId> child node — only for 2-ID nodes (e.g. "2", "3") */
    public final String nodeId;

    /** Area group ID this device belongs to (e.g. "LivingRoom") */
    public final String areaId;

    public DeviceInfo(String id, Element element, RectF bounds,
                      String elementId, String nodeId, String areaId) {  // ← nodeId add
        this.id        = id;
        this.element   = element;
        this.bounds    = bounds;
        this.elementId = elementId;
        this.nodeId    = nodeId;
        this.areaId    = areaId;
    }
}