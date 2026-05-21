package no.nordicsemi.android.swaromapmesh.swajaui.Svg_Operations;

import android.graphics.RectF;
import org.w3c.dom.Element;

/**
 * Holds all parsed information for a single device icon found in
 * the Technician_Layer of the SVG.
 *
 * ID format:  <ROOM_CODE>_<DEVICE_CODE>_<INSTANCE>[_<SUBTYPE>]
 *  * Example:    MBDR_CLE02_1
 *             MBDR_PSS04_7
 *             MBDR_IR01_1_AC
 *
 * roomCode   → "MBDR"
 * deviceCode → "CLE02"
 * instance   → 1   (also = number of User_Layer nodes linked to this icon)
 * subType    → ""  (or "AC" for MBDR_IR01_1_AC)
 */

public class DeviceInfo {

    // ── Core identity ─────────────────────────────────────────────────────

    /** Full element id as it appears in SVG, e.g. "MBDR_CLE02_1" */
    public final String id;

    /** Short room/area code extracted from id, e.g. "MBDR" */
    public final String roomCode;

    /** Device type code extracted from id, e.g. "CLE02", "PSS04" */
    public final String deviceCode;

    /**
     * Instance / connection count extracted from id, e.g. 1, 7.
     * This number tells how many User_Layer nodes are linked to this icon.
     */
    public final int instance;

    /**
     * Optional subtype suffix, e.g. "AC" in "MBDR_IR01_1_AC".
     * Empty string when absent.
     */
    public final String subType;

    // ── SVG DOM references ────────────────────────────────────────────────

    /** The SVG &lt;g&gt; element for this icon inside Technician_Layer */
    public final Element element;

    /** Bounding box in SVG coordinate space */
    public final RectF bounds;

    // ── Area grouping ─────────────────────────────────────────────────────

    /**
     * Full room group id this device belongs to,
     * e.g. "Master_Bedroom_MBDR".
     * Derived by matching roomCode against the parent &lt;g&gt; id suffix.
     */
    public final String areaId;

    // ── Constructor ───────────────────────────────────────────────────────

    public DeviceInfo(String id,
                      String roomCode,
                      String deviceCode,
                      int    instance,
                      String subType,
                      Element element,
                      RectF   bounds,
                      String  areaId) {
        this.id         = id;
        this.roomCode   = roomCode;
        this.deviceCode = deviceCode;
        this.instance   = instance;
        this.subType    = subType != null ? subType : "";
        this.element    = element;
        this.bounds     = bounds;
        this.areaId     = areaId;
    }

    // ── Convenience helpers ───────────────────────────────────────────────

    /**
     * Returns true if this device has a subtype suffix (e.g. "AC").
     */
    public boolean hasSubType() {
        return !subType.isEmpty();
    }

    /**
     * Returns the User_Layer element id that corresponds to this icon.
     * Convention: Technician id  "MBDR_CLE02_1"
     *             User_Layer id  "MBDR_CLE02_1-2"   (suffix "-2")
     */
    public String getUserLayerId() {
        return id + "-2";
    }

    @Override
    public String toString() {
        return "DeviceInfo{id='" + id + "'"
                + ", room='" + roomCode + "'"
                + ", code='" + deviceCode + "'"
                + ", instance=" + instance
                + (hasSubType() ? ", subType='" + subType + "'" : "")
                + ", area='" + areaId + "'"
                + "}";
    }
}