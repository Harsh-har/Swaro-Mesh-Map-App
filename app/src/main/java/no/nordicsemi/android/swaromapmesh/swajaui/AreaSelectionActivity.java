package no.nordicsemi.android.swaromapmesh.swajaui;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import dagger.hilt.android.AndroidEntryPoint;
import no.nordicsemi.android.swaromapmesh.DeviceDetailActivity;
import no.nordicsemi.android.swaromapmesh.R;
import no.nordicsemi.android.swaromapmesh.TestProvisionActivity;
import no.nordicsemi.android.swaromapmesh.viewmodels.SharedViewModel;
import androidx.lifecycle.ViewModelProvider;

@AndroidEntryPoint  // ✅ IMPORTANT - Add this annotation
public class AreaSelectionActivity extends AppCompatActivity {

    private static final String TAG = "AreaSelectionActivity";
    private static final String PREFS_NAME = "mesh_prefs";
    private static final String KEY_PROVISIONED_DEVICES = "provisioned_devices";

    private TextView tvTitle, tvSubtitle, tvAreaDescription;
    private RecyclerView rvDevices;
    private LinearLayout emptyView;
    private View btnBack;
    private SharedViewModel mViewModel;

    private String areaId;
    private String areaName;
    private List<DeviceItem> deviceList = new ArrayList<>();
    private Set<String> provisionedDevices = new HashSet<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_area_selection);

        // Initialize ViewModel - Hilt will handle it now
        mViewModel = new ViewModelProvider(this).get(SharedViewModel.class);

        // Get intent data
        areaId = getIntent().getStringExtra("area_id");
        areaName = getIntent().getStringExtra("area_name");

        if (areaName == null) areaName = getIntent().getStringExtra("device_name");
        if (areaId == null) areaId = "Unknown Area";

        // Initialize views
        initViews();

        // Set header data
        setHeaderData();

        // Load provisioned devices
        loadProvisionedDevices();

        // Load devices in this area
        loadDevicesInArea();

        // Setup click listeners
        setupListeners();
    }

    private void initViews() {
        tvTitle = findViewById(R.id.tvTitle);
        tvSubtitle = findViewById(R.id.tvSubtitle);
        tvAreaDescription = findViewById(R.id.tvAreaDescription);
        rvDevices = findViewById(R.id.rvDevices);
        emptyView = findViewById(R.id.emptyView);
        btnBack = findViewById(R.id.btnBack);

        if (rvDevices != null) {
            rvDevices.setLayoutManager(new LinearLayoutManager(this));
        }
    }

    private void setHeaderData() {
        if (tvTitle != null) {
            tvTitle.setText(areaName);
        }
        if (tvSubtitle != null) {
            tvSubtitle.setText("AREA");
        }
    }

    private void loadProvisionedDevices() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        Set<String> raw = prefs.getStringSet(KEY_PROVISIONED_DEVICES, new HashSet<>());
        provisionedDevices.clear();
        provisionedDevices.addAll(raw);
    }

    private void loadDevicesInArea() {
        // Sample device data - In real app, get from SVG parsing or ViewModel
        deviceList.clear();

        // TODO: Get actual devices from areaMap in NetworkFragment
        deviceList.add(new DeviceItem("light_001", "Light 1", "element_101", false));
        deviceList.add(new DeviceItem("fan_002", "Ceiling Fan", "element_102", true));
        deviceList.add(new DeviceItem("ac_003", "Air Conditioner", "element_103", false));
        deviceList.add(new DeviceItem("sensor_004", "Temperature Sensor", "element_104", true));
        deviceList.add(new DeviceItem("switch_005", "Wall Switch", "element_105", false));

        updateUI();
    }

    private void updateUI() {
        if (deviceList.isEmpty()) {
            if (rvDevices != null) rvDevices.setVisibility(View.GONE);
            if (emptyView != null) emptyView.setVisibility(View.VISIBLE);
            if (tvAreaDescription != null) {
                tvAreaDescription.setText("No devices found in " + areaName);
            }
        } else {
            if (rvDevices != null) {
                rvDevices.setVisibility(View.VISIBLE);
                DeviceAdapter adapter = new DeviceAdapter(deviceList, provisionedDevices,
                        this::onDeviceClick);
                rvDevices.setAdapter(adapter);
            }
            if (emptyView != null) emptyView.setVisibility(View.GONE);
            if (tvAreaDescription != null) {
                tvAreaDescription.setText(deviceList.size() + " devices in this area");
            }
        }
    }

    private void onDeviceClick(DeviceItem device) {
        boolean isProvisioned = provisionedDevices.contains(device.id);

        if (isProvisioned) {
            Intent intent = new Intent(this, TestProvisionActivity.class);
            intent.putExtra(DeviceDetailActivity.EXTRA_DEVICE_ID, device.id);
            intent.putExtra(DeviceDetailActivity.EXTRA_ELEMENT_ID, device.elementId);
            intent.putExtra(DeviceDetailActivity.EXTRA_DEVICE_NAME, device.name);
            startActivity(intent);
        } else {
            Intent intent = new Intent(this, DeviceDetailActivity.class);
            intent.putExtra(DeviceDetailActivity.EXTRA_DEVICE_ID, device.id);
            intent.putExtra(DeviceDetailActivity.EXTRA_ELEMENT_ID, device.elementId);
            intent.putExtra(DeviceDetailActivity.EXTRA_DEVICE_NAME, device.name);
            startActivity(intent);
        }
    }

    private void setupListeners() {
        // Back button
        if (btnBack != null) {
            btnBack.setOnClickListener(v -> finish());
        }

        // Close on background click
        findViewById(android.R.id.content).setOnClickListener(v -> finish());
    }

    // Device Item Model
    private static class DeviceItem {
        String id;
        String name;
        String elementId;
        boolean isOnline;

        DeviceItem(String id, String name, String elementId, boolean isOnline) {
            this.id = id;
            this.name = name;
            this.elementId = elementId;
            this.isOnline = isOnline;
        }
    }

    // RecyclerView Adapter
    private static class DeviceAdapter extends RecyclerView.Adapter<DeviceAdapter.ViewHolder> {

        private List<DeviceItem> devices;
        private Set<String> provisionedDevices;
        private OnDeviceClickListener listener;

        interface OnDeviceClickListener {
            void onDeviceClick(DeviceItem device);
        }

        DeviceAdapter(List<DeviceItem> devices, Set<String> provisionedDevices,
                      OnDeviceClickListener listener) {
            this.devices = devices;
            this.provisionedDevices = provisionedDevices;
            this.listener = listener;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_room, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            DeviceItem device = devices.get(position);
            boolean isProvisioned = provisionedDevices.contains(device.id);

            holder.tvDeviceName.setText(device.name);

            String status = isProvisioned ? "✓ Provisioned" : (device.isOnline ? "● Online" : "○ Offline");
            holder.tvDeviceStatus.setText(status);

            if (isProvisioned) {
                holder.tvDeviceStatus.setTextColor(
                        holder.itemView.getContext().getColor(R.color.green_500));
            } else if (device.isOnline) {
                holder.tvDeviceStatus.setTextColor(
                        holder.itemView.getContext().getColor(R.color.black));
            } else {
                holder.tvDeviceStatus.setTextColor(
                        holder.itemView.getContext().getColor(R.color.material_grey_500));
            }

            // Set icon based on device type
            setDeviceIcon(holder.ivDeviceIcon, device.id);

            holder.itemView.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onDeviceClick(device);
                }
            });
        }

        private void setDeviceIcon(ImageView iv, String deviceId) {
            // Use default icon - you can customize based on deviceId
            iv.setImageResource(R.drawable.ic_settings);
        }

        @Override
        public int getItemCount() {
            return devices.size();
        }

        static class ViewHolder extends RecyclerView.ViewHolder {
            TextView tvDeviceName, tvDeviceStatus;
            ImageView ivDeviceIcon;

            ViewHolder(View itemView) {
                super(itemView);
                tvDeviceName = itemView.findViewById(R.id.tvDeviceName);
                tvDeviceStatus = itemView.findViewById(R.id.tvDeviceStatus);
                ivDeviceIcon = itemView.findViewById(R.id.ivDeviceIcon);
            }
        }
    }
}