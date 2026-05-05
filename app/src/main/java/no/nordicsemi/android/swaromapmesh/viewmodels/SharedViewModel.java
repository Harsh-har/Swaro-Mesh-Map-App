package no.nordicsemi.android.swaromapmesh.viewmodels;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
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
import no.nordicsemi.android.swaromapmesh.swajaui.AutoPublicationHelper;
import no.nordicsemi.android.swaromapmesh.transport.Element;
import no.nordicsemi.android.swaromapmesh.transport.MeshMessage;
import no.nordicsemi.android.swaromapmesh.transport.ProvisionedMeshNode;
import no.nordicsemi.android.swaromapmesh.utils.NetworkExportUtils;

@HiltViewModel
public class SharedViewModel extends BaseViewModel
        implements NetworkExportUtils.NetworkExportCallbacks {

    private static final String TAG = "SharedViewModel";
    private static final String PREFS_NAME              = "mesh_prefs";
    private static final String KEY_PROXY_ENABLED       = "proxy_enabled";
    private static final String KEY_SELECTED_DEVICE     = "selected_device";
    private static final String KEY_SIGNAL_THRESHOLD    = "signal_threshold";
    private static final String KEY_SVG_URI             = "svg_uri";
    private static final String DEFAULT_SELECTED_DEVICE = "All Device";

    // Publication settings (same as AreaClientListActivity)
    private static final int GENERIC_ONOFF_CLIENT = 0x1001;
    private static final int GENERIC_ONOFF_SERVER = 0x1000;

    private final SharedPreferences prefs;

    // ── Application context (for publication setup) ────────────────────────
    private final Context mContext;

    // ── Repositories ───────────────────────────────────────────────────────
    private final ScannerRepository      mScannerRepository;
    private final SingleLiveEvent<String> networkExportState = new SingleLiveEvent<>();

    // ── LiveData ───────────────────────────────────────────────────────────
    private final MutableLiveData<Boolean>     proxyEnabled         = new MutableLiveData<>();
    private final MutableLiveData<String>      selectedDevice       = new MutableLiveData<>(DEFAULT_SELECTED_DEVICE);
    private final MutableLiveData<Integer>     signalThreshold      = new MutableLiveData<>(DevicesAdapter.SIGNAL_DEFAULT);
    private final MutableLiveData<Uri>         svgUri               = new MutableLiveData<>();
    private final MutableLiveData<Set<String>> provisionedDeviceIds = new MutableLiveData<>(new HashSet<>());
    private final MutableLiveData<String>      focusAreaId          = new MutableLiveData<>();
    private final MutableLiveData<String>      mSelectedSvgDeviceId = new MutableLiveData<>();
    private final MutableLiveData<String>      mServerSvgDeviceId   = new MutableLiveData<>();

    private final MutableLiveData<List<ExtendedBluetoothDevice>> filteredDevices =
            new MutableLiveData<>(new ArrayList<>());
    private final MutableLiveData<List<ExtendedBluetoothDevice>> allUnprovisionedDevices =
            new MutableLiveData<>(new ArrayList<>());

    // ── In-memory UUID → SVG ID map ────────────────────────────────────────
    private final Map<String, String> nodeToSvgMap = new HashMap<>();

    // ── Last provisioned node (set by provisioning flow) ──────────────────
    private ProvisionedMeshNode lastProvisionedNode;

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

        mContext = context;
        ClientServerElementStore.init(context);

        mScannerRepository = scannerRepository;
        scannerRepository.registerBroadcastReceivers();

        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        // ── Restore simple prefs ───────────────────────────────────────────
        proxyEnabled.setValue(prefs.getBoolean(KEY_PROXY_ENABLED, true));
        selectedDevice.setValue(prefs.getString(KEY_SELECTED_DEVICE, DEFAULT_SELECTED_DEVICE));
        signalThreshold.setValue(prefs.getInt(KEY_SIGNAL_THRESHOLD, DevicesAdapter.SIGNAL_DEFAULT));

        final String savedSvgUri = prefs.getString(KEY_SVG_URI, null);
        if (savedSvgUri != null) svgUri.setValue(Uri.parse(savedSvgUri));

        // ── Load provisioned set from Store (single source of truth) ──────
        syncFromStore();

        // ── On node list change → re-sync ─────────────────────────────────
        getNodes().observeForever(nodes -> syncFromStore());

        // ── After network import → re-sync ────────────────────────────────
        mNrfMeshRepository.setOnNetworkImportedCallback(this::onNetworkImported);

        // ── Register auto-setup complete callback → triggers publication ───
        mNrfMeshRepository.setAutoSetupCompleteListener(this::onAutoSetupComplete);
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        if (!mNrfMeshRepository.getBleMeshManager().isConnected()) {
            mNrfMeshRepository.disconnect();
        }
        mScannerRepository.unregisterBroadcastReceivers();
    }

    public void syncFromStore() {
        Set<String> keys = ClientServerElementStore.getProvisionedKeys();
        provisionedDeviceIds.setValue(new HashSet<>(keys));
        Log.d(TAG, "✅ syncFromStore: " + keys.size() + " devices → " + keys);
    }

    // ── Called after mesh network import ──────────────────────────────────
    private void onNetworkImported() {
        List<ProvisionedMeshNode> nodes = getAllProvisionedNodes();
        if (nodes == null || nodes.isEmpty()) {
            Log.w(TAG, "onNetworkImported: no nodes");
            return;
        }
        // Rebuild UUID → SVG map from persisted node_svg_ keys
        for (ProvisionedMeshNode node : nodes) {
            String svgId = prefs.getString("node_svg_" + node.getUuid(), null);
            if (svgId != null) {
                nodeToSvgMap.put(node.getUuid(), svgId);
            }
        }
        syncFromStore();
        new Handler(Looper.getMainLooper())
                .postDelayed(this::forceSvgRefresh, 1000);
        Log.d(TAG, "✅ onNetworkImported: rebuilt " + nodeToSvgMap.size() + " UUID mappings");
    }

    // =========================================================================
    // AUTO PUBLICATION SETUP
    // Called automatically when NrfMeshRepository completes auto-bind for a node
    // =========================================================================

    /**
     * Called by NrfMeshRepository when all AppKey binds are done for a node.
     * Triggers bidirectional publication setup automatically — no Activity needed.
     */
    private void onAutoSetupComplete(@NonNull ProvisionedMeshNode node) {
        Log.d(TAG, "🔔 onAutoSetupComplete: node=0x"
                + String.format("%04X", node.getUnicastAddress())
                + " name=" + node.getNodeName());

        // 500ms delay — store ko settle hone do
        new Handler(Looper.getMainLooper())
                .postDelayed(() -> triggerPublicationSetup(node), 500);
    }

    /**
     * Finds all client-server pairs that need publication, then sets them up.
     */
    private void triggerPublicationSetup(@NonNull ProvisionedMeshNode completedNode) {
        Log.d(TAG, "📡 triggerPublicationSetup: checking all pairs...");

        List<ApplicationKey> appKeys = getNetworkLiveData().getAppKeys();
        if (appKeys == null || appKeys.isEmpty()) {
            Log.e(TAG, "triggerPublicationSetup: no AppKey available");
            return;
        }
        int appKeyIndex = appKeys.get(0).getKeyIndex();

        SharedPreferences meshPrefs = mContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences storePrefs = ClientServerElementStore.getPrefsPublic();
        if (storePrefs == null) {
            Log.e(TAG, "triggerPublicationSetup: storePrefs null");
            return;
        }

        Set<String> provisionedKeys = ClientServerElementStore.getProvisionedKeys();
        if (provisionedKeys.isEmpty()) {
            Log.d(TAG, "triggerPublicationSetup: no provisioned keys");
            return;
        }

        boolean anyScheduled = false;
        long delayOffset = 0;

        for (String clientKey : provisionedKeys) {
            // Normalize the key name (strip area prefix for lookup)
            String keyName = clientKey.contains(":")
                    ? clientKey.split(":")[1].trim().toLowerCase()
                    : clientKey.toLowerCase();
            String areaPrefix = clientKey.contains(":")
                    ? clientKey.split(":")[0].trim().toLowerCase()
                    : "";

            // Find all element_addr_ entries for this clientKey
            Map<Integer, Integer> clientIndexToAddr = new HashMap<>();
            for (Map.Entry<String, ?> e : storePrefs.getAll().entrySet()) {
                String k = e.getKey();
                if (!k.startsWith("element_addr_")) continue;
                String rest = k.substring("element_addr_".length());
                int sep = rest.lastIndexOf("_");
                if (sep == -1) continue;
                String storedName = rest.substring(0, sep).toLowerCase();
                if (!storedName.equals(keyName)) continue;
                try {
                    int idx = Integer.parseInt(rest.substring(sep + 1));
                    int addr = (int) e.getValue();
                    clientIndexToAddr.put(idx, addr);
                } catch (Exception ignored) {}
            }

            if (clientIndexToAddr.isEmpty()) continue;

            for (Map.Entry<Integer, Integer> entry : clientIndexToAddr.entrySet()) {
                int svgElementId = entry.getKey();
                int clientAddr   = entry.getValue();

                // Find matching server via svgElementId + area
                String serverStoreKey = ClientServerElementStore
                        .getKeyBySvgElementIdAndArea(svgElementId, areaPrefix);
                if (serverStoreKey == null) {
                    Log.d(TAG, "  No server key for svgId=" + svgElementId
                            + " area=" + areaPrefix);
                    continue;
                }

                int serverAddr = ClientServerElementStore.getServerUnicastAddress(serverStoreKey);
                if (serverAddr == -1) {
                    Log.d(TAG, "  Server addr not found for key=" + serverStoreKey);
                    continue;
                }

                // Check if server node is actually provisioned
                if (!isNodeInNetwork(serverAddr)) {
                    Log.d(TAG, "  Server 0x" + String.format("%04X", serverAddr)
                            + " not in network yet — skip");
                    continue;
                }

                // Already done?
                if (AutoPublicationHelper.isPublicationSetupComplete(
                        meshPrefs, clientAddr, serverAddr)) {
                    Log.d(TAG, "  Already complete: 0x" + String.format("%04X", clientAddr)
                            + " ↔ 0x" + String.format("%04X", serverAddr));
                    continue;
                }

                Log.d(TAG, "  📤 Scheduling publication: 0x"
                        + String.format("%04X", clientAddr)
                        + " ↔ 0x" + String.format("%04X", serverAddr)
                        + " (delay=" + delayOffset + "ms)");

                final int fClientAddr = clientAddr;
                final int fServerAddr = serverAddr;
                final int fAppKeyIndex = appKeyIndex;
                final long fDelay = delayOffset;

                new Handler(Looper.getMainLooper()).postDelayed(
                        () -> setupPublicationPair(fClientAddr, fServerAddr, fAppKeyIndex),
                        fDelay);

                delayOffset += 3000; // 3 second gap between pairs to avoid BLE collision
                anyScheduled = true;
            }
        }

        if (anyScheduled) {
            Log.d(TAG, "✅ triggerPublicationSetup: pairs scheduled with staggered delays");
        } else {
            Log.d(TAG, "triggerPublicationSetup: nothing to schedule");
        }
    }

    /**
     * Sets up bidirectional publication between one client-server pair.
     */
    private void setupPublicationPair(int clientAddr, int serverAddr, int appKeyIndex) {
        Log.d(TAG, "setupPublicationPair: 0x" + String.format("%04X", clientAddr)
                + " ↔ 0x" + String.format("%04X", serverAddr));

        List<ProvisionedMeshNode> nodes = getAllProvisionedNodes();
        if (nodes == null) {
            Log.e(TAG, "setupPublicationPair: node list null");
            return;
        }

        ProvisionedMeshNode clientNode = null;
        ProvisionedMeshNode serverNode = null;

        for (ProvisionedMeshNode n : nodes) {
            for (Element el : n.getElements().values()) {
                if (el.getElementAddress() == clientAddr) clientNode = n;
                if (el.getElementAddress() == serverAddr) serverNode = n;
            }
        }

        if (clientNode == null) {
            Log.e(TAG, "setupPublicationPair: clientNode not found for 0x"
                    + String.format("%04X", clientAddr));
            return;
        }
        if (serverNode == null) {
            Log.e(TAG, "setupPublicationPair: serverNode not found for 0x"
                    + String.format("%04X", serverAddr));
            return;
        }

        int clientElemIndex = AutoPublicationHelper.getElementIndex(clientNode, clientAddr);
        int serverElemIndex = AutoPublicationHelper.getElementIndex(serverNode, serverAddr);

        if (clientElemIndex == -1 || serverElemIndex == -1) {
            Log.e(TAG, "setupPublicationPair: element index not found"
                    + " clientIdx=" + clientElemIndex
                    + " serverIdx=" + serverElemIndex);
            return;
        }

        Log.d(TAG, "  Client node=0x" + String.format("%04X", clientNode.getUnicastAddress())
                + " elemIdx=" + clientElemIndex);
        Log.d(TAG, "  Server node=0x" + String.format("%04X", serverNode.getUnicastAddress())
                + " elemIdx=" + serverElemIndex);

        AutoPublicationHelper.setupBidirectionalPublication(
                this,
                clientNode,
                serverNode,
                clientElemIndex,
                serverElemIndex,
                appKeyIndex
        );
    }

    /**
     * Checks whether a node with the given unicast address exists in the mesh network.
     */
    private boolean isNodeInNetwork(int unicastAddr) {
        List<ProvisionedMeshNode> nodes = getAllProvisionedNodes();
        if (nodes == null) return false;
        for (ProvisionedMeshNode n : nodes) {
            if (n.getUnicastAddress() == unicastAddr) return true;
            for (Element el : n.getElements().values()) {
                if (el.getElementAddress() == unicastAddr) return true;
            }
        }
        return false;
    }

    // =========================================================================
    // PROVISIONED DEVICE IDs
    // =========================================================================

    public LiveData<Set<String>> getProvisionedDeviceIds() {
        return provisionedDeviceIds;
    }

    public boolean isDeviceProvisioned(String svgDeviceId) {
        return ClientServerElementStore.isProvisioned(svgDeviceId);
    }

    public void forceSvgRefresh() {
        syncFromStore();
        Log.d(TAG, "🔄 forceSvgRefresh done");
    }

    public LiveData<MeshMessage> getMeshMessageLiveData() {
        return mNrfMeshRepository.getMeshMessageLiveData();
    }

    public void clearProvisionedDevices() {
        for (String key : ClientServerElementStore.getProvisionedKeys()) {
            ClientServerElementStore.clearDevice(key);
        }
        provisionedDeviceIds.setValue(new HashSet<>());
        Log.d(TAG, "🧹 clearProvisionedDevices done");
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
                if (n.getUuid().equals(adapterNode.getUuid())) {
                    realNode = n;
                    break;
                }
            }
        }
        if (realNode == null) {
            Log.e(TAG, "❌ fullyDeleteNode: node not found in network");
            return false;
        }

        // ── Method 1: UUID → svgId map ────────────────────────────────────────
        String svgId = getSvgIdFromNode(realNode);

        // ── Method 2: Fallback — unicast address se reverse lookup ────────────
        if (svgId == null) {
            svgId = ClientServerElementStore
                    .getKeyByUnicastAddress(realNode.getUnicastAddress());
            Log.d(TAG, "fullyDeleteNode: svgId via unicast fallback = " + svgId);
        }

        Log.d(TAG, "fullyDeleteNode: nodeName=" + realNode.getNodeName()
                + " unicast=0x" + String.format("%04X", realNode.getUnicastAddress())
                + " svgId=" + svgId);

        boolean deleted = getNetworkLiveData().getMeshNetwork().deleteNode(realNode);
        if (!deleted) {
            Log.e(TAG, "❌ fullyDeleteNode: mesh delete failed");
            return false;
        }

        // ── Clear Store ───────────────────────────────────────────────────────
        if (svgId != null) {
            ClientServerElementStore.clearDevice(svgId);
            Log.d(TAG, "✅ fullyDeleteNode: clearDevice(" + svgId + ")");
        } else {
            Log.w(TAG, "⚠️ fullyDeleteNode: svgId null — scanning provisioned set by unicast");
            Set<String> keys = ClientServerElementStore.getProvisionedKeys();
            for (String key : keys) {
                int addr = ClientServerElementStore.getServerUnicastAddress(key);
                if (addr == realNode.getUnicastAddress()) {
                    ClientServerElementStore.clearDevice(key);
                    Log.d(TAG, "✅ fullyDeleteNode: cleared by unicast scan → " + key);
                    break;
                }
            }
        }

        // ── Clean in-memory + persisted UUID map ──────────────────────────────
        nodeToSvgMap.remove(realNode.getUuid());
        prefs.edit().remove("node_svg_" + realNode.getUuid()).apply();

        forceSvgRefresh();
        Log.d(TAG, "✅ fullyDeleteNode complete: " + realNode.getNodeName());
        return true;
    }

    // =========================================================================
    // CLIENT PROVISIONING
    // =========================================================================

    public void onClientProvisioned(@NonNull ProvisionedMeshNode clientNode,
                                    @NonNull String svgDeviceId) {
        List<Element> sorted = new ArrayList<>(clientNode.getElements().values());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            sorted.sort((a, b) ->
                    Integer.compare(a.getElementAddress(), b.getElementAddress()));
        }
        Map<Integer, Integer> elementAddresses = new HashMap<>();
        for (int i = 0; i < sorted.size() && i < 40; i++) {
            elementAddresses.put(i, sorted.get(i).getElementAddress());
        }
        ClientServerElementStore.saveAllClientElementAddresses(svgDeviceId, elementAddresses);
        Log.d(TAG, "✅ onClientProvisioned: saved "
                + elementAddresses.size() + " elements for " + svgDeviceId);
    }

    // =========================================================================
    // ELEMENT ID  (read-only — write via ClientServerElementStore.saveDevice())
    // =========================================================================

    @Nullable
    public String getElementId(@NonNull String svgDeviceId) {
        int id = ClientServerElementStore.getServerSvgElementId(svgDeviceId);
        return id != -1 ? String.valueOf(id) : null;
    }

    // =========================================================================
    // NODE ↔ SVG MAPPING  (UUID → svgDeviceId, in-memory + node_svg_ prefs)
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
        if (svgId == null) {
            Log.w(TAG, "autoMapNodeToCurrentSvg: svgId is null");
            return;
        }
        mapNodeToSvg(node.getUuid(), svgId);
    }

    // =========================================================================
    // FOCUS AREA
    // =========================================================================

    public LiveData<String> getFocusAreaId()    { return focusAreaId; }
    public void setFocusAreaId(String areaId)  { focusAreaId.setValue(areaId); }

    // =========================================================================
    // NETWORK
    // =========================================================================

    public LiveData<String> getNetworkLoadState()   { return mNrfMeshRepository.getNetworkLoadState(); }
    public LiveData<String> getNetworkExportState() { return networkExportState; }
    public void setSelectedGroup(final int address) { mNrfMeshRepository.setSelectedGroup(address); }

    public void exportMeshNetwork(@NonNull final java.io.OutputStream stream) {
        NetworkExportUtils.exportMeshNetwork(getMeshManagerApi(), stream, this);
    }

    public void exportMeshNetwork(@NonNull final Context context) {
        NetworkExportUtils.exportMeshNetwork(
                getMeshManagerApi(),
                mNrfMeshRepository.getExportPath(context),
                getNetworkLiveData().getNetworkName() + ".json",
                this);
    }

    @Override
    public void onNetworkExported() {
        networkExportState.postValue(
                getNetworkLiveData().getMeshNetwork().getMeshName() + " exported successfully.");
    }

    @Override
    public void onNetworkExportFailed(@NonNull final String error) {
        networkExportState.postValue(error);
    }

    // =========================================================================
    // LAST PROVISIONED NODE
    // =========================================================================

    public void setLastProvisionedNode(ProvisionedMeshNode node) {
        this.lastProvisionedNode = node;
    }

    public ProvisionedMeshNode getLastProvisionedNode() {
        return lastProvisionedNode;
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

    public void clearSelectedDevice() {
        setSelectedDevice(DEFAULT_SELECTED_DEVICE);
    }

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

    public void clearSignalThreshold() {
        setSignalThreshold(DevicesAdapter.SIGNAL_DEFAULT);
    }

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
        } catch (Exception e) {
            return null;
        }
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

    public LiveData<List<ExtendedBluetoothDevice>> getFilteredDevices() {
        return filteredDevices;
    }

    public List<ExtendedBluetoothDevice> getFilteredDevicesValue() {
        List<ExtendedBluetoothDevice> v = filteredDevices.getValue();
        return v != null ? v : new ArrayList<>();
    }

    public void setFilteredDevices(List<ExtendedBluetoothDevice> devices) {
        filteredDevices.setValue(devices != null ? devices : new ArrayList<>());
    }

    public void clearFilteredDevices() {
        filteredDevices.setValue(new ArrayList<>());
    }

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

    public void clearAllUnprovisionedDevices() {
        allUnprovisionedDevices.setValue(new ArrayList<>());
    }

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
            boolean signalOk = !hasSignalFilter
                    || matchesSignalThreshold(device, threshold);
            if (deviceOk && signalOk) filtered.add(device);
        }
        return filtered;
    }

    private boolean matchesSignalThreshold(@NonNull ExtendedBluetoothDevice device,
                                           int threshold) {
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
    // SELECTED SVG DEVICE ID  (transient — not persisted)
    // =========================================================================

    public LiveData<String> getSelectedSvgDeviceIdLiveData() { return mSelectedSvgDeviceId; }

    @Nullable
    public String getSelectedSvgDeviceId() { return mSelectedSvgDeviceId.getValue(); }

    public void setSelectedSvgDeviceId(@Nullable String svgDeviceId) {
        mSelectedSvgDeviceId.setValue(svgDeviceId);
        Log.d(TAG, "setSelectedSvgDeviceId: " + svgDeviceId);
    }

    public void clearSelectedSvgDeviceId() {
        mSelectedSvgDeviceId.setValue(null);
    }

    // =========================================================================
    // SERVER SVG DEVICE ID  (persistent)
    // =========================================================================

    public void setServerSvgDeviceId(@Nullable String serverSvgDeviceId) {
        mServerSvgDeviceId.setValue(serverSvgDeviceId);
        if (serverSvgDeviceId != null) {
            ClientServerElementStore.saveServerAreaId(serverSvgDeviceId, serverSvgDeviceId);
        }
        Log.d(TAG, "setServerSvgDeviceId: " + serverSvgDeviceId);
    }

    public LiveData<String> getServerSvgDeviceIdLiveData() { return mServerSvgDeviceId; }

    @Nullable
    public String getServerSvgDeviceId() { return mServerSvgDeviceId.getValue(); }

    public void clearServerSvgDeviceId() {
        mServerSvgDeviceId.setValue(null);
    }

    // =========================================================================
    // UTILITY — Node finders
    // =========================================================================

    @Nullable
    public ProvisionedMeshNode findNodeBySvgDeviceId(String svgDeviceId) {
        if (svgDeviceId == null) return null;
        String targetKey = ClientServerElementStore.normalize(svgDeviceId);
        try {
            MeshNetwork network = getNetworkLiveData().getMeshNetwork();
            if (network == null) return null;
            for (ProvisionedMeshNode node : network.getNodes()) {
                String nodeName = node.getNodeName();
                if (nodeName == null) continue;
                if (ClientServerElementStore.normalize(nodeName).equals(targetKey)) {
                    return node;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "findNodeBySvgDeviceId error", e);
        }
        return null;
    }

    public boolean selectNodeBySvgDeviceId(@Nullable String svgDeviceId) {
        ProvisionedMeshNode node = findNodeBySvgDeviceId(svgDeviceId);
        if (node != null) {
            setSelectedMeshNode(node);
            return true;
        }
        return false;
    }

    public int getProvisionedNodeCount() {
        try {
            MeshNetwork network = getNetworkLiveData().getMeshNetwork();
            if (network == null) return 0;
            List<ProvisionedMeshNode> nodes = network.getNodes();
            return nodes != null ? nodes.size() : 0;
        } catch (Exception e) {
            return 0;
        }
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

    public LiveData<Boolean> isAutoSetupInProgress() {
        return mNrfMeshRepository.isAutoSetupInProgress();
    }

    // =========================================================================
    // Helper class — PublishConfig
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