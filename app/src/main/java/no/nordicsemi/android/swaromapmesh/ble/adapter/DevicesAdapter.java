package no.nordicsemi.android.swaromapmesh.ble.adapter;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.lifecycle.LifecycleOwner;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import no.nordicsemi.android.swaromapmesh.R;
import no.nordicsemi.android.swaromapmesh.adapter.ExtendedBluetoothDevice;
import no.nordicsemi.android.swaromapmesh.databinding.DeviceItemBinding;
import no.nordicsemi.android.swaromapmesh.viewmodels.ScannerLiveData;

public class DevicesAdapter extends RecyclerView.Adapter<DevicesAdapter.ViewHolder> {

    // RSSI thresholds
    public static final int SIGNAL_DEFAULT = Integer.MIN_VALUE;
    public static final int SIGNAL_20      = -85;
    public static final int SIGNAL_50      = -70;
    public static final int SIGNAL_100     = -55;

    private final List<ExtendedBluetoothDevice> mAllDevices;
    private final List<ExtendedBluetoothDevice> mDisplayedDevices;

    private String mCurrentNameFilter = "";
    private int mCurrentSignalThreshold = SIGNAL_DEFAULT;

    private OnItemClickListener mOnItemClickListener;

    public DevicesAdapter(@NonNull final LifecycleOwner owner,
                          @NonNull final ScannerLiveData scannerLiveData) {

        mAllDevices = new ArrayList<>();
        mDisplayedDevices = new ArrayList<>();

        // 🔥 FIXED: correct ScannerLiveData usage
        scannerLiveData.observe(owner, scannerData -> {

            if (scannerData == null || scannerData.getDevices() == null) return;

            List<ExtendedBluetoothDevice> devices = scannerData.getDevices();

            mAllDevices.clear();
            mAllDevices.addAll(devices);

            applyFilters(mCurrentNameFilter, mCurrentSignalThreshold);
        });
    }

    // -------------------------------------------------------------------------
    // FILTER API
    // -------------------------------------------------------------------------

    public void applyFilters(@NonNull String nameFilter, int signalThreshold) {
        mCurrentNameFilter = nameFilter;
        mCurrentSignalThreshold = signalThreshold;

        mDisplayedDevices.clear();

        for (ExtendedBluetoothDevice device : mAllDevices) {

            if (matchesNameFilter(device, nameFilter)
                    && matchesSignalFilter(device, signalThreshold)) {

                mDisplayedDevices.add(device);
            }
        }

        // Sort strongest first
        Collections.sort(mDisplayedDevices,
                (a, b) -> Integer.compare(b.getRssi(), a.getRssi()));

        notifyDataSetChanged();
    }

    public void applyFilter(@NonNull String nameFilter) {
        applyFilters(nameFilter, mCurrentSignalThreshold);
    }

    public void applySignalFilter(int signalThreshold) {
        applyFilters(mCurrentNameFilter, signalThreshold);
    }

    // -------------------------------------------------------------------------
    // FILTER LOGIC
    // -------------------------------------------------------------------------

    private boolean matchesNameFilter(@NonNull ExtendedBluetoothDevice device,
                                      @NonNull String nameFilter) {

        if (nameFilter.isEmpty()) return true;

        return device.getName() != null &&
                device.getName().toLowerCase().contains(nameFilter.toLowerCase());
    }

    private boolean matchesSignalFilter(@NonNull ExtendedBluetoothDevice device,
                                        int signalThreshold) {

        if (signalThreshold == SIGNAL_DEFAULT) return true;

        return device.getRssi() >= signalThreshold;
    }

    // -------------------------------------------------------------------------
    // UI helpers
    // -------------------------------------------------------------------------

    private int getRssiPercentage(int rssi) {
        if (rssi <= -100) return 0;
        if (rssi >= -50) return 100;
        return 2 * (rssi + 100);
    }

    private int getSignalLevel(int rssi) {
        if (rssi >= -55) return 4;
        else if (rssi >= -65) return 3;
        else if (rssi >= -75) return 2;
        else return 1;
    }

    // -------------------------------------------------------------------------
    // RecyclerView
    // -------------------------------------------------------------------------

    public void setOnItemClickListener(@NonNull final OnItemClickListener listener) {
        mOnItemClickListener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull final ViewGroup parent, int viewType) {
        return new ViewHolder(DeviceItemBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull final ViewHolder holder, int position) {

        final ExtendedBluetoothDevice device = mDisplayedDevices.get(position);

        final String name = device.getName();
        final int rssi = device.getRssi();

        holder.deviceName.setText(TextUtils.isEmpty(name)
                ? holder.deviceName.getContext().getString(R.string.unknown_device)
                : name);

        holder.deviceAddress.setText(device.getAddress() + " (" + rssi + " dBm)");

        holder.rssi.setImageLevel(getSignalLevel(rssi));
    }

    @Override
    public int getItemCount() {
        return mDisplayedDevices.size();
    }

    public boolean isEmpty() {
        return mDisplayedDevices.isEmpty();
    }

    // -------------------------------------------------------------------------
    // CLICK
    // -------------------------------------------------------------------------

    @FunctionalInterface
    public interface OnItemClickListener {
        void onItemClick(final ExtendedBluetoothDevice device);
    }

    final class ViewHolder extends RecyclerView.ViewHolder {

        TextView deviceAddress;
        TextView deviceName;
        ImageView rssi;

        private ViewHolder(@NonNull DeviceItemBinding binding) {
            super(binding.getRoot());

            deviceAddress = binding.deviceAddress;
            deviceName = binding.deviceName;
            rssi = binding.rssi;

            binding.deviceContainer.setOnClickListener(v -> {
                if (mOnItemClickListener != null) {
                    int pos = getBindingAdapterPosition();

                    if (pos != RecyclerView.NO_POSITION
                            && pos < mDisplayedDevices.size()) {
                        mOnItemClickListener.onItemClick(mDisplayedDevices.get(pos));
                    }
                }
            });
        }
    }
}