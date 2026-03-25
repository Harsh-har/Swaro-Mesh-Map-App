package no.nordicsemi.android.swaromapmesh;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.textview.MaterialTextView;
import com.google.android.material.button.MaterialButton;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import dagger.hilt.android.AndroidEntryPoint;

import no.nordicsemi.android.swaromapmesh.ble.MeshCommandManager;
import no.nordicsemi.android.swaromapmesh.viewmodels.SharedViewModel;

@AndroidEntryPoint
public class TestProvisionActivity extends AppCompatActivity {

    private static final String PREFS_NAME              = "mesh_prefs";
    private static final String KEY_PROVISIONED_DEVICES = "provisioned_devices";

    private String deviceId;
    private String elementId;

    private MaterialTextView tvDeviceId, tvElementId, tvStatus;
    private MaterialButton   btnTest;

    private SharedViewModel     mViewModel;
    private final AtomicInteger tidCounter = new AtomicInteger(0);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_test_provision);

        mViewModel = new ViewModelProvider(this).get(SharedViewModel.class);

        deviceId  = getIntent().getStringExtra(DeviceDetailActivity.EXTRA_DEVICE_ID);
        elementId = getIntent().getStringExtra(DeviceDetailActivity.EXTRA_ELEMENT_ID);

        tvDeviceId  = findViewById(R.id.tv_device_id);
        tvElementId = findViewById(R.id.tv_element_id);
        tvStatus    = findViewById(R.id.tv_status);
        btnTest     = findViewById(R.id.btn_test);

        tvDeviceId.setText(deviceId   != null ? deviceId   : "N/A");
        tvElementId.setText(elementId != null ? elementId : "N/A");

        updateStatus();

        btnTest.setOnClickListener(v -> {
            if (!isProvisioned(deviceId)) {
                Toast.makeText(this, "Device not provisioned!", Toast.LENGTH_SHORT).show();
                return;
            }

            MeshCommandManager.startBlink(mViewModel, tidCounter);
            Toast.makeText(this, "Blinking...", Toast.LENGTH_SHORT).show();
            btnTest.setEnabled(false);

            int totalBlinkMs =
                    (MeshCommandManager.BLINK_ON_MS + MeshCommandManager.BLINK_OFF_MS)
                            * MeshCommandManager.BLINK_COUNT + 100;

            btnTest.postDelayed(() -> btnTest.setEnabled(true), totalBlinkMs);
        });
    }

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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        MeshCommandManager.stopBlink(mViewModel, tidCounter);
    }
}