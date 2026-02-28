package no.nordicsemi.android.swaromesh.ble.adapter;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.lifecycle.LifecycleOwner;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import no.nordicsemi.android.swaromesh.R;
import no.nordicsemi.android.swaromesh.adapter.ExtendedBluetoothDevice;
import no.nordicsemi.android.swaromesh.databinding.DeviceItemBinding;
import no.nordicsemi.android.swaromesh.viewmodels.ScannerLiveData;

public class DevicesAdapter extends RecyclerView.Adapter<DevicesAdapter.ViewHolder> {

    // Signal strength threshold constants (percentage)
    public static final int SIGNAL_DEFAULT = 0;   // No RSSI filter
    public static final int SIGNAL_20      = 10;
    public static final int SIGNAL_60      = 34;
    public static final int SIGNAL_100     = 40;

    // All devices from scanner (unfiltered source of truth)
    private final List<ExtendedBluetoothDevice> mAllDevices;

    // Currently displayed devices (filtered)
    private final List<ExtendedBluetoothDevice> mDisplayedDevices;

    // Current active filters
    private String mCurrentNameFilter     = "";
    private int    mCurrentSignalThreshold = SIGNAL_DEFAULT;

    private OnItemClickListener mOnItemClickListener;

    public DevicesAdapter(@NonNull final LifecycleOwner owner,
                          @NonNull final ScannerLiveData scannerLiveData) {

        mAllDevices      = scannerLiveData.getDevices();
        mDisplayedDevices = new ArrayList<>(mAllDevices);

        scannerLiveData.observe(owner, devices -> {
            // New scan results arrived — re-apply current filters so display stays consistent
            applyFilters(mCurrentNameFilter, mCurrentSignalThreshold);
        });
    }

    // -------------------------------------------------------------------------
    // Public filter API
    // -------------------------------------------------------------------------

    /**
     * Apply both name filter AND signal strength threshold together.
     * A device must pass BOTH to be shown.
     *
     * @param nameFilter      no.nordicsemi.android.swaromesh.Device name substring (empty = no name filter)
     * @param signalThreshold Minimum signal % (0 = no threshold, 20/60/100 = filter)
     */
    public void applyFilters(@NonNull String nameFilter, int signalThreshold) {
        mCurrentNameFilter      = nameFilter;
        mCurrentSignalThreshold = signalThreshold;
        mDisplayedDevices.clear();

        for (ExtendedBluetoothDevice device : mAllDevices) {
            if (matchesNameFilter(device, nameFilter)
                    && matchesSignalFilter(device, signalThreshold)) {
                mDisplayedDevices.add(device);
            }
        }

        notifyDataSetChanged();
    }

    /**
     * Apply name filter only — keeps the current signal threshold.
     */
    public void applyFilter(@NonNull String nameFilter) {
        applyFilters(nameFilter, mCurrentSignalThreshold);
    }

    /**
     * Apply signal threshold only — keeps the current name filter.
     */
    public void applySignalFilter(int signalThreshold) {
        applyFilters(mCurrentNameFilter, signalThreshold);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Returns true if the device name contains the filter string (case-insensitive).
     * Empty filter matches everything.
     */
    private boolean matchesNameFilter(@NonNull ExtendedBluetoothDevice device,
                                      @NonNull String nameFilter) {
        if (nameFilter.isEmpty()) return true;
        return device.getName() != null
                && device.getName().toLowerCase().contains(nameFilter.toLowerCase());
    }

    /**
     * Returns true if the device RSSI percentage meets the minimum threshold.
     * SIGNAL_DEFAULT (0) matches everything.
     *
     * Formula: rssiPercent = 100 * (127 + rssi) / (127 + 20)
     *   threshold 20%  →  rssi >= -107
     *   threshold 60%  →  rssi >= -68
     *   threshold 100% →  rssi >= -20
     */
    private boolean matchesSignalFilter(@NonNull ExtendedBluetoothDevice device,
                                        int signalThreshold) {
        if (signalThreshold == SIGNAL_DEFAULT) return true;
        int rssiPercent = (int) (100.0f * (127.0f + device.getRssi()) / (127.0f + 20.0f));
        return rssiPercent >= signalThreshold;
    }

    // -------------------------------------------------------------------------
    // RecyclerView boilerplate
    // -------------------------------------------------------------------------

    public void setOnItemClickListener(@NonNull final OnItemClickListener listener) {
        mOnItemClickListener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull final ViewGroup parent, final int viewType) {
        return new ViewHolder(DeviceItemBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull final ViewHolder holder, final int position) {
        final ExtendedBluetoothDevice device     = mDisplayedDevices.get(position);
        final String                  deviceName = device.getName();

        holder.deviceName.setText(TextUtils.isEmpty(deviceName)
                ? holder.deviceName.getContext().getString(R.string.unknown_device)
                : deviceName);

        holder.deviceAddress.setText(device.getAddress());

        final int rssiPercent = (int) (100.0f * (127.0f + device.getRssi()) / (127.0f + 20.0f));
        holder.rssi.setImageLevel(rssiPercent);
    }

    @Override
    public long getItemId(final int position) {
        return position;
    }

    @Override
    public int getItemCount() {
        return mDisplayedDevices.size();
    }

    public boolean isEmpty() {
        return getItemCount() == 0;
    }

    @FunctionalInterface
    public interface OnItemClickListener {
        void onItemClick(final ExtendedBluetoothDevice device);
    }

    final class ViewHolder extends RecyclerView.ViewHolder {
        TextView  deviceAddress;
        TextView  deviceName;
        ImageView rssi;

        private ViewHolder(final @NonNull DeviceItemBinding binding) {
            super(binding.getRoot());
            deviceAddress = binding.deviceAddress;
            deviceName    = binding.deviceName;
            rssi          = binding.rssi;

            binding.deviceContainer.setOnClickListener(v -> {
                if (mOnItemClickListener != null) {
                    int pos = getAdapterPosition();
                    if (pos > -1 && !mDisplayedDevices.isEmpty()) {
                        mOnItemClickListener.onItemClick(mDisplayedDevices.get(pos));
                    }
                }
            });
        }
    }
}