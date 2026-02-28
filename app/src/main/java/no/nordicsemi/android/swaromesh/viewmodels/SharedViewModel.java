package no.nordicsemi.android.swaromesh.viewmodels;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;
import dagger.hilt.android.qualifiers.ApplicationContext;
import no.nordicsemi.android.swaromesh.adapter.ExtendedBluetoothDevice;
import no.nordicsemi.android.swaromesh.ble.adapter.DevicesAdapter;
import no.nordicsemi.android.swaromesh.utils.NetworkExportUtils;


@HiltViewModel
public class SharedViewModel extends BaseViewModel implements NetworkExportUtils.NetworkExportCallbacks {

    private final ScannerRepository mScannerRepository;
    private final SingleLiveEvent<String> networkExportState = new SingleLiveEvent<>();

    private static final String PREFS_NAME              = "mesh_prefs";
    private static final String KEY_PROXY_ENABLED       = "proxy_enabled";
    private static final String KEY_DEVICE_NAME_FILTER  = "device_name_filter";
    private static final String KEY_SELECTED_DEVICE     = "selected_device";
    private static final String KEY_SIGNAL_THRESHOLD    = "signal_threshold";       // ← NEW
    private static final String DEFAULT_SELECTED_DEVICE = "Select no.nordicsemi.android.swaromesh.Device";

    private final SharedPreferences prefs;

    private final MutableLiveData<Boolean>  proxyEnabled    = new MutableLiveData<>();
    private final MutableLiveData<String>   deviceNameFilter = new MutableLiveData<>("");
    private final MutableLiveData<String>   selectedDevice  = new MutableLiveData<>(DEFAULT_SELECTED_DEVICE);
    private final MutableLiveData<Integer>  signalThreshold = new MutableLiveData<>(DevicesAdapter.SIGNAL_DEFAULT); // ← NEW
    private final MutableLiveData<List<ExtendedBluetoothDevice>> filteredDevices         = new MutableLiveData<>(new ArrayList<>());
    private final MutableLiveData<List<ExtendedBluetoothDevice>> allUnprovisionedDevices = new MutableLiveData<>(new ArrayList<>());

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

        // Restore persisted values
        proxyEnabled.setValue(prefs.getBoolean(KEY_PROXY_ENABLED, true));
        deviceNameFilter.setValue(prefs.getString(KEY_DEVICE_NAME_FILTER, ""));
        selectedDevice.setValue(prefs.getString(KEY_SELECTED_DEVICE, DEFAULT_SELECTED_DEVICE));
        signalThreshold.setValue(prefs.getInt(KEY_SIGNAL_THRESHOLD, DevicesAdapter.SIGNAL_DEFAULT)); // ← NEW
    }

    @Override
    protected void onCleared() {
        super.onCleared();

        // ✅ ROOT FIX: Do NOT disconnect if proxy is currently connected.
        if (!mNrfMeshRepository.getBleMeshManager().isConnected()) {
            mNrfMeshRepository.disconnect();
        }

        mScannerRepository.unregisterBroadcastReceivers();
    }

    // ---------------- NETWORK ----------------

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
        NetworkExportUtils.exportMeshNetwork(getMeshManagerApi(), NrfMeshRepository.EXPORT_PATH, fileName, this);
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

    // ---------------- PROXY BUTTON STATE (PERSISTENT) ----------------

    public LiveData<Boolean> getProxyEnabled() { return proxyEnabled; }

    public void setProxyEnabled(boolean enabled) {
        proxyEnabled.setValue(enabled);
        prefs.edit().putBoolean(KEY_PROXY_ENABLED, enabled).apply();
    }

    public boolean isProxyEnabled() {
        Boolean v = proxyEnabled.getValue();
        return v != null && v;
    }

    // ---------------- DEVICE NAME FILTER (PERSISTENT) ----------------

    public LiveData<String> getDeviceNameFilter() { return deviceNameFilter; }

    public void setDeviceNameFilter(String filter) {
        if (filter == null) filter = "";
        deviceNameFilter.setValue(filter);
        prefs.edit().putString(KEY_DEVICE_NAME_FILTER, filter).apply();
    }

    public String getDeviceNameFilterValue() {
        String v = deviceNameFilter.getValue();
        return v != null ? v : "";
    }

    public void clearDeviceNameFilter() { setDeviceNameFilter(""); }

    // ---------------- SELECTED DEVICE (PERSISTENT) ----------------

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

    // ---------------- SIGNAL STRENGTH THRESHOLD (PERSISTENT) ← NEW ----------------

    public LiveData<Integer> getSignalThreshold() { return signalThreshold; }

    /**
     * Set minimum RSSI signal threshold.
     * Use DevicesAdapter.SIGNAL_DEFAULT (0) to disable.
     * Use DevicesAdapter.SIGNAL_20 / SIGNAL_60 / SIGNAL_100 for thresholds.
     */
    public void setSignalThreshold(int threshold) {
        signalThreshold.setValue(threshold);
        prefs.edit().putInt(KEY_SIGNAL_THRESHOLD, threshold).apply();
    }

    public int getSignalThresholdValue() {
        Integer v = signalThreshold.getValue();
        return v != null ? v : DevicesAdapter.SIGNAL_DEFAULT;
    }

    public void clearSignalThreshold() { setSignalThreshold(DevicesAdapter.SIGNAL_DEFAULT); }

    // ---------------- FILTERED DEVICES ----------------

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

    // ---------------- ALL UNPROVISIONED DEVICES ----------------

    public LiveData<List<ExtendedBluetoothDevice>> getAllUnprovisionedDevices() { return allUnprovisionedDevices; }

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

    public void clearAllUnprovisionedDevices() { allUnprovisionedDevices.setValue(new ArrayList<>()); }

    // ---------------- FILTER UTILITY ----------------

    public boolean isFilterActive() {
        return !getDeviceNameFilterValue().isEmpty()
                || !getSelectedDeviceValue().equals(DEFAULT_SELECTED_DEVICE)
                || getSignalThresholdValue() != DevicesAdapter.SIGNAL_DEFAULT;
    }

    public String getActiveFilterDescription() {
        StringBuilder sb = new StringBuilder();

        if (!getSelectedDeviceValue().equals(DEFAULT_SELECTED_DEVICE)) {
            sb.append("no.nordicsemi.android.swaromesh.Device: ").append(getSelectedDeviceValue());
        } else if (!getDeviceNameFilterValue().isEmpty()) {
            sb.append("Name: ").append(getDeviceNameFilterValue());
        }

        if (getSignalThresholdValue() != DevicesAdapter.SIGNAL_DEFAULT) {
            if (sb.length() > 0) sb.append(" | ");
            sb.append("Signal ≥ ").append(getSignalThresholdValue()).append("%");
        }

        return sb.length() > 0 ? "Filter: " + sb : "No filter active";
    }

    public void resetAllFilters() {
        clearDeviceNameFilter();
        clearSelectedDevice();
        clearSignalThreshold();   // ← NEW
        clearFilteredDevices();
    }

    /**
     * Apply both name AND signal filters.
     * no.nordicsemi.android.swaromesh.Device must pass BOTH to be included.
     */
    public List<ExtendedBluetoothDevice> applyFilter(List<ExtendedBluetoothDevice> devices) {
        if (devices == null) return new ArrayList<>();

        // Spinner selection takes priority over typed name
        String nameFilter = !getSelectedDeviceValue().equals(DEFAULT_SELECTED_DEVICE)
                ? getSelectedDeviceValue()
                : getDeviceNameFilterValue();

        int     threshold      = getSignalThresholdValue();
        boolean hasNameFilter  = !nameFilter.isEmpty();
        boolean hasSignalFilter = threshold != DevicesAdapter.SIGNAL_DEFAULT;

        if (!hasNameFilter && !hasSignalFilter) {
            return new ArrayList<>(devices);
        }

        List<ExtendedBluetoothDevice> filtered    = new ArrayList<>();
        String                        lowerFilter = nameFilter.toLowerCase();

        for (ExtendedBluetoothDevice device : devices) {

            // Name check
            boolean nameOk = !hasNameFilter
                    || (device.getName() != null
                    && device.getName().toLowerCase().contains(lowerFilter));

            // Signal check
            boolean signalOk = !hasSignalFilter || matchesSignalThreshold(device, threshold);

            if (nameOk && signalOk) {
                filtered.add(device);
            }
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

    // ---------------- SCANNER REPOSITORY ACCESS ----------------

    public ScannerRepository getScannerRepository() { return mScannerRepository; }

    public LiveData<ScannerLiveData> getScannerResults() {
        return mScannerRepository.getScannerResults();
    }
}