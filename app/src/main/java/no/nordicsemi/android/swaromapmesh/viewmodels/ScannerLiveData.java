
package no.nordicsemi.android.swaromapmesh.viewmodels;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;

import java.util.ArrayList;
import java.util.List;

import no.nordicsemi.android.swaromapmesh.MeshBeacon;
import no.nordicsemi.android.swaromapmesh.adapter.ExtendedBluetoothDevice;
import no.nordicsemi.android.support.v18.scanner.ScanResult;

/**
 * This class keeps the current list of discovered Bluetooth LE devices matching filter.
 * If a new device has been found it is added to the list and the LiveData in observers are
 * notified. If a packet from a device that's already in the list is found, the RSSI and name
 * are updated and observers are also notified. Observer may check {@link #getUpdatedDeviceIndex()}
 * to find out the index of the updated device.
 */
public class ScannerLiveData extends LiveData<ScannerLiveData> {
    private final List<ExtendedBluetoothDevice> mDevices = new ArrayList<>();
    private Integer mUpdatedDeviceIndex;

    ScannerLiveData() {
    }

    void deviceDiscovered(final ScanResult result) {
        ExtendedBluetoothDevice device;

        final int index = indexOf(result);
        if (index == -1) {
            device = new ExtendedBluetoothDevice(result);
            mDevices.add(device);
            mUpdatedDeviceIndex = null;
        } else {
            device = mDevices.get(index);
            mUpdatedDeviceIndex = index;
        }
        // Update RSSI and name
        device.setRssi(result.getRssi());
        device.setName(getDeviceName(result));

        postValue(this);
    }

    void deviceDiscovered(final ScanResult result, final MeshBeacon beacon) {
        ExtendedBluetoothDevice device;

        final int index = indexOf(result);
        if (index == -1) {
            device = new ExtendedBluetoothDevice(result, beacon);
            mDevices.add(device);
            mUpdatedDeviceIndex = null;
        } else {
            device = mDevices.get(index);
            mUpdatedDeviceIndex = index;
        }
        // Update RSSI and name
        device.setRssi(result.getRssi());
        device.setName(getDeviceName(result));

        postValue(this);
    }

    /**
     * Returns the device name from a scan record
     *
     * @param result ScanResult
     * @return no.nordicsemi.android.swaromesh.Device name found in the scan record or unknown
     */
    private String getDeviceName(final ScanResult result) {
        if (result.getScanRecord() != null)
            return result.getScanRecord().getDeviceName();
        return "Unknown";
    }

    /**
     * Clears the list of devices found.
     */
    void clear() {
        mDevices.clear();
        mUpdatedDeviceIndex = null;
        postValue(this);
    }

    /**
     * Returns the list of devices.
     *
     * @return current list of devices discovered
     */
    @NonNull
    public List<ExtendedBluetoothDevice> getDevices() {
        return mDevices;
    }

    /**
     * Returns null if a new device was added, or an index of the updated device.
     */
    @Nullable
    public Integer getUpdatedDeviceIndex() {
        final Integer i = mUpdatedDeviceIndex;
        mUpdatedDeviceIndex = null;
        return i;
    }

    /**
     * Returns whether the list is empty.
     */
    public boolean isEmpty() {
        return mDevices.isEmpty();
    }

    /**
     * Finds the index of existing devices on the scan results list.
     *
     * @param result scan result
     * @return index of -1 if not found
     */
    private int indexOf(final ScanResult result) {
        int i = 0;
        for (final ExtendedBluetoothDevice device : mDevices) {
            if (device.matches(result))
                return i;
            i++;
        }
        return -1;
    }
}
