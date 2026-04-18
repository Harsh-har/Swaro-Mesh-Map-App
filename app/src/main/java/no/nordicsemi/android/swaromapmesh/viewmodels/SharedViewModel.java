package no.nordicsemi.android.swaromapmesh.viewmodels;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;
import dagger.hilt.android.qualifiers.ApplicationContext;
import no.nordicsemi.android.swaromapmesh.ApplicationKey;
import no.nordicsemi.android.swaromapmesh.MeshNetwork;
import no.nordicsemi.android.swaromapmesh.NodeKey;
import no.nordicsemi.android.swaromapmesh.adapter.ExtendedBluetoothDevice;
import no.nordicsemi.android.swaromapmesh.ble.adapter.DevicesAdapter;
import no.nordicsemi.android.swaromapmesh.transport.Element;
import no.nordicsemi.android.swaromapmesh.transport.MeshModel;
import no.nordicsemi.android.swaromapmesh.transport.ProvisionedMeshNode;
import no.nordicsemi.android.swaromapmesh.utils.NetworkExportUtils;

@HiltViewModel
public class SharedViewModel extends BaseViewModel
        implements NetworkExportUtils.NetworkExportCallbacks {

    private static final String TAG = "SharedViewModel";

    private final ScannerRepository mScannerRepository;
    private final SingleLiveEvent<String> networkExportState = new SingleLiveEvent<>();

    // ── SharedPreferences keys ─────────────────────────────────────────────
    private static final String PREFS_NAME                         = "mesh_prefs";
    private static final String KEY_PROXY_ENABLED                  = "proxy_enabled";
    private static final String KEY_SELECTED_DEVICE                = "selected_device";
    private static final String KEY_SIGNAL_THRESHOLD               = "signal_threshold";
    private static final String KEY_SVG_URI                        = "svg_uri";
    private static final String KEY_PROVISIONED_DEVICES            = "provisioned_devices";
    private static final String KEY_SERVER_SVG_DEVICE_ID           = "server_svg_device_id";
    private static final String KEY_ELEMENT_ADDRESS_MAPPING_PREFIX = "element_addr_";
    private static final String KEY_ELEMENT_ID_MAPPING_PREFIX      = "element_id_";
    private static final String KEY_CLIENT_ADDRESS_MAPPING_PREFIX  = "client_addr_";
    private static final String DEFAULT_SELECTED_DEVICE            = "All Device";

    private final SharedPreferences prefs;

    // ── LiveData ───────────────────────────────────────────────────────────
    private final MutableLiveData<Boolean>     proxyEnabled         = new MutableLiveData<>();
    private final MutableLiveData<String>      selectedDevice       = new MutableLiveData<>(DEFAULT_SELECTED_DEVICE);
    private final MutableLiveData<Integer>     signalThreshold      = new MutableLiveData<>(DevicesAdapter.SIGNAL_DEFAULT);
    private final MutableLiveData<Uri>         svgUri               = new MutableLiveData<>();
    private final MutableLiveData<String>      selectedDeviceId     = new MutableLiveData<>();
    private final MutableLiveData<Set<String>> provisionedDeviceIds = new MutableLiveData<>(new HashSet<>());

    private final MutableLiveData<List<ExtendedBluetoothDevice>> filteredDevices =
            new MutableLiveData<>(new ArrayList<>());
    private final MutableLiveData<List<ExtendedBluetoothDevice>> allUnprovisionedDevices =
            new MutableLiveData<>(new ArrayList<>());

    private final MutableLiveData<String> mSelectedSvgDeviceId = new MutableLiveData<>();
    private final MutableLiveData<String> mServerSvgDeviceId   = new MutableLiveData<>();
    private final Map<String, String>     nodeToSvgMap         = new HashMap<>();

    // =========================================================================
    // Constructor
    // =========================================================================
    @Inject
    SharedViewModel(
            @NonNull final NrfMeshRepository nrfMeshRepository,
            @NonNull final ScannerRepository scannerRepository,
            @ApplicationContext @NonNull final Context context
    ) {
        super(nrfMeshRepository);

        ClientServerElementStore.init(context);

        mScannerRepository = scannerRepository;
        scannerRepository.registerBroadcastReceivers();

        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        proxyEnabled.setValue(prefs.getBoolean(KEY_PROXY_ENABLED, true));
        selectedDevice.setValue(prefs.getString(KEY_SELECTED_DEVICE, DEFAULT_SELECTED_DEVICE));
        signalThreshold.setValue(prefs.getInt(KEY_SIGNAL_THRESHOLD, DevicesAdapter.SIGNAL_DEFAULT));

        final String savedSvgUri = prefs.getString(KEY_SVG_URI, null);
        if (savedSvgUri != null) svgUri.setValue(Uri.parse(savedSvgUri));

        Set<String> savedProvisioned = prefs.getStringSet(KEY_PROVISIONED_DEVICES, new HashSet<>());
        provisionedDeviceIds.setValue(new HashSet<>(savedProvisioned));

        String savedServerId = prefs.getString(KEY_SERVER_SVG_DEVICE_ID, null);
        if (savedServerId != null) mServerSvgDeviceId.setValue(savedServerId);

        // ── Observe nodes: sync stale IDs AND rebuild after import ────────────
        getNodes().observeForever(nodes -> {
            syncProvisionedWithMeshNetwork();
        });

        mNrfMeshRepository.setOnNetworkImportedCallback(this::rebuildProvisionedFromMesh);
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        if (!mNrfMeshRepository.getBleMeshManager().isConnected()) {
            mNrfMeshRepository.disconnect();
        }
        mScannerRepository.unregisterBroadcastReceivers();
    }
    private void syncProvisionedWithMeshNetwork() {
        Set<String> saved = new HashSet<>(
                prefs.getStringSet(KEY_PROVISIONED_DEVICES, new HashSet<>()));
        if (saved.isEmpty()) return;

        List<ProvisionedMeshNode> meshNodes = getAllProvisionedNodes();
        if (meshNodes == null || meshNodes.isEmpty()) {
            prefs.edit().putStringSet(KEY_PROVISIONED_DEVICES, new HashSet<>()).apply();
            provisionedDeviceIds.setValue(new HashSet<>());
            Log.d(TAG, "🧹 syncProvisioned: mesh empty — cleared all");
            return;
        }

        // ✅ Build valid ids set including SVG-style ids
        Set<String> validMeshIds = new HashSet<>();
        for (ProvisionedMeshNode node : meshNodes) {
            String nodeName = node.getNodeName();
            if (nodeName != null) {
                validMeshIds.add(nodeName); // "Relay Node"
            }
            String svgId = getSvgIdFromNode(node);
            if (svgId != null) validMeshIds.add(svgId);
        }

        Set<String> cleaned = new HashSet<>();
        for (String savedId : saved) {
            // ✅ Direct match check
            if (validMeshIds.contains(savedId)) {
                cleaned.add(savedId);
                continue;
            }

            // ✅ Suffix match: "PDRI:Relay Node5" → pure name "relay node"
            // Compare against each mesh node name
            boolean matched = false;
            String savedPure = extractPureName(savedId); // "relay node"
            for (ProvisionedMeshNode node : meshNodes) {
                String nodeName = node.getNodeName();
                if (nodeName == null) continue;
                String nodePure = nodeName.trim().toLowerCase();
                if (nodePure.equals(savedPure)) {
                    cleaned.add(savedId); // ✅ Keep it — it belongs to this node
                    matched = true;
                    Log.d(TAG, "✅ syncProvisioned: suffix match kept → " + savedId
                            + " (node: " + nodeName + ")");
                    break;
                }
            }

            if (!matched) {
                Log.d(TAG, "🧹 syncProvisioned: removing stale → " + savedId);
            }
        }

        if (cleaned.size() != saved.size()) {
            prefs.edit().putStringSet(KEY_PROVISIONED_DEVICES, cleaned).apply();
            provisionedDeviceIds.setValue(cleaned);
            Log.d(TAG, "✅ syncProvisioned done — remaining: " + cleaned);
        }
    }

    // ✅ Helper: "PDRI:Relay Node5" → "relay node"
    private String extractPureName(String fullId) {
        if (fullId == null) return "";
        String name = fullId.trim().toLowerCase();
        int colon = name.lastIndexOf(":");
        if (colon != -1) name = name.substring(colon + 1).trim();
        name = name.replaceAll("\\s*\\d+$", "").replaceAll("\\d+$", "").trim();
        return name;
    }
    // =========================================================================
    // ✅ FIX: Import ke baad SVG rebuild
    // =========================================================================

    public void rebuildProvisionedFromMesh() {
        List<ProvisionedMeshNode> nodes = getAllProvisionedNodes();
        if (nodes == null || nodes.isEmpty()) {
            Log.w(TAG, "rebuildProvisionedFromMesh: no nodes found — nothing to rebuild");
            return;
        }

        Set<String> rebuilt = new HashSet<>();

        for (ProvisionedMeshNode node : nodes) {
            final String uuid = node.getUuid();

            // Step 1: node_svg_ prefs se svgId lo (agar export se pehle map kiya tha)
            String svgId = prefs.getString("node_svg_" + uuid, null);
            if (svgId != null && !svgId.isEmpty()) {
                rebuilt.add(svgId);
                // in-memory map bhi restore karo taaki getSvgIdFromNode() kaam kare
                nodeToSvgMap.put(uuid, svgId);
                Log.d(TAG, "rebuildProvisionedFromMesh: uuid→svgId  " + uuid + " → " + svgId);
            }

            // Step 2: Node name bhi add karo (fallback — provisioning ke waqt
            //         node name == svgDeviceId hota tha)
            String nodeName = node.getNodeName();
            if (nodeName != null && !nodeName.isEmpty()) {
                rebuilt.add(nodeName);
                Log.d(TAG, "rebuildProvisionedFromMesh: nodeName → " + nodeName);
            }
        }

        if (!rebuilt.isEmpty()) {
            prefs.edit().putStringSet(KEY_PROVISIONED_DEVICES, rebuilt).apply();
            provisionedDeviceIds.setValue(new HashSet<>(rebuilt));
            Log.d(TAG, "✅ rebuildProvisionedFromMesh complete — provisioned: " + rebuilt);
        }
        else {
            Log.w(TAG, "rebuildProvisionedFromMesh: nothing to rebuild");
        }

        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            forceSvgRefresh();
            Log.d(TAG, "🔄 forceSvgRefresh after import delay");
        }, 1500);
    }

    // =========================================================================
    // ELEMENT ID (SVG shape ID — user assigned)
    // =========================================================================

    /**
     * DeviceDetailActivity yeh call karta hai — svgDeviceId ke liye elementId save karo.
     */
// REPLACE the existing saveElementId() with this:
    public void saveElementId(@NonNull String svgDeviceId, @NonNull String elementId) {
        // Save in prefs with original key
        String key = KEY_ELEMENT_ID_MAPPING_PREFIX + svgDeviceId;
        prefs.edit().putString(key, elementId).apply();

        try {
            int svgElementIdInt = Integer.parseInt(elementId.trim());

            // ✅ Save ONLY with full lowercased key: "pdri:relay node1"
            // Do NOT save under pure name — it breaks resolveKeyByNodeName suffix matching
            String fullKey = svgDeviceId.trim().toLowerCase();
            ClientServerElementStore.saveServerSvgElementId(fullKey, svgElementIdInt);

            Log.d(TAG, "✅ saveElementId: " + svgDeviceId + " = " + elementId
                    + " (also saved in Store as " + fullKey + " = " + svgElementIdInt + ")");
        } catch (NumberFormatException e) {
            Log.w(TAG, "saveElementId: elementId not a number — Store not updated: " + elementId);
        }
    }
    @Nullable
    public String getElementId(@NonNull String svgDeviceId) {
        return prefs.getString(KEY_ELEMENT_ID_MAPPING_PREFIX + svgDeviceId, null);
    }

    public int getSvgElementIdAsInt(@NonNull String svgDeviceId) {
        String s = getElementId(svgDeviceId);
        if (s == null) return -1;
        try { return Integer.parseInt(s.trim()); }
        catch (NumberFormatException e) { return -1; }
    }

    private final MutableLiveData<String> focusAreaId = new MutableLiveData<>();

    public LiveData<String> getFocusAreaId() { return focusAreaId; }

    public void setFocusAreaId(String areaId) { focusAreaId.setValue(areaId); }

    // =========================================================================
    // CLIENT ELEMENT ADDRESS MAPPING
    // =========================================================================

    public void saveClientElementAddress(@NonNull String svgDeviceId,
                                         int elementIndex, int elementAddress) {
        String key = KEY_ELEMENT_ADDRESS_MAPPING_PREFIX + svgDeviceId + "_" + elementIndex;
        prefs.edit().putInt(key, elementAddress).apply();
        Log.d(TAG, "✅ saveClientElementAddress: " + svgDeviceId
                + "[" + elementIndex + "] = 0x" + String.format("%04X", elementAddress));
    }

    public void saveAllClientElementAddresses(@NonNull String svgDeviceId,
                                              @NonNull Map<Integer, Integer> elementAddresses) {
        for (Map.Entry<Integer, Integer> entry : elementAddresses.entrySet()) {
            saveClientElementAddress(svgDeviceId, entry.getKey(), entry.getValue());
        }
        Log.d(TAG, "✅ saveAllClientElementAddresses: "
                + elementAddresses.size() + " for " + svgDeviceId);
    }

    // =========================================================================
    // NODE DELETE
    // =========================================================================

    public void removeNodeFromNetwork(ProvisionedMeshNode node) {
        if (node == null) return;
        List<ProvisionedMeshNode> nodes = getAllProvisionedNodes();
        if (nodes != null) {
            nodes.remove(node);
            Log.d(TAG, "🔥 Node removed: " + node.getNodeName());
        }
    }

    public boolean fullyDeleteNode(@NonNull ProvisionedMeshNode adapterNode) {
        ProvisionedMeshNode realNode = null;
        List<ProvisionedMeshNode> nodes = getAllProvisionedNodes();
        if (nodes != null) {
            for (ProvisionedMeshNode n : nodes) {
                if (n.getUuid().equals(adapterNode.getUuid())) { realNode = n; break; }
            }
        }
        if (realNode == null) { Log.e(TAG, "❌ Node not found in network"); return false; }

        String nodeName = realNode.getNodeName();
        String svgId    = getSvgIdFromNode(realNode);
        Log.d(TAG, "fullyDeleteNode: nodeName=" + nodeName + " svgId=" + svgId);

        boolean deleted = getNetworkLiveData().getMeshNetwork().deleteNode(realNode);
        if (!deleted) { Log.e(TAG, "❌ Mesh delete failed"); return false; }

        if (svgId != null) unmarkDeviceProvisioned(svgId);
        if (nodeName != null && !nodeName.equals(svgId)) unmarkDeviceProvisioned(nodeName);
        removeAllMatchingProvisioned(nodeName, svgId);
        nodeToSvgMap.remove(realNode.getUuid());
        prefs.edit().remove("node_svg_" + realNode.getUuid()).apply();
        forceSvgRefresh();
        Log.d(TAG, "✅ fullyDeleteNode complete: " + nodeName);
        return true;
    }

    // =========================================================================
    // PROVISIONED DEVICE IDs
    // =========================================================================

    public LiveData<Set<String>> getProvisionedDeviceIds() { return provisionedDeviceIds; }

    public boolean isDeviceProvisioned(String svgDeviceId) {
        if (svgDeviceId == null) return false;
        Set<String> set = provisionedDeviceIds.getValue();
        return set != null && set.contains(svgDeviceId);
    }

    public void markDeviceProvisioned(String svgDeviceId) {
        if (svgDeviceId == null) return;
        Set<String> current = new HashSet<>();
        if (provisionedDeviceIds.getValue() != null) current.addAll(provisionedDeviceIds.getValue());
        current.add(svgDeviceId);
        provisionedDeviceIds.setValue(current);
        prefs.edit().putStringSet(KEY_PROVISIONED_DEVICES, new HashSet<>(current)).apply();
        Log.d(TAG, "✅ markDeviceProvisioned: " + svgDeviceId);
    }

    public void unmarkDeviceProvisioned(String svgDeviceId) {
        if (svgDeviceId == null) return;
        Set<String> current = new HashSet<>(
                prefs.getStringSet(KEY_PROVISIONED_DEVICES, new HashSet<>()));
        if (current.remove(svgDeviceId)) {
            prefs.edit().putStringSet(KEY_PROVISIONED_DEVICES, current).apply();
            provisionedDeviceIds.setValue(new HashSet<>(current));
            Log.d(TAG, "✅ unmarkDeviceProvisioned: " + svgDeviceId);
        } else {
            Log.w(TAG, "⚠️ unmarkDeviceProvisioned: not found → " + svgDeviceId);
        }
    }

    private void removeAllMatchingProvisioned(@Nullable String nodeName, @Nullable String svgId) {
        Set<String> current = new HashSet<>(
                prefs.getStringSet(KEY_PROVISIONED_DEVICES, new HashSet<>()));
        Set<String> toRemove = new HashSet<>();
        for (String id : current) {
            boolean matchesNode = nodeName != null && (id.equals(nodeName) || id.contains(nodeName));
            boolean matchesSvg  = svgId    != null && (id.equals(svgId)    || id.contains(svgId));
            if (matchesNode || matchesSvg) {
                toRemove.add(id);
                Log.d(TAG, "🧹 removeAllMatchingProvisioned: removing → " + id);
            }
        }
        if (!toRemove.isEmpty()) {
            current.removeAll(toRemove);
            prefs.edit().putStringSet(KEY_PROVISIONED_DEVICES, current).apply();
            provisionedDeviceIds.setValue(new HashSet<>(current));
        }
    }

    public void forceSvgRefresh() {
        Set<String> fresh = new HashSet<>(
                prefs.getStringSet(KEY_PROVISIONED_DEVICES, new HashSet<>()));
        provisionedDeviceIds.setValue(fresh);
        Log.d(TAG, "🔄 forceSvgRefresh — provisioned: " + fresh);
    }

    public void clearProvisionedDevices() {
        provisionedDeviceIds.setValue(new HashSet<>());
        prefs.edit().remove(KEY_PROVISIONED_DEVICES).apply();
    }

    // =========================================================================
    // NODE ↔ SVG MAPPING
    // =========================================================================

    public void mapNodeToSvg(String nodeUuid, String svgId) {
        if (nodeUuid == null || svgId == null) return;
        nodeToSvgMap.put(nodeUuid, svgId);
        prefs.edit().putString("node_svg_" + nodeUuid, svgId).apply();
        Log.d(TAG, "✅ mapNodeToSvg: " + nodeUuid + " → " + svgId);
    }

    public String getSvgIdFromNode(ProvisionedMeshNode node) {
        if (node == null) return null;
        String svgId = nodeToSvgMap.get(node.getUuid());
        if (svgId == null) {
            svgId = prefs.getString("node_svg_" + node.getUuid(), null);
            if (svgId != null) nodeToSvgMap.put(node.getUuid(), svgId);
        }
        return svgId;
    }

    public void autoMapNodeToCurrentSvg(ProvisionedMeshNode node) {
        if (node == null) return;
        String svgId = getSelectedSvgDeviceId();
        if (svgId == null) { Log.w(TAG, "autoMapNodeToCurrentSvg: svgId is null"); return; }
        mapNodeToSvg(node.getUuid(), svgId);
    }

    // =========================================================================
    // CLIENT PUBLISH ADDRESS MAPPING
    // =========================================================================

    public void saveServerPublishMapping(@NonNull String serverSvgDeviceId, int serverElementIndex,
                                         @NonNull String clientSvgDeviceId, int clientElementIndex) {
        String key = KEY_CLIENT_ADDRESS_MAPPING_PREFIX + serverSvgDeviceId + "_" + serverElementIndex;
        prefs.edit().putString(key, clientSvgDeviceId + ":" + clientElementIndex).apply();
        Log.d(TAG, "✅ saveServerPublishMapping: " + serverSvgDeviceId
                + "[" + serverElementIndex + "] → " + clientSvgDeviceId + "[" + clientElementIndex + "]");
    }

    public void saveClientToServerMapping(@NonNull String clientSvgId,
                                          int elementIndex,
                                          @NonNull String serverSvgId) {
        String key = "client_to_server_" + clientSvgId + "_" + elementIndex;
        prefs.edit().putString(key, serverSvgId).apply();
        Log.d(TAG, "✅ Client → Server mapping saved: "
                + clientSvgId + "[" + elementIndex + "] → " + serverSvgId);
    }

    public String getServerSvgIdForClient(@NonNull String clientSvgId, int elementIndex) {
        String key = "client_to_server_" + clientSvgId + "_" + elementIndex;
        String serverSvgId = prefs.getString(key, null);
        Log.d(TAG, "🔍 getServerSvgIdForClient: "
                + clientSvgId + "[" + elementIndex + "] → " + serverSvgId);
        return serverSvgId;
    }

    // =========================================================================
    // PROVISIONING HELPERS
    // =========================================================================

    public void onClientProvisioned(@NonNull ProvisionedMeshNode clientNode,
                                    @NonNull String svgDeviceId) {
        List<Element> sorted = new ArrayList<>(clientNode.getElements().values());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            sorted.sort((a, b) -> Integer.compare(a.getElementAddress(), b.getElementAddress()));
        }
        Map<Integer, Integer> elementAddresses = new HashMap<>();
        for (int i = 0; i < sorted.size() && i < 40; i++) {
            elementAddresses.put(i + 1, sorted.get(i).getElementAddress());
        }
        saveAllClientElementAddresses(svgDeviceId, elementAddresses);
        Log.d(TAG, "✅ onClientProvisioned: saved "
                + elementAddresses.size() + " for " + svgDeviceId);
    }

    // =========================================================================
    // NETWORK
    // =========================================================================

    public LiveData<String> getNetworkLoadState()  { return mNrfMeshRepository.getNetworkLoadState(); }
    public LiveData<String> getNetworkExportState() { return networkExportState; }
    public void setSelectedGroup(final int address) { mNrfMeshRepository.setSelectedGroup(address); }

    public void exportMeshNetwork(@NonNull final OutputStream stream) {
        NetworkExportUtils.exportMeshNetwork(getMeshManagerApi(), stream, this);
    }
    public void exportMeshNetwork() {
        NetworkExportUtils.exportMeshNetwork(getMeshManagerApi(),
                NrfMeshRepository.EXPORT_PATH,
                getNetworkLiveData().getNetworkName() + ".json", this);
    }

    @Override public void onNetworkExported() {
        networkExportState.postValue(
                getNetworkLiveData().getMeshNetwork().getMeshName() + " exported successfully.");
    }
    @Override public void onNetworkExportFailed(@NonNull final String error) {
        networkExportState.postValue(error);
    }

    // =========================================================================
    // SVG URI
    // =========================================================================

    public LiveData<Uri> getSvgUri()  { return svgUri; }
    public Uri getSvgUriValue()       { return svgUri.getValue(); }
    public boolean hasSvg()           { return svgUri.getValue() != null; }

    public void setSvgUri(@NonNull Uri uri) {
        svgUri.setValue(uri);
        prefs.edit().putString(KEY_SVG_URI, uri.toString()).apply();
    }
    public void clearSvgUri() {
        svgUri.setValue(null);
        prefs.edit().remove(KEY_SVG_URI).apply();
    }

    // =========================================================================
    // PROXY
    // =========================================================================

    public LiveData<Boolean> getProxyEnabled() { return proxyEnabled; }
    public boolean isProxyEnabled() { Boolean v = proxyEnabled.getValue(); return v != null && v; }
    public void setProxyEnabled(boolean enabled) {
        proxyEnabled.setValue(enabled);
        prefs.edit().putBoolean(KEY_PROXY_ENABLED, enabled).apply();
    }

    // =========================================================================
    // DEVICE NAME FILTER
    // =========================================================================

    public void setDeviceNameFilter(String filter) {
        prefs.edit().putString("device_name_filter", filter).apply();
    }
    public String getDeviceNameFilterValue() {
        return prefs.getString("device_name_filter", "");
    }

    // =========================================================================
    // SELECTED DEVICE
    // =========================================================================

    public LiveData<String> getSelectedDevice()    { return selectedDevice; }
    public String getSelectedDeviceValue() {
        String v = selectedDevice.getValue();
        return v != null ? v : DEFAULT_SELECTED_DEVICE;
    }
    public boolean isDeviceSelected(String deviceName) {
        return deviceName != null && deviceName.equals(getSelectedDeviceValue());
    }
    public void setSelectedDevice(String device) {
        if (device == null) device = DEFAULT_SELECTED_DEVICE;
        selectedDevice.setValue(device);
        prefs.edit().putString(KEY_SELECTED_DEVICE, device).apply();
    }
    public void clearSelectedDevice() { setSelectedDevice(DEFAULT_SELECTED_DEVICE); }

    // =========================================================================
    // SIGNAL THRESHOLD
    // =========================================================================

    public LiveData<Integer> getSignalThreshold() { return signalThreshold; }
    public int getSignalThresholdValue() {
        Integer v = signalThreshold.getValue();
        return v != null ? v : DevicesAdapter.SIGNAL_DEFAULT;
    }
    public void setSignalThreshold(int threshold) {
        int sanitized = (threshold == DevicesAdapter.SIGNAL_100)
                ? DevicesAdapter.SIGNAL_100 : DevicesAdapter.SIGNAL_DEFAULT;
        signalThreshold.setValue(sanitized);
        prefs.edit().putInt(KEY_SIGNAL_THRESHOLD, sanitized).apply();
    }
    public void clearSignalThreshold() { setSignalThreshold(DevicesAdapter.SIGNAL_DEFAULT); }

    // =========================================================================
    // AUTO APP KEY
    // =========================================================================

    @Nullable
    public ApplicationKey getDefaultAppKey() {
        try {
            MeshNetwork network = getNetworkLiveData().getMeshNetwork();
            if (network == null) return null;
            List<ApplicationKey> appKeys = network.getAppKeys();
            if (appKeys == null || appKeys.isEmpty()) return null;
            return appKeys.get(0);
        } catch (Exception e) { return null; }
    }

    public boolean isDefaultAppKeyBound(@NonNull final ProvisionedMeshNode node) {
        ApplicationKey key = getDefaultAppKey();
        if (key == null) return false;
        for (NodeKey k : node.getAddedAppKeys()) {
            if (k.getIndex() == key.getKeyIndex()) return true;
        }
        return false;
    }

    public boolean isAutoAppKeyDone(int unicastAddress) {
        return prefs.getBoolean("app_key_done_" + unicastAddress, false);
    }
    public void setAutoAppKeyDone(int unicastAddress) {
        prefs.edit().putBoolean("app_key_done_" + unicastAddress, true).apply();
    }

    // =========================================================================
    // FILTERED DEVICES
    // =========================================================================

    public LiveData<List<ExtendedBluetoothDevice>> getFilteredDevices() { return filteredDevices; }
    public List<ExtendedBluetoothDevice> getFilteredDevicesValue() {
        List<ExtendedBluetoothDevice> v = filteredDevices.getValue();
        return v != null ? v : new ArrayList<>();
    }
    public void setFilteredDevices(List<ExtendedBluetoothDevice> devices) {
        filteredDevices.setValue(devices != null ? devices : new ArrayList<>());
    }
    public void clearFilteredDevices() { filteredDevices.setValue(new ArrayList<>()); }

    // =========================================================================
    // ALL UNPROVISIONED DEVICES
    // =========================================================================

    public LiveData<List<ExtendedBluetoothDevice>> getAllUnprovisionedDevices() {
        return allUnprovisionedDevices;
    }
    public List<ExtendedBluetoothDevice> getAllUnprovisionedDevicesValue() {
        List<ExtendedBluetoothDevice> v = allUnprovisionedDevices.getValue();
        return v != null ? v : new ArrayList<>();
    }
    public void setAllUnprovisionedDevices(List<ExtendedBluetoothDevice> devices) {
        allUnprovisionedDevices.setValue(devices != null ? devices : new ArrayList<>());
    }
    public void addUnprovisionedDevice(ExtendedBluetoothDevice device) {
        if (device == null) return;
        List<ExtendedBluetoothDevice> current = getAllUnprovisionedDevicesValue();
        if (!current.contains(device)) {
            current.add(device);
            allUnprovisionedDevices.setValue(current);
        }
    }
    public void clearAllUnprovisionedDevices() { allUnprovisionedDevices.setValue(new ArrayList<>()); }

    // =========================================================================
    // FILTER UTILITY
    // =========================================================================

    public boolean isFilterActive() {
        return !getSelectedDeviceValue().equals(DEFAULT_SELECTED_DEVICE)
                || getSignalThresholdValue() != DevicesAdapter.SIGNAL_DEFAULT;
    }

    public String getActiveFilterDescription() {
        StringBuilder sb = new StringBuilder();
        if (!getSelectedDeviceValue().equals(DEFAULT_SELECTED_DEVICE))
            sb.append("Device: ").append(getSelectedDeviceValue());
        if (getSignalThresholdValue() == DevicesAdapter.SIGNAL_100) {
            if (sb.length() > 0) sb.append(" | ");
            sb.append("Signal ≥ 100%");
        }
        return sb.length() > 0 ? "Filter: " + sb : "No filter active";
    }

    public void resetAllFilters() {
        clearSelectedDevice();
        clearSignalThreshold();
        applyCurrentFilter();
    }

    public List<ExtendedBluetoothDevice> applyFilter(List<ExtendedBluetoothDevice> devices) {
        if (devices == null) return new ArrayList<>();
        String  nameFilter      = getSelectedDeviceValue();
        int     threshold       = getSignalThresholdValue();
        boolean hasDeviceFilter = !nameFilter.equals(DEFAULT_SELECTED_DEVICE);
        boolean hasSignalFilter = threshold != DevicesAdapter.SIGNAL_DEFAULT;
        if (!hasDeviceFilter && !hasSignalFilter) return new ArrayList<>(devices);
        List<ExtendedBluetoothDevice> filtered = new ArrayList<>();
        String lowerFilter = nameFilter.toLowerCase();
        for (ExtendedBluetoothDevice device : devices) {
            boolean deviceOk = !hasDeviceFilter
                    || (device.getName() != null
                    && device.getName().toLowerCase().contains(lowerFilter));
            boolean signalOk = !hasSignalFilter || matchesSignalThreshold(device, threshold);
            if (deviceOk && signalOk) filtered.add(device);
        }
        return filtered;
    }

    private boolean matchesSignalThreshold(@NonNull ExtendedBluetoothDevice device, int threshold) {
        int rssiPercent = (int) (100.0f * (127.0f + device.getRssi()) / (127.0f + 20.0f));
        return rssiPercent >= threshold;
    }

    public void applyCurrentFilter() {
        setFilteredDevices(applyFilter(getAllUnprovisionedDevicesValue()));
    }

    // =========================================================================
    // SCANNER REPOSITORY
    // =========================================================================

    public ScannerRepository getScannerRepository() { return mScannerRepository; }
    public LiveData<ScannerLiveData> getScannerResults() {
        return mScannerRepository.getScannerResults();
    }

    // =========================================================================
    // SELECTED SVG DEVICE ID (transient)
    // =========================================================================

    public LiveData<String> getSelectedSvgDeviceIdLiveData() { return mSelectedSvgDeviceId; }
    @Nullable public String getSelectedSvgDeviceId()         { return mSelectedSvgDeviceId.getValue(); }
    public void setSelectedSvgDeviceId(@Nullable String svgDeviceId) {
        mSelectedSvgDeviceId.setValue(svgDeviceId);
        Log.d(TAG, "setSelectedSvgDeviceId: " + svgDeviceId);
    }
    public void clearSelectedSvgDeviceId() { mSelectedSvgDeviceId.setValue(null); }

    // =========================================================================
    // SERVER SVG DEVICE ID (persistent)
    // =========================================================================

    public void setServerSvgDeviceId(@Nullable String serverSvgDeviceId) {
        mServerSvgDeviceId.setValue(serverSvgDeviceId);
        if (serverSvgDeviceId != null)
            prefs.edit().putString(KEY_SERVER_SVG_DEVICE_ID, serverSvgDeviceId).apply();
        else
            prefs.edit().remove(KEY_SERVER_SVG_DEVICE_ID).apply();
        Log.d(TAG, "setServerSvgDeviceId: " + serverSvgDeviceId);
    }
    public LiveData<String> getServerSvgDeviceIdLiveData() { return mServerSvgDeviceId; }
    @Nullable public String getServerSvgDeviceId()         { return mServerSvgDeviceId.getValue(); }
    public void clearServerSvgDeviceId() {
        mServerSvgDeviceId.setValue(null);
        prefs.edit().remove(KEY_SERVER_SVG_DEVICE_ID).apply();
    }

    // =========================================================================
    // UTILITY
    // =========================================================================

    @Nullable
    public ProvisionedMeshNode findNodeBySvgDeviceId(@Nullable String svgDeviceId) {
        if (svgDeviceId == null) return null;
        try {
            MeshNetwork network = getNetworkLiveData().getMeshNetwork();
            if (network == null) return null;
            List<ProvisionedMeshNode> nodes = network.getNodes();
            if (nodes == null || nodes.isEmpty()) return null;
            for (ProvisionedMeshNode node : nodes) {
                if (svgDeviceId.equalsIgnoreCase(node.getNodeName())) return node;
            }
            if (nodes.size() == 1) return nodes.get(0);
        } catch (Exception e) { Log.e(TAG, "findNodeBySvgDeviceId error", e); }
        return null;
    }

    public boolean selectNodeBySvgDeviceId(@Nullable String svgDeviceId) {
        ProvisionedMeshNode node = findNodeBySvgDeviceId(svgDeviceId);
        if (node != null) { setSelectedMeshNode(node); return true; }
        return false;
    }

    public int getProvisionedNodeCount() {
        try {
            MeshNetwork network = getNetworkLiveData().getMeshNetwork();
            if (network == null) return 0;
            List<ProvisionedMeshNode> nodes = network.getNodes();
            return nodes != null ? nodes.size() : 0;
        } catch (Exception e) { return 0; }
    }

    @Nullable
    public List<ProvisionedMeshNode> getAllProvisionedNodes() {
        try {
            MeshNetwork network = getNetworkLiveData().getMeshNetwork();
            if (network == null) return null;
            return network.getNodes();
        } catch (Exception e) {
            Log.e(TAG, "getAllProvisionedNodes error", e);
            return null;
        }
    }

    // =========================================================================
    // Helper class
    // =========================================================================

    public static class PublishConfig {
        private final int    address;
        private final String clientDeviceId;
        private final int    clientElementIndex;

        public PublishConfig(int address, String clientDeviceId, int clientElementIndex) {
            this.address            = address;
            this.clientDeviceId     = clientDeviceId;
            this.clientElementIndex = clientElementIndex;
        }

        public int    getAddress()            { return address; }
        public String getClientDeviceId()     { return clientDeviceId; }
        public int    getClientElementIndex() { return clientElementIndex; }
    }
    public LiveData<Boolean> isAutoSetupInProgress() {
        return mNrfMeshRepository.isAutoSetupInProgress();
    }
}