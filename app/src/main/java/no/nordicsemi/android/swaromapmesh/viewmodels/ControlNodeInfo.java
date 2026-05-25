package no.nordicsemi.android.swaromapmesh.viewmodels;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a single Control Node's complete data —
 * parsed dynamically from the provisioned mesh network.
 *
 * You only hardcode the SVG group id (e.g. "Control Node Guest Room").
 * This class is populated automatically by ControlNodeRepository
 * by matching that name against the live provisioned node list.
 */
public class ControlNodeInfo {

    // ── Identity ───────────────────────────────────────────────────────────
    public final String nodeName;       // e.g. "Control Node Guest Room"
    public final String uuid;
    public final int    unicastAddress; // base address of the control node

    // ── Element list ───────────────────────────────────────────────────────
    public final List<ControlElement> elements;

    public ControlNodeInfo(String nodeName, String uuid,
                           int unicastAddress, List<ControlElement> elements) {
        this.nodeName      = nodeName;
        this.uuid          = uuid;
        this.unicastAddress = unicastAddress;
        this.elements      = elements;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  ControlElement  — one entry per mapped element
    // ══════════════════════════════════════════════════════════════════════
    public static class ControlElement {

        /** 0-based index inside the control node's element array. */
        public final int    elementIndex;

        /**
         * Unicast address of this client element.
         * = controlNode.unicastAddress + elementIndex
         */
        public final int    clientUnicast;

        /**
         * The server unicast this element publishes to.
         * Group addresses (≥ 0xC000) are included as-is.
         * -1 means not mapped.
         */
        public final int    serverUnicast;

        /** Name of the target server node (from mesh network node list). */
        public final String serverName;

        public ControlElement(int elementIndex, int clientUnicast,
                              int serverUnicast, String serverName) {
            this.elementIndex  = elementIndex;
            this.clientUnicast = clientUnicast;
            this.serverUnicast = serverUnicast;
            this.serverName    = serverName;
        }

        public boolean isGroupAddress() {
            return serverUnicast >= 0xC000;
        }

        @Override
        public String toString() {
            return String.format(
                    "Element[%02d] client=0x%04X → server=0x%04X (%s)",
                    elementIndex, clientUnicast, serverUnicast, serverName);
        }
    }

    @Override
    public String toString() {
        return nodeName + " (0x" + String.format("%04X", unicastAddress)
                + ") — " + elements.size() + " mapped elements";
    }
}
