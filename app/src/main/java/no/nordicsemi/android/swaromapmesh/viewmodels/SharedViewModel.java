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

    // ── SharedPreferences keys ────────────────────────────────────────────────
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

    // ── LiveData fields ───────────────────────────────────────────────────────
    private final MutableLiveData<Boolean> proxyEnabled     = new MutableLiveData<>();
    private final MutableLiveData<String>  selectedDevice   = new MutableLiveData<>(DEFAULT_SELECTED_DEVICE);
    private final MutableLiveData<Integer> signalThreshold  = new MutableLiveData<>(DevicesAdapter.SIGNAL_DEFAULT);
    private final MutableLiveData<Uri>     svgUri           = new MutableLiveData<>();
    private final MutableLiveData<String>  selectedDeviceId = new MutableLiveData<>();
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

        mScannerRepository = scannerRepository;
        scannerRepository.registerBroadcastReceivers();

        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        ClientElementStore.init(context);

        proxyEnabled.setValue(prefs.getBoolean(KEY_PROXY_ENABLED, true));
        selectedDevice.setValue(prefs.getString(KEY_SELECTED_DEVICE, DEFAULT_SELECTED_DEVICE));
        signalThreshold.setValue(prefs.getInt(KEY_SIGNAL_THRESHOLD, DevicesAdapter.SIGNAL_DEFAULT));

        final String savedSvgUri = prefs.getString(KEY_SVG_URI, null);
        if (savedSvgUri != null) svgUri.setValue(Uri.parse(savedSvgUri));

        Set<String> savedProvisioned = prefs.getStringSet(KEY_PROVISIONED_DEVICES, new HashSet<>());
        provisionedDeviceIds.setValue(new HashSet<>(savedProvisioned));

        String savedServerId = prefs.getString(KEY_SERVER_SVG_DEVICE_ID, null);
        if (savedServerId != null) mServerSvgDeviceId.setValue(savedServerId);

        // ✅ Sync prefs with mesh on first node load — clears any stale provisioned IDs
        getNodes().observeForever(nodes -> syncProvisionedWithMeshNetwork());
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        if (!mNrfMeshRepository.getBleMeshManager().isConnected()) {
            mNrfMeshRepository.disconnect();
        }
        mScannerRepository.unregisterBroadcastReceivers();
    }

    // =========================================================================
    // SYNC — prefs ke stale provisioned IDs ko mesh ke saath clean karo
    // =========================================================================

    /**
     * Prefs mein jo provisioned IDs hain unhe mesh network ke actual nodes se match karo.
     * Jo IDs mesh mein nahi hain — unhe prefs se hata do.
     *
     * Ye tab call hota hai jab nodes LiveData update hoti hai (load + delete dono ke baad).
     */
    private void syncProvisionedWithMeshNetwork() {
        Set<String> saved = new HashSet<>(
                prefs.getStringSet(KEY_PROVISIONED_DEVICES, new HashSet<>()));

        if (saved.isEmpty()) return;

        List<ProvisionedMeshNode> meshNodes = getAllProvisionedNodes();

        // Mesh empty hai (provisioner ke alawa koi node nahi) — sab clear karo
        if (meshNodes == null || meshNodes.isEmpty()) {
            prefs.edit().putStringSet(KEY_PROVISIONED_DEVICES, new HashSet<>()).apply();
            provisionedDeviceIds.setValue(new HashSet<>());
            Log.d(TAG, "🧹 syncProvisioned: mesh empty — cleared all");
            return;
        }

        // Mesh mein jo valid IDs hain unka set banao (nodeName + svgId dono)
        Set<String> validMeshIds = new HashSet<>();
        for (ProvisionedMeshNode node : meshNodes) {
            if (node.getNodeName() != null) {
                validMeshIds.add(node.getNodeName());
            }
            // svgId mapping bhi check karo
            String svgId = getSvgIdFromNode(node);
            if (svgId != null) {
                validMeshIds.add(svgId);
            }
        }

        // Sirf valid IDs rakho
        Set<String> cleaned = new HashSet<>();
        for (String id : saved) {
            if (validMeshIds.contains(id)) {
                cleaned.add(id);
            } else {
                Log.d(TAG, "🧹 syncProvisioned: removing stale id → " + id);
            }
        }

        if (cleaned.size() != saved.size()) {
            prefs.edit().putStringSet(KEY_PROVISIONED_DEVICES, cleaned).apply();
            provisionedDeviceIds.setValue(cleaned);
            Log.d(TAG, "✅ syncProvisioned done — remaining: " + cleaned);
        }
    }

    // =========================================================================
    // ELEMENT ADDRESS MAPPING (Client Side)
    // =========================================================================

    public void saveClientElementAddress(
            @NonNull String svgDeviceId,
            int elementIndex,
            int elementAddress) {
        String key = KEY_ELEMENT_ADDRESS_MAPPING_PREFIX + svgDeviceId + "_" + elementIndex;
        prefs.edit().putInt(key, elementAddress).apply();
        Log.d(TAG, "✅ saveClientElementAddress: device=" + svgDeviceId
                + " index=" + elementIndex
                + " address=0x" + String.format("%04X", elementAddress));
    }

    public int getClientElementAddress(@NonNull String svgDeviceId, int elementIndex) {
        String key     = KEY_ELEMENT_ADDRESS_MAPPING_PREFIX + svgDeviceId + "_" + elementIndex;
        int    address = prefs.getInt(key, -1);
        if (address == -1) address = ClientElementStore.get(svgDeviceId, elementIndex);
        if (address != -1) {
            Log.d(TAG, "getClientElementAddress: device=" + svgDeviceId
                    + " index=" + elementIndex
                    + " address=0x" + String.format("%04X", address));
        } else {
            Log.w(TAG, "getClientElementAddress: NOT FOUND device=" + svgDeviceId
                    + " index=" + elementIndex);
        }
        return address;
    }

    public void saveAllClientElementAddresses(
            @NonNull String svgDeviceId,
            @NonNull Map<Integer, Integer> elementAddresses) {
        for (Map.Entry<Integer, Integer> entry : elementAddresses.entrySet()) {
            saveClientElementAddress(svgDeviceId, entry.getKey(), entry.getValue());
        }
        Log.d(TAG, "✅ saveAllClientElementAddresses: saved "
                + elementAddresses.size() + " for: " + svgDeviceId);
    }

    public Map<Integer, Integer> getAllClientElementAddresses(@NonNull String svgDeviceId) {
        Map<Integer, Integer> result = new HashMap<>();
        for (int i = 1; i <= 40; i++) {
            int address = getClientElementAddress(svgDeviceId, i);
            if (address != -1) result.put(i, address);
        }
        return result;
    }

    // =========================================================================
    // NODE DELETE
    // =========================================================================

    public void removeNodeFromNetwork(ProvisionedMeshNode node) {
        if (node == null) return;
        List<ProvisionedMeshNode> nodes = getAllProvisionedNodes();
        if (nodes != null) {
            nodes.remove(node);
            Log.d(TAG, "🔥 Node removed from network: " + node.getNodeName());
        }
    }

    public boolean fullyDeleteNode(@NonNull ProvisionedMeshNode adapterNode) {
        if (adapterNode == null) return false;

        // Step 1: Real node dhundo by UUID
        ProvisionedMeshNode realNode = null;
        List<ProvisionedMeshNode> nodes = getAllProvisionedNodes();
        if (nodes != null) {
            for (ProvisionedMeshNode n : nodes) {
                if (n.getUuid().equals(adapterNode.getUuid())) {
                    realNode = n;
                    break;
                }
            }
        }

        if (realNode == null) {
            Log.e(TAG, "❌ Node not found in network");
            return false;
        }

        String nodeName = realNode.getNodeName();
        String svgId    = getSvgIdFromNode(realNode);
        Log.d(TAG, "fullyDeleteNode: nodeName=" + nodeName + " svgId=" + svgId);

        // Step 2: Mesh se delete karo
        boolean deleted = getNetworkLiveData().getMeshNetwork().deleteNode(realNode);
        if (!deleted) {
            Log.e(TAG, "❌ Mesh delete failed");
            return false;
        }
        Log.d(TAG, "🔥 Mesh delete success: " + nodeName);

        // Step 3: svgId se unmark karo
        if (svgId != null) {
            unmarkDeviceProvisioned(svgId);
        }

        // Step 4: nodeName se bhi unmark karo (agar svgId alag tha ya null tha)
        if (nodeName != null && !nodeName.equals(svgId)) {
            unmarkDeviceProvisioned(nodeName);
        }

        // Step 5: Puri provisioned list scan karo —
        // koi bhi entry jo nodeName ya svgId se match kare use hatao
        removeAllMatchingProvisioned(nodeName, svgId);

        // Step 6: Memory + prefs mapping clean karo
        nodeToSvgMap.remove(realNode.getUuid());
        prefs.edit().remove("node_svg_" + realNode.getUuid()).apply();

        // Step 7: Force refresh — prefs se fresh read
        forceSvgRefresh();

        Log.d(TAG, "✅ fullyDeleteNode complete: " + nodeName);
        return true;
    }

    // =========================================================================
    // PROVISIONED DEVICE IDs (PERSISTENT)
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
        if (provisionedDeviceIds.getValue() != null)
            current.addAll(provisionedDeviceIds.getValue());
        current.add(svgDeviceId);
        provisionedDeviceIds.setValue(current);
        prefs.edit().putStringSet(KEY_PROVISIONED_DEVICES, new HashSet<>(current)).apply();
        Log.d(TAG, "✅ markDeviceProvisioned: " + svgDeviceId);
    }

    /**
     * Ek specific svgDeviceId ko provisioned_devices prefs se hatao.
     * KEY_PROVISIONED_DEVICES use karta hai — markDeviceProvisioned ke same key.
     */
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

    /**
     * Provisioned set mein se wo saari entries hatao jo nodeName ya svgId se
     * contain/equal match karti hain.
     * Yeh `removeAllRelatedProvisioned` ka improved version hai.
     */
    private void removeAllMatchingProvisioned(@Nullable String nodeName,
                                              @Nullable String svgId) {
        Set<String> current = new HashSet<>(
                prefs.getStringSet(KEY_PROVISIONED_DEVICES, new HashSet<>()));

        Set<String> toRemove = new HashSet<>();
        for (String id : current) {
            boolean matchesNode = nodeName != null &&
                    (id.equals(nodeName) || id.contains(nodeName));
            boolean matchesSvg  = svgId != null &&
                    (id.equals(svgId) || id.contains(svgId));
            if (matchesNode || matchesSvg) {
                toRemove.add(id);
                Log.d(TAG, "🧹 removeAllMatchingProvisioned: removing → " + id);
            }
        }

        if (!toRemove.isEmpty()) {
            current.removeAll(toRemove);
            prefs.edit().putStringSet(KEY_PROVISIONED_DEVICES, current).apply();
            provisionedDeviceIds.setValue(new HashSet<>(current));
            Log.d(TAG, "✅ removeAllMatchingProvisioned done — remaining: " + current);
        }
    }

    /**
     * Prefs se fresh read karke LiveData update karo.
     */
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
        String uuid  = node.getUuid();
        String svgId = nodeToSvgMap.get(uuid);
        if (svgId == null) {
            svgId = prefs.getString("node_svg_" + uuid, null);
            if (svgId != null) {
                nodeToSvgMap.put(uuid, svgId);
                Log.d(TAG, "Restored mapping: " + uuid + " → " + svgId);
            }
        }
        return svgId;
    }

    public void autoMapNodeToCurrentSvg(ProvisionedMeshNode node) {
        if (node == null) return;
        String svgId = getSelectedSvgDeviceId();
        if (svgId == null) {
            Log.w(TAG, "autoMapNodeToCurrentSvg: svgId is null");
            return;
        }
        mapNodeToSvg(node.getUuid(), svgId);
    }

    // =========================================================================
    // CLIENT PUBLISH ADDRESS MAPPING (Server Side)
    // =========================================================================

    public void saveServerPublishMapping(
            @NonNull String serverSvgDeviceId,
            int serverElementIndex,
            @NonNull String clientSvgDeviceId,
            int clientElementIndex) {
        String key          = KEY_CLIENT_ADDRESS_MAPPING_PREFIX + serverSvgDeviceId + "_" + serverElementIndex;
        String mappingValue = clientSvgDeviceId + ":" + clientElementIndex;
        prefs.edit().putString(key, mappingValue).apply();
        Log.d(TAG, "✅ saveServerPublishMapping: server=" + serverSvgDeviceId
                + "[" + serverElementIndex + "] → client=" + clientSvgDeviceId
                + "[" + clientElementIndex + "]");
    }

    public int getServerPublishAddress(
            @NonNull String serverSvgDeviceId,
            int serverElementIndex) {
        String key          = KEY_CLIENT_ADDRESS_MAPPING_PREFIX + serverSvgDeviceId + "_" + serverElementIndex;
        String mappingValue = prefs.getString(key, null);
        if (mappingValue == null) return -1;
        String[] parts = mappingValue.split(":");
        if (parts.length != 2) return -1;
        try {
            int clientElementIndex = Integer.parseInt(parts[1]);
            return getClientElementAddress(parts[0], clientElementIndex);
        } catch (NumberFormatException e) { return -1; }
    }

    @Nullable
    public PublishConfig getServerPublishConfig(
            @NonNull String serverSvgDeviceId,
            int serverElementIndex) {
        int address = getServerPublishAddress(serverSvgDeviceId, serverElementIndex);
        if (address == -1) return null;
        String key          = KEY_CLIENT_ADDRESS_MAPPING_PREFIX + serverSvgDeviceId + "_" + serverElementIndex;
        String mappingValue = prefs.getString(key, null);
        if (mappingValue == null) return null;
        String[] parts = mappingValue.split(":");
        if (parts.length != 2) return null;
        return new PublishConfig(address, parts[0], Integer.parseInt(parts[1]));
    }

    // =========================================================================
    // AUTO MAPPING LOGIC
    // =========================================================================

    public int autoMapServerToClientPublishAddress(
            @NonNull String serverSvgDeviceId,
            int serverElementIndex) {

        int existingMapping = getServerPublishAddress(serverSvgDeviceId, serverElementIndex);
        if (existingMapping != -1) return existingMapping;

        List<ProvisionedMeshNode> allNodes = getAllProvisionedNodes();
        if (allNodes == null || allNodes.isEmpty()) return -1;

        for (ProvisionedMeshNode node : allNodes) {
            if (serverSvgDeviceId.equalsIgnoreCase(node.getNodeName())) continue;
            boolean hasClientModel = false;
            for (Element element : node.getElements().values()) {
                for (MeshModel model : element.getMeshModels().values()) {
                    if (model.getModelId() == 0x1001) { hasClientModel = true; break; }
                }
                if (hasClientModel) break;
            }
            if (!hasClientModel) continue;
            int clientAddress = getClientElementAddress(node.getNodeName(), serverElementIndex);
            if (clientAddress != -1) {
                saveServerPublishMapping(serverSvgDeviceId, serverElementIndex,
                        node.getNodeName(), serverElementIndex);
                return clientAddress;
            }
        }
        return -1;
    }

    // =========================================================================
    // ELEMENT ID MANAGEMENT
    // =========================================================================

    public void saveElementId(@NonNull String svgDeviceId, @NonNull String elementId) {
        prefs.edit().putString(KEY_ELEMENT_ID_MAPPING_PREFIX + svgDeviceId, elementId).apply();
        Log.d(TAG, "✅ saveElementId: device=" + svgDeviceId + " elementId=" + elementId);
    }

    @Nullable
    public String getElementId(@NonNull String svgDeviceId) {
        return prefs.getString(KEY_ELEMENT_ID_MAPPING_PREFIX + svgDeviceId, null);
    }

    public int getElementIdAsInt(@NonNull String svgDeviceId) {
        String s = getElementId(svgDeviceId);
        if (s == null) return -1;
        try { return Integer.parseInt(s.trim()); }
        catch (NumberFormatException e) { return -1; }
    }

    // =========================================================================
    // PROVISIONING HELPERS
    // =========================================================================

    public void onClientProvisioned(
            @NonNull ProvisionedMeshNode clientNode,
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
        Log.d(TAG, "✅ onClientProvisioned: saved " + elementAddresses.size() + " for " + svgDeviceId);
    }

    public Map<Integer, Integer> onServerProvisioned(
            @NonNull ProvisionedMeshNode serverNode,
            @NonNull String svgDeviceId) {
        Map<Integer, Integer> assignedAddresses = new HashMap<>();
        int serverElementId = getElementIdAsInt(svgDeviceId);
        if (serverElementId == -1) return assignedAddresses;
        int publishAddress = autoMapServerToClientPublishAddress(svgDeviceId, serverElementId);
        if (publishAddress != -1) assignedAddresses.put(serverElementId, publishAddress);
        return assignedAddresses;
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
    public boolean hasSvg()          { return svgUri.getValue() != null; }

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
    public boolean isProxyEnabled() {
        Boolean v = proxyEnabled.getValue();
        return v != null && v;
    }
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

    public LiveData<String> getSelectedDevice() { return selectedDevice; }
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
                    || (device.getName() != null && device.getName().toLowerCase().contains(lowerFilter));
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

    @Nullable
    public String getSelectedSvgDeviceId() { return mSelectedSvgDeviceId.getValue(); }

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
    @Nullable public String getServerSvgDeviceId() { return mServerSvgDeviceId.getValue(); }
    public void clearServerSvgDeviceId() {
        mServerSvgDeviceId.setValue(null);
        prefs.edit().remove(KEY_SERVER_SVG_DEVICE_ID).apply();
    }

    // =========================================================================
    // LEGACY
    // =========================================================================

    public int resolveServerPublishAddress(
            @NonNull String serverSvgDeviceId,
            @NonNull ProvisionedMeshNode clientNode) {

        int serverElementId = getElementIdAsInt(serverSvgDeviceId);
        if (serverElementId != -1) {
            int address = autoMapServerToClientPublishAddress(serverSvgDeviceId, serverElementId);
            if (address != -1) return address;
        }

        final String elementIdStr = prefs.getString("element_id_" + serverSvgDeviceId, null);
        if (elementIdStr == null || elementIdStr.isEmpty()) return -1;

        int targetIndex;
        try { targetIndex = Integer.parseInt(elementIdStr.trim()); }
        catch (NumberFormatException e) { return -1; }

        List<Element> sorted = new ArrayList<>(clientNode.getElements().values());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            sorted.sort((a, b) -> Integer.compare(a.getElementAddress(), b.getElementAddress()));
        }
        if (targetIndex < 0 || targetIndex >= sorted.size()) return -1;
        return sorted.get(targetIndex).getElementAddress();
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
}