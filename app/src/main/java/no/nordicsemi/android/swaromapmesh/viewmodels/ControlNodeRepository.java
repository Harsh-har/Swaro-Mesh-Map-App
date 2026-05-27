package no.nordicsemi.android.swaromapmesh.viewmodels;

import android.util.Log;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import no.nordicsemi.android.swaromapmesh.MeshNetwork;
import no.nordicsemi.android.swaromapmesh.transport.Element;
import no.nordicsemi.android.swaromapmesh.transport.MeshModel;
import no.nordicsemi.android.swaromapmesh.transport.ProvisionedMeshNode;


public final class ControlNodeRepository {

    private static final String TAG             = "ControlNodeRepository";
    private static final int    MODEL_ONOFF_CLIENT = 0x1001;

    /**
     * Load a ControlNodeInfo for the node whose name matches svgNodeName.
     *
     * @param network     The live MeshNetwork (from getMeshNetwork())
     * @param svgNodeName The exact string used as the SVG group id,
     *                    e.g. "Control Node Guest Room"
     * @return ControlNodeInfo if found, null otherwise
     */
    public static ControlNodeInfo load(MeshNetwork network, String svgNodeName) {
        if (network == null || svgNodeName == null || svgNodeName.isEmpty()) return null;

        // ── 1. Find the matching node by name ──────────────────────────────
        ProvisionedMeshNode controlNode = findNodeByName(network, svgNodeName);
        if (controlNode == null) {
            Log.w(TAG, "No node found matching: " + svgNodeName);
            return null;
        }
        Log.d(TAG, "Found node: " + controlNode.getNodeName()
                + " unicast=0x" + String.format("%04X", controlNode.getUnicastAddress()));

        // ── 2. Build unicast → name lookup from entire network ─────────────
        Map<Integer, String> unicastToName = buildUnicastMap(network);

        // ── 3. Walk elements and collect mapped ones ───────────────────────
        List<ControlNodeInfo.ControlElement> mapped = new ArrayList<>();

        List<Element> sortedElements = new ArrayList<>(controlNode.getElements().values());
        // Sort by element address so index is consistent
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            sortedElements.sort((a, b) ->
                    Integer.compare(a.getElementAddress(), b.getElementAddress()));
        }

        int baseUnicast = controlNode.getUnicastAddress();

        for (int i = 0; i < sortedElements.size(); i++) {
            Element el       = sortedElements.get(i);
            int     elAddr   = el.getElementAddress();
            int     elIndex  = elAddr - baseUnicast; // 0-based index

            // Find the OnOff Client model on this element
            MeshModel onOffClient = el.getMeshModels().get(MODEL_ONOFF_CLIENT);
            if (onOffClient == null) continue;

            // Check if it has a publication address
            if (onOffClient.getPublicationSettings() == null) continue;
            int publishAddr = onOffClient.getPublicationSettings().getPublishAddress();
            if (publishAddr == 0x0000) continue;

            // Resolve server name
            String serverName = unicastToName.get(
                    String.format("%04X", publishAddr).toUpperCase());
            if (serverName == null) {
                serverName = publishAddr >= 0xC000
                        ? "Group 0x" + String.format("%04X", publishAddr)
                        : "Unknown 0x" + String.format("%04X", publishAddr);
            }

            mapped.add(new ControlNodeInfo.ControlElement(
                    elIndex, elAddr, publishAddr, serverName));

            Log.d(TAG, "  Element[" + elIndex + "] 0x"
                    + String.format("%04X", elAddr)
                    + " → 0x" + String.format("%04X", publishAddr)
                    + " (" + serverName + ")");
        }

        Log.d(TAG, "Loaded " + mapped.size() + " mapped elements for: " + svgNodeName);
        return new ControlNodeInfo(
                controlNode.getNodeName(),
                controlNode.getUuid(),
                baseUnicast,
                mapped);
    }

    /**
     * Load and immediately save to SharedPreferences via ClientServerElementStore.
     * Call this once after network import or provisioning is complete.
     */
    public static ControlNodeInfo loadAndSave(MeshNetwork network, String svgNodeName) {
        ControlNodeInfo info = load(network, svgNodeName);
        if (info == null) return null;
        saveToStore(info, svgNodeName);
        return info;
    }

    /**
     * Save ControlNodeInfo into ClientServerElementStore so the rest of the
     * app (publication setup, SVG coloring) can find the addresses.
     */
    public static void saveToStore(ControlNodeInfo info, String svgKey) {
        if (info == null || svgKey == null) return;

        // Save each client element address by its 0-based index
        Map<Integer, Integer> elementAddresses = new HashMap<>();
        for (ControlNodeInfo.ControlElement el : info.elements) {
            if (!el.isGroupAddress()) {
                elementAddresses.put(el.elementIndex, el.clientUnicast);
            }
        }
        ClientServerElementStore.saveAllClientElementAddresses(svgKey, elementAddresses);

        // Save unicast address as server unicast (so provisioned check works)
        ClientServerElementStore.saveServerUnicastAddress(svgKey, info.unicastAddress);

        Log.d(TAG, "Saved to store: svgKey=" + svgKey
                + " elements=" + elementAddresses.size()
                + " baseUnicast=0x" + String.format("%04X", info.unicastAddress));
    }

    // ══════════════════════════════════════════════════════════════════════
    //  PRIVATE HELPERS
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Find a node by name — exact match first, then case-insensitive,
     * then contains match. Returns the first match found.
     */
    private static ProvisionedMeshNode findNodeByName(MeshNetwork network,
                                                      String targetName) {
        String lowerTarget = targetName.toLowerCase().trim();

        // Pass 1 — exact
        for (ProvisionedMeshNode node : network.getNodes()) {
            if (targetName.equals(node.getNodeName())) return node;
        }
        // Pass 2 — case-insensitive
        for (ProvisionedMeshNode node : network.getNodes()) {
            if (node.getNodeName() != null
                    && lowerTarget.equals(node.getNodeName().toLowerCase().trim()))
                return node;
        }
        // Pass 3 — contains
        for (ProvisionedMeshNode node : network.getNodes()) {
            if (node.getNodeName() != null
                    && node.getNodeName().toLowerCase().contains(lowerTarget))
                return node;
        }
        return null;
    }

    /**
     * Build a hex-string unicast → node name map for the whole network.
     * Key format: "00A6" (uppercase, 4 chars).
     */
    private static Map<Integer, String> buildUnicastMap(MeshNetwork network) {
        // key = hex string like "00D5", value = node name
        // but we return int→String for direct int lookup
        Map<Integer, String> map = new HashMap<>();
        for (ProvisionedMeshNode node : network.getNodes()) {
            map.put(node.getUnicastAddress(), node.getNodeName());
            // Also map all element addresses for multi-element nodes
            for (Element el : node.getElements().values()) {
                map.put(el.getElementAddress(), node.getNodeName());
            }
        }
        return map;
    }

    private ControlNodeRepository() {} // no instantiation
}