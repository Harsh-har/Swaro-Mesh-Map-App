package no.nordicsemi.android.swaromapmesh;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.textview.MaterialTextView;
import com.google.android.material.button.MaterialButton;

import java.util.HashSet;
import java.util.Set;

import no.nordicsemi.android.swaromapmesh.ble.MeshCommandManager;

public class TestProvisionActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "mesh_prefs";
    private static final String KEY_PROVISIONED_DEVICES = "provisioned_devices";

    private String deviceId;
    private String elementId;

    private MaterialTextView tvDeviceId, tvElementId, tvStatus;
    private MaterialButton btnTest;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_test_provision);

        // ✅ Get data from intent
        deviceId = getIntent().getStringExtra(DeviceDetailActivity.EXTRA_DEVICE_ID);
        elementId = getIntent().getStringExtra(DeviceDetailActivity.EXTRA_ELEMENT_ID);

        // ✅ Bind views
        tvDeviceId = findViewById(R.id.tv_device_id);
        tvElementId = findViewById(R.id.tv_element_id);
        tvStatus = findViewById(R.id.tv_status);
        btnTest = findViewById(R.id.btn_test);

        // ✅ Set values
        tvDeviceId.setText("" + (deviceId != null ? deviceId : "N/A"));
        tvElementId.setText("" + (elementId != null ? elementId : "N/A"));

        // ✅ Update status
        updateStatus();

        // ✅ Test button logic
        btnTest.setOnClickListener(v -> {
            if (isProvisioned(deviceId)) {
                MeshCommandManager.sendTestCommand(this, deviceId);
            } else {
                Toast.makeText(this, "Device not provisioned!", Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ================= STATUS LOGIC =================

    private void updateStatus() {
        if (isProvisioned(deviceId)) {
            tvStatus.setText("Provisioned");
            tvStatus.setTextColor(getResources().getColor(android.R.color.holo_green_light));
        } else {
            tvStatus.setText("Not Provisioned");
            tvStatus.setTextColor(getResources().getColor(android.R.color.holo_orange_light));
        }
    }

    private boolean isProvisioned(String id) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        Set<String> devices = prefs.getStringSet(KEY_PROVISIONED_DEVICES, new HashSet<>());
        return devices.contains(id);
    }
}