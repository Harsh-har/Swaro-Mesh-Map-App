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
    private static final String PREFS_NAME                        = "mesh_prefs";
    private static final String KEY_PROXY_ENABLED                 = "proxy_enabled";
    private static final String KEY_SELECTED_DEVICE               = "selected_device";
    private static final String KEY_SIGNAL_THRESHOLD              = "signal_threshold";
    private static final String KEY_SVG_URI                       = "svg_uri";
    private static final String KEY_PROVISIONED_DEVICES           = "provisioned_devices";
    private static final String KEY_SERVER_SVG_DEVICE_ID          = "server_svg_device_id";
    private static final String KEY_ELEMENT_ADDRESS_MAPPING_PREFIX = "element_addr_";
    private static final String KEY_ELEMENT_ID_MAPPING_PREFIX     = "element_id_";
    private static final String KEY_CLIENT_ADDRESS_MAPPING_PREFIX  = "client_addr_";
    private static final String DEFAULT_SELECTED_DEVICE           = "All Device";

    private final SharedPreferences prefs;

    // ── LiveData fields ───────────────────────────────────────────────────────
    private final MutableLiveData<Boolean> proxyEnabled      = new MutableLiveData<>();
    private final MutableLiveData<String>  selectedDevice    = new MutableLiveData<>(DEFAULT_SELECTED_DEVICE);
    private final MutableLiveData<Integer> signalThreshold   = new MutableLiveData<>(DevicesAdapter.SIGNAL_DEFAULT);
    private final MutableLiveData<Uri>     svgUri            = new MutableLiveData<>();
    private final MutableLiveData<String>  selectedDeviceId  = new MutableLiveData<>();
    private final MutableLiveData<Set<String>> provisionedDeviceIds = new MutableLiveData<>(new HashSet<>());

    private final MutableLiveData<List<ExtendedBluetoothDevice>> filteredDevices =
            new MutableLiveData<>(new ArrayList<>());
    private final MutableLiveData<List<ExtendedBluetoothDevice>> allUnprovisionedDevices =
            new MutableLiveData<>(new ArrayList<>());

    // ── SVG Device ID (transient — passed between activities) ─────────────────
    private final MutableLiveData<String> mSelectedSvgDeviceId = new MutableLiveData<>();

    // ── Server SVG Device ID with LiveData support ────────────────────────────
    private final MutableLiveData<String> mServerSvgDeviceId = new MutableLiveData<>();

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

        // Also initialise ClientElementStore with the same context so
        // NrfMeshRepository can write to it without needing a Context reference.
        ClientElementStore.init(context);

        proxyEnabled.setValue(prefs.getBoolean(KEY_PROXY_ENABLED, true));
        selectedDevice.setValue(
                prefs.getString(KEY_SELECTED_DEVICE, DEFAULT_SELECTED_DEVICE));
        signalThreshold.setValue(
                prefs.getInt(KEY_SIGNAL_THRESHOLD, DevicesAdapter.SIGNAL_DEFAULT));

        final String savedSvgUri = prefs.getString(KEY_SVG_URI, null);
        if (savedSvgUri != null) {
            svgUri.setValue(Uri.parse(savedSvgUri));
        }

        Set<String> savedProvisioned =
                prefs.getStringSet(KEY_PROVISIONED_DEVICES, new HashSet<>());
        provisionedDeviceIds.setValue(new HashSet<>(savedProvisioned));

        String savedServerId = prefs.getString(KEY_SERVER_SVG_DEVICE_ID, null);
        if (savedServerId != null) {
            mServerSvgDeviceId.setValue(savedServerId);
        }
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
    // ELEMENT ADDRESS MAPPING (Client Side)
    // =========================================================================

    /**
     * Save element address for a specific element index in a client device.
     * Called when provisioning a Generic On Off Client.
     *
     * Key: "element_addr_<svgDeviceId>_<elementIndex>"
     *
     * @param svgDeviceId    The client device's SVG ID (= node name)
     * @param elementIndex   1-based element index (1..40)
     * @param elementAddress Unicast address of that element
     */
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

    /**
     * Get client element address by SVG device ID and element index.
     *
     * Reads from SharedPreferences written by EITHER:
     *   - NodeConfigurationActivity / onClientProvisioned()  (UI path)
     *   - NrfMeshRepository.onAllModelsBindComplete()        (auto-bind path, via ClientElementStore)
     *
     * Both paths write the same key format so this single read covers both.
     *
     * @param svgDeviceId  The client device's SVG ID (= node name)
     * @param elementIndex 1-based element index
     * @return Element unicast address, or -1 if not found
     */
    public int getClientElementAddress(@NonNull String svgDeviceId, int elementIndex) {
        String key = KEY_ELEMENT_ADDRESS_MAPPING_PREFIX + svgDeviceId + "_" + elementIndex;
        int address = prefs.getInt(key, -1);

        // ClientElementStore uses the SAME prefs file and SAME key prefix,
        // so a second lookup is not needed — the read above already covers it.
        // Keeping the ClientElementStore.get() call as an explicit fallback
        // for robustness in case prefs are written on a different instance.
        if (address == -1) {
            address = ClientElementStore.get(svgDeviceId, elementIndex);
        }

        if (address != -1) {
            Log.d(TAG, "getClientElementAddress: device=" + svgDeviceId
                    + " index=" + elementIndex
                    + " address=0x" + String.format("%04X", address));
        } else {
            Log.w(TAG, "getClientElementAddress: NOT FOUND device=" + svgDeviceId
                    + " index=" + elementIndex
                    + " — was the Client provisioned and auto-bind completed?");
        }

        return address;
    }

    /**
     * Save all element addresses for a client device at once.
     * Called after provisioning a client to store all element mappings.
     *
     * @param svgDeviceId    The client device's SVG ID
     * @param elementAddresses Map of element index (1-based) → element address
     */
    public void saveAllClientElementAddresses(
            @NonNull String svgDeviceId,
            @NonNull Map<Integer, Integer> elementAddresses) {

        for (Map.Entry<Integer, Integer> entry : elementAddresses.entrySet()) {
            saveClientElementAddress(svgDeviceId, entry.getKey(), entry.getValue());
        }

        Log.d(TAG, "✅ saveAllClientElementAddresses: saved "
                + elementAddresses.size() + " addresses for client: " + svgDeviceId);
    }

    /**
     * Get ALL stored client element addresses for a device (indices 1–40).
     *
     * @param svgDeviceId The client device's SVG ID
     * @return Map of index → address for every index that has data
     */
    public Map<Integer, Integer> getAllClientElementAddresses(@NonNull String svgDeviceId) {
        Map<Integer, Integer> result = new HashMap<>();
        for (int i = 1; i <= 40; i++) {
            int address = getClientElementAddress(svgDeviceId, i);
            if (address != -1) {
                result.put(i, address);
            }
        }
        return result;
    }

    // =========================================================================
    // CLIENT PUBLISH ADDRESS MAPPING (Server Side)
    // =========================================================================

    /**
     * Save which client element a server element should publish to.
     *
     * @param serverSvgDeviceId  Server device's SVG ID
     * @param serverElementIndex Server's element index (from SVG parsing)
     * @param clientSvgDeviceId  Client device's SVG ID
     * @param clientElementIndex Client's element index to publish to
     */
    public void saveServerPublishMapping(
            @NonNull String serverSvgDeviceId,
            int serverElementIndex,
            @NonNull String clientSvgDeviceId,
            int clientElementIndex) {

        String key = KEY_CLIENT_ADDRESS_MAPPING_PREFIX
                + serverSvgDeviceId + "_" + serverElementIndex;
        String mappingValue = clientSvgDeviceId + ":" + clientElementIndex;
        prefs.edit().putString(key, mappingValue).apply();

        Log.d(TAG, "✅ saveServerPublishMapping:"
                + " server=" + serverSvgDeviceId + "[" + serverElementIndex + "]"
                + " → client=" + clientSvgDeviceId + "[" + clientElementIndex + "]");
    }

    /**
     * Get the publish address for a server element by resolving its stored client mapping.
     *
     * @param serverSvgDeviceId  Server device's SVG ID
     * @param serverElementIndex Server's element index
     * @return Publish address, or -1 if no mapping exists
     */
    public int getServerPublishAddress(
            @NonNull String serverSvgDeviceId,
            int serverElementIndex) {

        String key = KEY_CLIENT_ADDRESS_MAPPING_PREFIX
                + serverSvgDeviceId + "_" + serverElementIndex;
        String mappingValue = prefs.getString(key, null);

        if (mappingValue == null) {
            Log.w(TAG, "getServerPublishAddress: no mapping for server="
                    + serverSvgDeviceId + " element=" + serverElementIndex);
            return -1;
        }

        String[] parts = mappingValue.split(":");
        if (parts.length != 2) {
            Log.e(TAG, "getServerPublishAddress: invalid mapping format: " + mappingValue);
            return -1;
        }

        String clientSvgDeviceId = parts[0];
        int clientElementIndex;
        try {
            clientElementIndex = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            Log.e(TAG, "getServerPublishAddress: invalid client element index: " + parts[1]);
            return -1;
        }

        int clientAddress = getClientElementAddress(clientSvgDeviceId, clientElementIndex);
        if (clientAddress != -1) {
            Log.d(TAG, "getServerPublishAddress: resolved"
                    + " server=" + serverSvgDeviceId + "[" + serverElementIndex + "]"
                    + " → client=" + clientSvgDeviceId + "[" + clientElementIndex + "]"
                    + " address=0x" + String.format("%04X", clientAddress));
        }
        return clientAddress;
    }

    /**
     * Get the complete publish configuration for a server element.
     *
     * @param serverSvgDeviceId  Server device's SVG ID
     * @param serverElementIndex Server's element index
     * @return PublishConfig, or null if no mapping found
     */
    @Nullable
    public PublishConfig getServerPublishConfig(
            @NonNull String serverSvgDeviceId,
            int serverElementIndex) {

        int address = getServerPublishAddress(serverSvgDeviceId, serverElementIndex);
        if (address == -1) return null;

        String key = KEY_CLIENT_ADDRESS_MAPPING_PREFIX
                + serverSvgDeviceId + "_" + serverElementIndex;
        String mappingValue = prefs.getString(key, null);
        if (mappingValue == null) return null;

        String[] parts = mappingValue.split(":");
        if (parts.length != 2) return null;

        return new PublishConfig(address, parts[0], Integer.parseInt(parts[1]));
    }

    // =========================================================================
    // AUTO MAPPING LOGIC
    // =========================================================================

    /**
     * Core auto-mapping method.
     *
     * Given a server SVG device ID and its element index N, this method:
     *  1. Checks for an existing saved mapping (fast path).
     *  2. Scans all provisioned client nodes looking for one that has a stored
     *     element address at index N (written by onAllModelsBindComplete after
     *     Client was provisioned).
     *  3. Saves the discovered mapping so future calls use the fast path.
     *
     * Example: Server SVG element ID = 2
     *   → looks for client_addr_<clientNodeName>_2
     *   → returns the unicast address stored there
     *
     * @param serverSvgDeviceId  The server device's SVG ID (= node name)
     * @param serverElementIndex The server's element index from SVG parsing (1-based)
     * @return The client unicast address to use as publish address, or -1 if not found
     */
    public int autoMapServerToClientPublishAddress(
            @NonNull String serverSvgDeviceId,
            int serverElementIndex) {

        Log.d(TAG, "autoMapServerToClientPublishAddress:"
                + " server=" + serverSvgDeviceId
                + " elementIndex=" + serverElementIndex);

        // ── Fast path: existing saved mapping ────────────────────────────────
        int existingMapping = getServerPublishAddress(serverSvgDeviceId, serverElementIndex);
        if (existingMapping != -1) {
            Log.d(TAG, "  → fast path: existing mapping 0x"
                    + String.format("%04X", existingMapping));
            return existingMapping;
        }

        // ── Scan all provisioned nodes for a matching client ──────────────────
        List<ProvisionedMeshNode> allNodes = getAllProvisionedNodes();
        if (allNodes == null || allNodes.isEmpty()) {
            Log.w(TAG, "  → no provisioned nodes found");
            return -1;
        }

        for (ProvisionedMeshNode node : allNodes) {
            // Skip the server node itself
            if (serverSvgDeviceId.equalsIgnoreCase(node.getNodeName())) continue;

            // Check this node has Generic On Off Client model (0x1001)
            boolean hasClientModel = false;
            for (Element element : node.getElements().values()) {
                for (MeshModel model : element.getMeshModels().values()) {
                    if (model.getModelId() == 0x1001) {
                        hasClientModel = true;
                        break;
                    }
                }
                if (hasClientModel) break;
            }
            if (!hasClientModel) continue;

            // Check if client has a stored address for the matching element index
            int clientAddress = getClientElementAddress(node.getNodeName(), serverElementIndex);
            if (clientAddress != -1) {
                Log.d(TAG, "✅ autoMapServerToClientPublishAddress:"
                        + " matched client=" + node.getNodeName()
                        + " element=" + serverElementIndex
                        + " address=0x" + String.format("%04X", clientAddress));

                // Save for future fast-path use
                saveServerPublishMapping(serverSvgDeviceId, serverElementIndex,
                        node.getNodeName(), serverElementIndex);

                return clientAddress;
            }
        }

        Log.w(TAG, "autoMapServerToClientPublishAddress: no matching client element found"
                + " for server=" + serverSvgDeviceId
                + " elementIndex=" + serverElementIndex
                + " — ensure Client was provisioned first and auto-bind completed.");
        return -1;
    }

    // =========================================================================
    // ELEMENT ID MANAGEMENT (from SVG parsing)
    // =========================================================================

    /**
     * Save the SVG element index for a device.
     *
     * This MUST be called during the provisioning flow for SERVER nodes,
     * before PublicationSettingsActivity opens.
     *
     * Example: SVG icon "light_02" has element ID 2
     *   → saveElementId("light_02", "2")
     *
     * @param svgDeviceId The device's SVG ID (= node name)
     * @param elementId   The element index as a string (e.g. "2")
     */
    public void saveElementId(@NonNull String svgDeviceId, @NonNull String elementId) {
        String key = KEY_ELEMENT_ID_MAPPING_PREFIX + svgDeviceId;
        prefs.edit().putString(key, elementId).apply();
        Log.d(TAG, "✅ saveElementId: device=" + svgDeviceId + " elementId=" + elementId);
    }

    /**
     * Get the SVG element ID string for a device.
     *
     * @param svgDeviceId The device's SVG ID
     * @return Element ID string, or null if not set
     */
    @Nullable
    public String getElementId(@NonNull String svgDeviceId) {
        String key = KEY_ELEMENT_ID_MAPPING_PREFIX + svgDeviceId;
        return prefs.getString(key, null);
    }

    /**
     * Get the SVG element ID as an integer.
     *
     * @param svgDeviceId The device's SVG ID
     * @return Element index (1-based), or -1 if not found / not parseable
     */
    public int getElementIdAsInt(@NonNull String svgDeviceId) {
        String elementIdStr = getElementId(svgDeviceId);
        if (elementIdStr == null) return -1;
        try {
            return Integer.parseInt(elementIdStr.trim());
        } catch (NumberFormatException e) {
            Log.e(TAG, "getElementIdAsInt: invalid format: " + elementIdStr);
            return -1;
        }
    }

    // =========================================================================
    // COMPREHENSIVE CLIENT PROVISIONING HELPERS
    // =========================================================================

    /**
     * Call this after a Generic On Off Client has been fully provisioned
     * (all AppKey binds done) to store all element addresses.
     *
     * NOTE: NrfMeshRepository.onAllModelsBindComplete() also does this
     * automatically. Call this method from the UI layer if you want an
     * explicit hook (e.g. from NodeConfigurationActivity).
     *
     * @param clientNode  The fully provisioned client node
     * @param svgDeviceId The client's SVG device ID (= node name)
     */
    public void onClientProvisioned(
            @NonNull ProvisionedMeshNode clientNode,
            @NonNull String svgDeviceId) {

        Log.d(TAG, "onClientProvisioned: " + svgDeviceId);

        List<Element> sortedElements = new ArrayList<>(clientNode.getElements().values());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            sortedElements.sort((a, b) ->
                    Integer.compare(a.getElementAddress(), b.getElementAddress()));
        }

        Map<Integer, Integer> elementAddresses = new HashMap<>();
        for (int i = 0; i < sortedElements.size() && i < 40; i++) {
            elementAddresses.put(i + 1, sortedElements.get(i).getElementAddress());
        }

        saveAllClientElementAddresses(svgDeviceId, elementAddresses);

        Log.d(TAG, "✅ onClientProvisioned: saved "
                + elementAddresses.size() + " elements for " + svgDeviceId);
    }

    /**
     * Call this after a Generic On Off Server has been provisioned to
     * auto-assign publish addresses based on element index matching.
     *
     * @param serverNode  The provisioned server node
     * @param svgDeviceId The server's SVG device ID (= node name)
     * @return Map of element index → assigned publish address
     */
    public Map<Integer, Integer> onServerProvisioned(
            @NonNull ProvisionedMeshNode serverNode,
            @NonNull String svgDeviceId) {

        Log.d(TAG, "onServerProvisioned: " + svgDeviceId);

        Map<Integer, Integer> assignedAddresses = new HashMap<>();

        int serverElementId = getElementIdAsInt(svgDeviceId);
        if (serverElementId == -1) {
            Log.w(TAG, "onServerProvisioned: no element ID stored for server: " + svgDeviceId
                    + " — call saveElementId() before provisioning this server");
            return assignedAddresses;
        }

        int publishAddress = autoMapServerToClientPublishAddress(svgDeviceId, serverElementId);
        if (publishAddress != -1) {
            assignedAddresses.put(serverElementId, publishAddress);
            Log.d(TAG, "✅ onServerProvisioned: server=" + svgDeviceId
                    + " element=" + serverElementId
                    + " → publish=0x" + String.format("%04X", publishAddress));
        } else {
            Log.w(TAG, "onServerProvisioned: no publish address resolved for server="
                    + svgDeviceId + " element=" + serverElementId);
        }

        return assignedAddresses;
    }

    // =========================================================================
    // NETWORK
    // =========================================================================

    public LiveData<String> getNetworkLoadState() {
        return mNrfMeshRepository.getNetworkLoadState();
    }

    public LiveData<String> getNetworkExportState() {
        return networkExportState;
    }

    public void setSelectedGroup(final int address) {
        mNrfMeshRepository.setSelectedGroup(address);
    }

    public void exportMeshNetwork(@NonNull final OutputStream stream) {
        NetworkExportUtils.exportMeshNetwork(getMeshManagerApi(), stream, this);
    }

    public void exportMeshNetwork() {
        final String fileName =
                getNetworkLiveData().getNetworkName() + ".json";
        NetworkExportUtils.exportMeshNetwork(getMeshManagerApi(),
                NrfMeshRepository.EXPORT_PATH, fileName, this);
    }

    @Override
    public void onNetworkExported() {
        networkExportState.postValue(
                getNetworkLiveData().getMeshNetwork().getMeshName()
                        + " has been successfully exported.");
    }

    @Override
    public void onNetworkExportFailed(@NonNull final String error) {
        networkExportState.postValue(error);
    }

    // =========================================================================
    // SVG URI (PERSISTENT)
    // =========================================================================

    public LiveData<Uri> getSvgUri()      { return svgUri; }
    public Uri getSvgUriValue()           { return svgUri.getValue(); }
    public boolean hasSvg()              { return svgUri.getValue() != null; }

    public void setSvgUri(@NonNull Uri uri) {
        svgUri.setValue(uri);
        prefs.edit().putString(KEY_SVG_URI, uri.toString()).apply();
    }

    public void clearSvgUri() {
        svgUri.setValue(null);
        prefs.edit().remove(KEY_SVG_URI).apply();
    }

    // =========================================================================
    // PROXY BUTTON STATE (PERSISTENT)
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
    // SELECTED DEVICE (PERSISTENT)
    // =========================================================================

    public LiveData<String> getSelectedDevice()       { return selectedDevice; }
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
    // SIGNAL STRENGTH THRESHOLD (PERSISTENT)
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
    }

    public void unmarkDeviceProvisioned(String svgDeviceId) {
        if (svgDeviceId == null) return;
        Set<String> current = new HashSet<>();
        if (provisionedDeviceIds.getValue() != null)
            current.addAll(provisionedDeviceIds.getValue());
        current.remove(svgDeviceId);
        provisionedDeviceIds.setValue(current);
        prefs.edit().putStringSet(KEY_PROVISIONED_DEVICES, new HashSet<>(current)).apply();
    }

    public void clearProvisionedDevices() {
        provisionedDeviceIds.setValue(new HashSet<>());
        prefs.edit().remove(KEY_PROVISIONED_DEVICES).apply();
    }

    // =========================================================================
    // AUTO APP KEY
    // =========================================================================

    @Nullable
    public ApplicationKey getDefaultAppKey() {
        try {
            final MeshNetwork network = getNetworkLiveData().getMeshNetwork();
            if (network == null) return null;
            final List<ApplicationKey> appKeys = network.getAppKeys();
            if (appKeys == null || appKeys.isEmpty()) return null;
            return appKeys.get(0);
        } catch (Exception e) { return null; }
    }

    public boolean isDefaultAppKeyBound(@NonNull final ProvisionedMeshNode node) {
        final ApplicationKey key = getDefaultAppKey();
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
        if (devices == null) devices = new ArrayList<>();
        filteredDevices.setValue(devices);
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
        if (devices == null) devices = new ArrayList<>();
        allUnprovisionedDevices.setValue(devices);
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
        String nameFilter  = getSelectedDeviceValue();
        int threshold      = getSignalThresholdValue();
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

    private boolean matchesSignalThreshold(@NonNull ExtendedBluetoothDevice device, int threshold) {
        int rssiPercent = (int) (100.0f * (127.0f + device.getRssi()) / (127.0f + 20.0f));
        return rssiPercent >= threshold;
    }

    public void applyCurrentFilter() {
        setFilteredDevices(applyFilter(getAllUnprovisionedDevicesValue()));
    }

    // =========================================================================
    // SCANNER REPOSITORY ACCESS
    // =========================================================================

    public ScannerRepository getScannerRepository() { return mScannerRepository; }
    public LiveData<ScannerLiveData> getScannerResults() {
        return mScannerRepository.getScannerResults();
    }

    // =========================================================================
    // SELECTED SVG DEVICE ID (transient — passed between activities)
    // =========================================================================

    public LiveData<String> getSelectedSvgDeviceIdLiveData() { return mSelectedSvgDeviceId; }

    @Nullable
    public String getSelectedSvgDeviceId() { return mSelectedSvgDeviceId.getValue(); }

    public void setSelectedSvgDeviceId(@Nullable String svgDeviceId) {
        mSelectedSvgDeviceId.setValue(svgDeviceId);
        Log.d(TAG, svgDeviceId != null
                ? "setSelectedSvgDeviceId: " + svgDeviceId
                : "setSelectedSvgDeviceId: cleared");
    }

    public void clearSelectedSvgDeviceId() {
        mSelectedSvgDeviceId.setValue(null);
    }

    // =========================================================================
    // SERVER SVG DEVICE ID (persistent)
    // =========================================================================

    public void setServerSvgDeviceId(@Nullable String serverSvgDeviceId) {
        mServerSvgDeviceId.setValue(serverSvgDeviceId);
        if (serverSvgDeviceId != null) {
            prefs.edit().putString(KEY_SERVER_SVG_DEVICE_ID, serverSvgDeviceId).apply();
        } else {
            prefs.edit().remove(KEY_SERVER_SVG_DEVICE_ID).apply();
        }
        Log.d(TAG, "setServerSvgDeviceId: " + serverSvgDeviceId);
    }

    public LiveData<String> getServerSvgDeviceIdLiveData() { return mServerSvgDeviceId; }

    @Nullable
    public String getServerSvgDeviceId() { return mServerSvgDeviceId.getValue(); }

    public void clearServerSvgDeviceId() {
        mServerSvgDeviceId.setValue(null);
        prefs.edit().remove(KEY_SERVER_SVG_DEVICE_ID).apply();
    }

    // =========================================================================
    // LEGACY — resolveServerPublishAddress
    // =========================================================================

    /**
     * Legacy method kept for backward compatibility.
     * New code should use autoMapServerToClientPublishAddress() instead.
     *
     * Server node ke elementId ke basis par Client node ka
     * matching element unicast address return karta hai.
     */
    public int resolveServerPublishAddress(
            @NonNull String serverSvgDeviceId,
            @NonNull ProvisionedMeshNode clientNode) {

        // Try new system first
        int serverElementId = getElementIdAsInt(serverSvgDeviceId);
        if (serverElementId != -1) {
            int address = autoMapServerToClientPublishAddress(serverSvgDeviceId, serverElementId);
            if (address != -1) return address;
        }

        // Legacy fallback
        Log.d(TAG, "resolveServerPublishAddress: falling back to legacy method");

        final String elementIdStr = prefs.getString("element_id_" + serverSvgDeviceId, null);
        if (elementIdStr == null || elementIdStr.isEmpty()) {
            Log.w(TAG, "resolveServerPublishAddress: no elementId for " + serverSvgDeviceId);
            return -1;
        }

        int targetIndex;
        try {
            targetIndex = Integer.parseInt(elementIdStr.trim());
        } catch (NumberFormatException e) {
            Log.e(TAG, "resolveServerPublishAddress: elementId not a number: " + elementIdStr);
            return -1;
        }

        List<Element> sorted = new ArrayList<>(clientNode.getElements().values());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            sorted.sort((a, b) -> Integer.compare(a.getElementAddress(), b.getElementAddress()));
        }

        if (targetIndex < 0 || targetIndex >= sorted.size()) {
            Log.e(TAG, "resolveServerPublishAddress: targetIndex=" + targetIndex
                    + " out of range (size=" + sorted.size() + ")");
            return -1;
        }

        int address = sorted.get(targetIndex).getElementAddress();
        Log.d(TAG, "resolveServerPublishAddress ✅ (legacy)"
                + " elementIndex=" + targetIndex
                + " address=0x" + String.format("%04X", address));
        return address;
    }

    // =========================================================================
    // UTILITY — find node by SVG device ID
    // =========================================================================

    @Nullable
    public ProvisionedMeshNode findNodeBySvgDeviceId(@Nullable String svgDeviceId) {
        if (svgDeviceId == null) return null;
        try {
            final MeshNetwork network = getNetworkLiveData().getMeshNetwork();
            if (network == null) return null;
            final List<ProvisionedMeshNode> nodes = network.getNodes();
            if (nodes == null || nodes.isEmpty()) return null;
            for (ProvisionedMeshNode node : nodes) {
                if (svgDeviceId.equalsIgnoreCase(node.getNodeName())) return node;
            }
            if (nodes.size() == 1) return nodes.get(0);
        } catch (Exception e) {
            Log.e(TAG, "findNodeBySvgDeviceId error: " + svgDeviceId, e);
        }
        return null;
    }

    public boolean selectNodeBySvgDeviceId(@Nullable String svgDeviceId) {
        ProvisionedMeshNode node = findNodeBySvgDeviceId(svgDeviceId);
        if (node != null) { setSelectedMeshNode(node); return true; }
        return false;
    }

    public int getProvisionedNodeCount() {
        try {
            final MeshNetwork network = getNetworkLiveData().getMeshNetwork();
            if (network == null) return 0;
            List<ProvisionedMeshNode> nodes = network.getNodes();
            return nodes != null ? nodes.size() : 0;
        } catch (Exception e) { return 0; }
    }

    @Nullable
    public List<ProvisionedMeshNode> getAllProvisionedNodes() {
        try {
            final MeshNetwork network = getNetworkLiveData().getMeshNetwork();
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