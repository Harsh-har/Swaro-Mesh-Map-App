package no.nordicsemi.android.swaromapmesh.ble.adapter;

import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import no.nordicsemi.android.swaromapmesh.R;
import no.nordicsemi.android.swaromapmesh.adapter.ExtendedBluetoothDevice;
import no.nordicsemi.android.swaromapmesh.databinding.DeviceItemBinding;
import no.nordicsemi.android.swaromapmesh.utils.DeviceCodes;
import no.nordicsemi.android.swaromapmesh.viewmodels.ScannerLiveData;

public class DevicesAdapter extends RecyclerView.Adapter<DevicesAdapter.ViewHolder> {

    private static final String TAG = "DevicesAdapter";

    // Signal strength threshold constants (raw RSSI dBm)
    public static final int SIGNAL_ALL         = -100; // No RSSI filter (show everything)
    public static final int SIGNAL_WEAK        = -90;
    public static final int SIGNAL_MEDIUM      = -75;
    public static final int SIGNAL_STRONG      = -60;
    public static final int SIGNAL_VERY_STRONG = -45;

    public static final int SIGNAL_DEFAULT = SIGNAL_ALL;

    // All devices from scanner (unfiltered source of truth)
    private final List<ExtendedBluetoothDevice> mAllDevices;

    // Currently displayed devices (filtered)
    private final List<ExtendedBluetoothDevice> mDisplayedDevices;

    // Current active filters
    private String mCurrentNameFilter      = "";
    private int    mCurrentSignalThreshold = SIGNAL_DEFAULT;

    // Smoothed RSSI per device address (exponential moving average).
    private final Map<String, Double> mSmoothedRssi = new HashMap<>();
    private static final double RSSI_SMOOTHING_ALPHA = 0.25; // lower = smoother/slower

    private OnItemClickListener mOnItemClickListener;

    public DevicesAdapter(@NonNull final LifecycleOwner owner,
                          @NonNull final ScannerLiveData scannerLiveData) {

        mAllDevices      = scannerLiveData.getDevices();
        mDisplayedDevices = new ArrayList<>(mAllDevices);

        scannerLiveData.observe(owner, devices -> {
            Log.d(TAG, "scannerLiveData observer fired, mAllDevices size=" + mAllDevices.size());
            applyFilters(mCurrentNameFilter, mCurrentSignalThreshold);
        });
    }

    public void applyFilters(@NonNull String nameFilter, int signalThreshold) {
        mCurrentNameFilter      = nameFilter;
        mCurrentSignalThreshold = signalThreshold;
        mDisplayedDevices.clear();

        for (ExtendedBluetoothDevice device : mAllDevices) {
            updateSmoothedRssi(device);
        }

        for (ExtendedBluetoothDevice device : mAllDevices) {
            if (matchesNameFilter(device, nameFilter)
                    && matchesSignalFilter(device, signalThreshold)) {
                mDisplayedDevices.add(device);
            }
        }

        Log.d(TAG, "applyFilters: nameFilter='" + nameFilter
                + "' signalThreshold=" + signalThreshold
                + " -> displayed=" + mDisplayedDevices.size()
                + " of total=" + mAllDevices.size());

        notifyDataSetChanged();
    }

    public void applyFilter(@NonNull String nameFilter) {
        applyFilters(nameFilter, mCurrentSignalThreshold);
    }

    public void applySignalFilter(int signalThreshold) {
        applyFilters(mCurrentNameFilter, signalThreshold);
    }

    private boolean matchesNameFilter(@NonNull ExtendedBluetoothDevice device,
                                      @NonNull String nameFilter) {
        if (nameFilter.isEmpty()) return true;
        return DeviceCodes.matches(device.getName(), nameFilter);
    }

    private boolean matchesSignalFilter(@NonNull ExtendedBluetoothDevice device,
                                        int signalThreshold) {
        if (signalThreshold == SIGNAL_ALL) return true;
        double smoothedRssi = getSmoothedRssi(device);
        return smoothedRssi >= (double) signalThreshold;
    }

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

    private double getSmoothedRssi(@NonNull ExtendedBluetoothDevice device) {
        final String address = device.getAddress();
        if (address == null) return device.getRssi();
        final Double smoothed = mSmoothedRssi.get(address);
        return smoothed != null ? smoothed : device.getRssi();
    }

    private int calculateRssiPercent(int rssi) {
        int percent = (int) (100.0f * (127.0f + rssi) / (127.0f + 20.0f));
        if (percent < 0) percent = 0;
        if (percent > 100) percent = 100;
        return percent;
    }

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

        String displayedName = TextUtils.isEmpty(deviceName)
                ? holder.deviceName.getContext().getString(R.string.unknown_device)
                : deviceName;

        holder.deviceName.setText(displayedName);
        holder.deviceAddress.setText(device.getAddress());

        final int rawRssi      = device.getRssi();
        final double smoothedRssi = getSmoothedRssi(device);
        final int rssiPercent  = calculateRssiPercent((int) Math.round(smoothedRssi));

        final Drawable rssiDrawable =
                ContextCompat.getDrawable(holder.rssi.getContext(), R.drawable.ic_signal_bar)
                        .mutate();
        holder.rssi.setImageDrawable(rssiDrawable);
        holder.rssi.setImageLevel(rssiPercent);

        holder.rssiValue.setText(holder.rssiValue.getContext().getString(R.string.rssi_format, rawRssi));
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
        TextView       deviceAddress;
        TextView       deviceName;
        ImageView      rssi;
        TextView       rssiValue;

        private ViewHolder(final @NonNull DeviceItemBinding binding) {
            super(binding.getRoot());
            deviceAddress    = binding.deviceAddress;
            deviceName       = binding.deviceName;
            rssi             = binding.rssi;
            rssiValue        = binding.rssiValue;

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
