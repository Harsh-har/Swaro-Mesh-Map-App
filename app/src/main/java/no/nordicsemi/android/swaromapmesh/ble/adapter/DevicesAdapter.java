package no.nordicsemi.android.swaromapmesh.ble.adapter;

import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.lifecycle.LifecycleOwner;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import no.nordicsemi.android.swaromapmesh.R;
import no.nordicsemi.android.swaromapmesh.adapter.ExtendedBluetoothDevice;
import no.nordicsemi.android.swaromapmesh.databinding.DeviceItemBinding;
import no.nordicsemi.android.swaromapmesh.viewmodels.ScannerLiveData;

public class DevicesAdapter extends RecyclerView.Adapter<DevicesAdapter.ViewHolder> {

    private static final String TAG = "DevicesAdapter";

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

    // Smoothed RSSI per device address (exponential moving average).
    // Raw BLE RSSI readings fluctuate by ±10-15 dBm between consecutive scans
    // even for a stationary device, which causes the sorted list to jump
    // around constantly. Smoothing keeps the displayed order and signal
    // bars stable while still reacting to genuine signal-strength changes.
    private final Map<String, Double> mSmoothedRssi = new HashMap<>();
    private static final double RSSI_SMOOTHING_ALPHA = 0.25; // lower = smoother/slower

    private OnItemClickListener mOnItemClickListener;

    public DevicesAdapter(@NonNull final LifecycleOwner owner,
                          @NonNull final ScannerLiveData scannerLiveData) {

        mAllDevices      = scannerLiveData.getDevices();
        mDisplayedDevices = new ArrayList<>(mAllDevices);

        scannerLiveData.observe(owner, devices -> {
            // New scan results arrived — re-apply current filters so display stays consistent
            Log.d(TAG, "scannerLiveData observer fired, mAllDevices size=" + mAllDevices.size());
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
     * @param nameFilter      Device name substring (empty = no name filter)
     * @param signalThreshold Minimum signal % (0 = no threshold, 20/60/100 = filter)
     */
    public void applyFilters(@NonNull String nameFilter, int signalThreshold) {
        mCurrentNameFilter      = nameFilter;
        mCurrentSignalThreshold = signalThreshold;
        mDisplayedDevices.clear();

        // Update the smoothed RSSI for every known device before filtering/sorting,
        // so both operations use the stable value instead of the noisy raw reading.
        for (ExtendedBluetoothDevice device : mAllDevices) {
            updateSmoothedRssi(device);
        }

        for (ExtendedBluetoothDevice device : mAllDevices) {
            if (matchesNameFilter(device, nameFilter)
                    && matchesSignalFilter(device, signalThreshold)) {
                mDisplayedDevices.add(device);
            }
        }

        // Sort strongest signal first using the smoothed value, so the list
        // doesn't reorder on every tiny raw RSSI blip.
        Collections.sort(mDisplayedDevices, (d1, d2) ->
                Double.compare(getSmoothedRssi(d2), getSmoothedRssi(d1)));

        Log.d(TAG, "applyFilters: nameFilter='" + nameFilter
                + "' signalThreshold=" + signalThreshold
                + " -> displayed=" + mDisplayedDevices.size()
                + " of total=" + mAllDevices.size());

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
        int rssiPercent = calculateRssiPercent((int) Math.round(getSmoothedRssi(device)));
        return rssiPercent >= signalThreshold;
    }

    /**
     * Updates the exponential moving average for a device's RSSI.
     * First reading seeds the average directly (no smoothing needed yet).
     */
    private void updateSmoothedRssi(@NonNull ExtendedBluetoothDevice device) {
        final String address = device.getAddress();
        if (address == null) return;
        final double rawRssi = device.getRssi();
        final Double previous = mSmoothedRssi.get(address);
        final double smoothed = (previous == null)
                ? rawRssi
                : (RSSI_SMOOTHING_ALPHA * rawRssi) + ((1 - RSSI_SMOOTHING_ALPHA) * previous);
        mSmoothedRssi.put(address, smoothed);
    }

    /**
     * Returns the smoothed RSSI for a device, falling back to its raw RSSI
     * if no smoothed value has been recorded yet (e.g. address missing).
     */
    private double getSmoothedRssi(@NonNull ExtendedBluetoothDevice device) {
        final String address = device.getAddress();
        if (address == null) return device.getRssi();
        final Double smoothed = mSmoothedRssi.get(address);
        return smoothed != null ? smoothed : device.getRssi();
    }

    /**
     * Shared RSSI -> percent calculation, used both for filtering and for
     * driving the signal-bar icon level, so the two never drift apart.
     */
    private int calculateRssiPercent(int rssi) {
        int percent = (int) (100.0f * (127.0f + rssi) / (127.0f + 20.0f));
        // Clamp so we never feed an out-of-range level to the level-list drawable
        if (percent < 0) percent = 0;
        if (percent > 100) percent = 100;
        return percent;
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

        final int rawRssi      = device.getRssi();
        final double smoothedRssi = getSmoothedRssi(device);
        final int rssiPercent  = calculateRssiPercent((int) Math.round(smoothedRssi));

        // 🔍 DEBUG LOG — remove once RSSI icon issue is confirmed fixed
        Log.d(TAG, "onBindViewHolder pos=" + position
                + " name=" + deviceName
                + " address=" + device.getAddress()
                + " rawRssi=" + rawRssi
                + " smoothedRssi=" + smoothedRssi
                + " rssiPercent=" + rssiPercent);

        // Load a fresh mutable instance of the level-list drawable for this row.
        // Without mutate(), Android shares one ConstantState across every
        // ImageView pointing at @drawable/ic_signal_bar, so setImageLevel()
        // on a recycled row can silently fail to redraw the correct icon.
        final android.graphics.drawable.Drawable rssiDrawable =
                androidx.core.content.ContextCompat
                        .getDrawable(holder.rssi.getContext(), R.drawable.ic_signal_bar)
                        .mutate();
        holder.rssi.setImageDrawable(rssiDrawable);
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