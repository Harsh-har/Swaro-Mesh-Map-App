package no.nordicsemi.android.swaromapmesh.viewmodels;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;
import dagger.hilt.android.qualifiers.ApplicationContext;
import no.nordicsemi.android.swaromapmesh.ApplicationKey;
import no.nordicsemi.android.swaromapmesh.MeshNetwork;
import no.nordicsemi.android.swaromapmesh.NodeKey;
import no.nordicsemi.android.swaromapmesh.adapter.ExtendedBluetoothDevice;
import no.nordicsemi.android.swaromapmesh.ble.adapter.DevicesAdapter;
import no.nordicsemi.android.swaromapmesh.transport.ProvisionedMeshNode;
import no.nordicsemi.android.swaromapmesh.utils.NetworkExportUtils;

@HiltViewModel
public class SharedViewModel extends BaseViewModel implements NetworkExportUtils.NetworkExportCallbacks {

    private final ScannerRepository mScannerRepository;
    private final SingleLiveEvent<String> networkExportState = new SingleLiveEvent<>();

    private static final String PREFS_NAME              = "mesh_prefs";
    private static final String KEY_PROXY_ENABLED       = "proxy_enabled";
    private static final String KEY_SELECTED_DEVICE     = "selected_device";
    private static final String KEY_SIGNAL_THRESHOLD    = "signal_threshold";
    private static final String KEY_SVG_URI             = "svg_uri";
    private static final String KEY_PROVISIONED_DEVICES = "provisioned_devices";
    private static final String DEFAULT_SELECTED_DEVICE = "All Device";

    private final SharedPreferences prefs;

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

        proxyEnabled.setValue(prefs.getBoolean(KEY_PROXY_ENABLED, true));
        selectedDevice.setValue(prefs.getString(KEY_SELECTED_DEVICE, DEFAULT_SELECTED_DEVICE));
        signalThreshold.setValue(prefs.getInt(KEY_SIGNAL_THRESHOLD, DevicesAdapter.SIGNAL_DEFAULT));

        final String savedSvgUri = prefs.getString(KEY_SVG_URI, null);
        if (savedSvgUri != null) {
            svgUri.setValue(Uri.parse(savedSvgUri));
        }

        Set<String> savedProvisioned = prefs.getStringSet(KEY_PROVISIONED_DEVICES, new HashSet<>());
        provisionedDeviceIds.setValue(new HashSet<>(savedProvisioned));
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        if (!mNrfMeshRepository.getBleMeshManager().isConnected()) {
            mNrfMeshRepository.disconnect();
        }
        mScannerRepository.unregisterBroadcastReceivers();
    }

    // ==================== NETWORK ====================

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
        final String fileName = getNetworkLiveData().getNetworkName() + ".json";
        NetworkExportUtils.exportMeshNetwork(getMeshManagerApi(),
                NrfMeshRepository.EXPORT_PATH, fileName, this);
    }

    @Override
    public void onNetworkExported() {
        networkExportState.postValue(getNetworkLiveData().getMeshNetwork().getMeshName()
                + " has been successfully exported.");
    }

    @Override
    public void onNetworkExportFailed(@NonNull final String error) {
        networkExportState.postValue(error);
    }

    // ==================== SVG URI (PERSISTENT) ====================

    public LiveData<Uri> getSvgUri() { return svgUri; }

    public void setSvgUri(@NonNull Uri uri) {
        svgUri.setValue(uri);
        prefs.edit().putString(KEY_SVG_URI, uri.toString()).apply();
    }

    public Uri getSvgUriValue() { return svgUri.getValue(); }

    public boolean hasSvg() { return svgUri.getValue() != null; }

    public void clearSvgUri() {
        svgUri.setValue(null);
        prefs.edit().remove(KEY_SVG_URI).apply();
    }

    // ==================== PROXY BUTTON STATE (PERSISTENT) ====================

    public LiveData<Boolean> getProxyEnabled() { return proxyEnabled; }

    public void setProxyEnabled(boolean enabled) {
        proxyEnabled.setValue(enabled);
        prefs.edit().putBoolean(KEY_PROXY_ENABLED, enabled).apply();
    }

    public boolean isProxyEnabled() {
        Boolean v = proxyEnabled.getValue();
        return v != null && v;
    }

    // ==================== DEVICE NAME FILTER (stub) ====================

    public void setDeviceNameFilter(String filter) {
        prefs.edit().putString("device_name_filter", "").apply();
    }

    public String getDeviceNameFilterValue() { return ""; }

    // ==================== SELECTED DEVICE (PERSISTENT) ====================

    public LiveData<String> getSelectedDevice() { return selectedDevice; }

    public void setSelectedDevice(String device) {
        if (device == null) device = DEFAULT_SELECTED_DEVICE;
        selectedDevice.setValue(device);
        prefs.edit().putString(KEY_SELECTED_DEVICE, device).apply();
    }

    public String getSelectedDeviceValue() {
        String v = selectedDevice.getValue();
        return v != null ? v : DEFAULT_SELECTED_DEVICE;
    }

    public boolean isDeviceSelected(String deviceName) {
        return deviceName != null && deviceName.equals(getSelectedDeviceValue());
    }

    public void clearSelectedDevice() { setSelectedDevice(DEFAULT_SELECTED_DEVICE); }

    // ==================== SIGNAL STRENGTH THRESHOLD (PERSISTENT) ====================

    public LiveData<Integer> getSignalThreshold() { return signalThreshold; }

    public void setSignalThreshold(int threshold) {
        int sanitized = (threshold == DevicesAdapter.SIGNAL_100)
                ? DevicesAdapter.SIGNAL_100
                : DevicesAdapter.SIGNAL_DEFAULT;
        signalThreshold.setValue(sanitized);
        prefs.edit().putInt(KEY_SIGNAL_THRESHOLD, sanitized).apply();
    }

    public int getSignalThresholdValue() {
        Integer v = signalThreshold.getValue();
        return v != null ? v : DevicesAdapter.SIGNAL_DEFAULT;
    }

    public void clearSignalThreshold() { setSignalThreshold(DevicesAdapter.SIGNAL_DEFAULT); }

    // ==================== PROVISIONED DEVICE IDs (PERSISTENT) ====================

    public LiveData<Set<String>> getProvisionedDeviceIds() {
        return provisionedDeviceIds;
    }

    public boolean isDeviceProvisioned(String svgDeviceId) {
        if (svgDeviceId == null) return false;
        Set<String> set = provisionedDeviceIds.getValue();
        return set != null && set.contains(svgDeviceId);
    }

    public void markDeviceProvisioned(String svgDeviceId) {
        if (svgDeviceId == null) return;
        Set<String> current = new HashSet<>();
        if (provisionedDeviceIds.getValue() != null) {
            current.addAll(provisionedDeviceIds.getValue());
        }
        current.add(svgDeviceId);
        provisionedDeviceIds.setValue(current);
        prefs.edit().putStringSet(KEY_PROVISIONED_DEVICES, new HashSet<>(current)).apply();
    }

    public void unmarkDeviceProvisioned(String svgDeviceId) {
        if (svgDeviceId == null) return;
        Set<String> current = new HashSet<>();
        if (provisionedDeviceIds.getValue() != null) {
            current.addAll(provisionedDeviceIds.getValue());
        }
        current.remove(svgDeviceId);
        provisionedDeviceIds.setValue(current);
        prefs.edit().putStringSet(KEY_PROVISIONED_DEVICES, new HashSet<>(current)).apply();
    }

    public void clearProvisionedDevices() {
        provisionedDeviceIds.setValue(new HashSet<>());
        prefs.edit().remove(KEY_PROVISIONED_DEVICES).apply();
    }

    // ==================== AUTO APP KEY ====================

    /**
     * Returns the first AppKey from the network, or null if none exist.
     */
    @Nullable
    public ApplicationKey getDefaultAppKey() {
        try {
            final MeshNetwork network = getNetworkLiveData().getMeshNetwork();
            if (network == null) return null;
            final List<ApplicationKey> appKeys = network.getAppKeys();
            if (appKeys == null || appKeys.isEmpty()) return null;
            return appKeys.get(0);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Returns true if the default AppKey is already bound to this node.
     */
    public boolean isDefaultAppKeyBound(@NonNull final ProvisionedMeshNode node) {
        final ApplicationKey key = getDefaultAppKey();
        if (key == null) return false;
        for (NodeKey k : node.getAddedAppKeys()) {
            if (k.getIndex() == key.getKeyIndex()) return true;
        }
        return false;
    }

    /**
     * Returns true if AppKey auto-bind has already been done for this node.
     * Keyed by unicast address — survives app restarts.
     */
    public boolean isAutoAppKeyDone(int unicastAddress) {
        return prefs.getBoolean("app_key_done_" + unicastAddress, false);
    }

    /**
     * Marks AppKey auto-bind as done for this node so it never repeats.
     */
    public void setAutoAppKeyDone(int unicastAddress) {
        prefs.edit().putBoolean("app_key_done_" + unicastAddress, true).apply();
    }

    // ==================== FILTERED DEVICES ====================

    public LiveData<List<ExtendedBluetoothDevice>> getFilteredDevices() { return filteredDevices; }

    public void setFilteredDevices(List<ExtendedBluetoothDevice> devices) {
        if (devices == null) devices = new ArrayList<>();
        filteredDevices.setValue(devices);
    }

    public List<ExtendedBluetoothDevice> getFilteredDevicesValue() {
        List<ExtendedBluetoothDevice> v = filteredDevices.getValue();
        return v != null ? v : new ArrayList<>();
    }

    public void clearFilteredDevices() { filteredDevices.setValue(new ArrayList<>()); }

    // ==================== ALL UNPROVISIONED DEVICES ====================

    public LiveData<List<ExtendedBluetoothDevice>> getAllUnprovisionedDevices() {
        return allUnprovisionedDevices;
    }

    public void setAllUnprovisionedDevices(List<ExtendedBluetoothDevice> devices) {
        if (devices == null) devices = new ArrayList<>();
        allUnprovisionedDevices.setValue(devices);
    }

    public List<ExtendedBluetoothDevice> getAllUnprovisionedDevicesValue() {
        List<ExtendedBluetoothDevice> v = allUnprovisionedDevices.getValue();
        return v != null ? v : new ArrayList<>();
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

    // ==================== FILTER UTILITY ====================

    public boolean isFilterActive() {
        return !getSelectedDeviceValue().equals(DEFAULT_SELECTED_DEVICE)
                || getSignalThresholdValue() != DevicesAdapter.SIGNAL_DEFAULT;
    }

    public String getActiveFilterDescription() {
        StringBuilder sb = new StringBuilder();
        if (!getSelectedDeviceValue().equals(DEFAULT_SELECTED_DEVICE)) {
            sb.append("Device: ").append(getSelectedDeviceValue());
        }
        if (getSignalThresholdValue() == DevicesAdapter.SIGNAL_100) {
            if (sb.length() > 0) sb.append(" | ");
            sb.append("Signal ≥ 100%");
        }
        return sb.length() > 0 ? "Filter: " + sb : "No filter active";
    }

    public void resetAllFilters() {
        clearSelectedDevice();
        clearSignalThreshold();
        clearFilteredDevices();
    }

    public List<ExtendedBluetoothDevice> applyFilter(List<ExtendedBluetoothDevice> devices) {
        if (devices == null) return new ArrayList<>();

        String  nameFilter      = getSelectedDeviceValue();
        int     threshold       = getSignalThresholdValue();
        boolean hasDeviceFilter = !nameFilter.equals(DEFAULT_SELECTED_DEVICE);
        boolean hasSignalFilter = threshold != DevicesAdapter.SIGNAL_DEFAULT;

        if (!hasDeviceFilter && !hasSignalFilter) return new ArrayList<>(devices);

        List<ExtendedBluetoothDevice> filtered    = new ArrayList<>();
        String                        lowerFilter = nameFilter.toLowerCase();

        for (ExtendedBluetoothDevice device : devices) {
            boolean deviceOk = !hasDeviceFilter || (device.getName() != null
                    && device.getName().toLowerCase().contains(lowerFilter));
            boolean signalOk = !hasSignalFilter || matchesSignalThreshold(device, threshold);
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

    // ==================== SCANNER REPOSITORY ACCESS ====================

    public ScannerRepository getScannerRepository() { return mScannerRepository; }

    public LiveData<ScannerLiveData> getScannerResults() {
        return mScannerRepository.getScannerResults();
    }
}