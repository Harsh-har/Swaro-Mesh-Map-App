package no.nordicsemi.android.swaromapmesh;

import static androidx.core.content.ContentProviderCompat.requireContext;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import dagger.hilt.android.AndroidEntryPoint;
import no.nordicsemi.android.swaromapmesh.ble.ScannerActivity;
import no.nordicsemi.android.swaromapmesh.databinding.ActivityDeviceDetailBinding;
import no.nordicsemi.android.swaromapmesh.node.NodeConfigurationActivity;
import no.nordicsemi.android.swaromapmesh.utils.Utils;

/**
 * Shows device details and operations when a device is tapped on the SVG map.
 *
 * Receives via Intent:
 *   EXTRA_DEVICE_ID    → SVG element id   (e.g. "c_2", "SW-RL01-006A")
 *   EXTRA_ELEMENT_ID   → metadata elementId value (e.g. "1", "2", ...)
 *   EXTRA_DEVICE_NAME  → human-readable label (same as device id for now)
 */
@AndroidEntryPoint
public class DeviceDetailActivity extends AppCompatActivity {

    private static final String TAG = "DeviceDetailActivity";

    public static final String EXTRA_DEVICE_ID   = "device_id";
    public static final String EXTRA_ELEMENT_ID  = "element_id";
    public static final String EXTRA_DEVICE_NAME = "device_name";

    private ActivityDeviceDetailBinding binding;

    private String deviceId;
    private String elementId;
    private String deviceName;
    private final ActivityResultLauncher<Intent> provisioner =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                    this::handleProvisioningResult);

    private final ActivityResultLauncher<Intent> proxyConnector =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                    this::handleProxyConnectResult);





    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityDeviceDetailBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Read extras
        deviceId   = getIntent().getStringExtra(EXTRA_DEVICE_ID);
        elementId  = getIntent().getStringExtra(EXTRA_ELEMENT_ID);
        deviceName = getIntent().getStringExtra(EXTRA_DEVICE_NAME);

        if (deviceId == null) {
            Log.e(TAG, "No device id passed — finishing");
            finish();
            return;
        }

        setupToolbar();
        populateDeviceInfo();
        setupButtons();
    }


    // ==================== UI SETUP ====================

    private void setupToolbar() {
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(deviceName != null ? deviceName : deviceId);
        }
    }

    private void populateDeviceInfo() {
        // Device ID row
        binding.tvDeviceIdValue.setText(deviceId);

        // Element ID row (from SVG metadata)
        if (elementId != null && !elementId.isEmpty()) {
            binding.tvElementIdValue.setText(elementId);
        } else {
            binding.tvElementIdValue.setText("—");
        }

        // Status — placeholder until BLE is connected

        Log.d(TAG, "Showing device: id=" + deviceId + " elementId=" + elementId);
    }

    private void setupButtons() {

        // Connect / Scan button → opens ScannerActivity for this device
        binding.btnConnect.setOnClickListener(v -> {
            Intent intent = new Intent(this, ScannerActivity.class);
            intent.putExtra(EXTRA_DEVICE_ID, deviceId);
            intent.putExtra(EXTRA_ELEMENT_ID, elementId);
            startActivity(intent);
        });

        // start Add to network
        binding.addToNetwork.setOnClickListener(v -> {
            Log.d(TAG, "Add to Network: " + deviceId);

            Toast.makeText(DeviceDetailActivity.this,
                    "Start Add to Network: " + deviceId,
                    Toast.LENGTH_SHORT).show();

            final Intent intent = new Intent(this, ScannerActivity.class);
            intent.putExtra(Utils.EXTRA_DATA_PROVISIONING_SERVICE, true);
            provisioner.launch(intent);
        });

        // Turn OFF
        binding.btnTest.setOnClickListener(v -> {
            Log.d(TAG, "Test Device: " + deviceId);
            Toast.makeText(this,
                    "Start Testing Device: " + deviceId, Toast.LENGTH_SHORT).show();

        });


    }

    private void handleProvisioningResult(final ActivityResult result) {
        final Intent data = result.getData();
        if (result.getResultCode() == RESULT_OK && data != null) {
            final boolean provisioningSuccess =
                    data.getBooleanExtra(Utils.PROVISIONING_COMPLETED, false);

            if (provisioningSuccess) {
                final Intent intent = new Intent(this, ScannerActivity.class);
                intent.putExtra(Utils.EXTRA_DATA_PROVISIONING_SERVICE, false);
                intent.putExtra(Utils.EXTRA_NEWLY_PROVISIONED_NODE, true);
                intent.putExtra(Utils.EXTRA_SILENT_CONNECT, true);
                proxyConnector.launch(intent);
            }
            this.invalidateOptionsMenu();
        }
    }

    private void handleProxyConnectResult(final ActivityResult result) {
        if (result.getResultCode() == RESULT_OK) {
            startActivity(new Intent(this, NodeConfigurationActivity.class));
        }
    }


    // ==================== NAVIGATION ====================

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}