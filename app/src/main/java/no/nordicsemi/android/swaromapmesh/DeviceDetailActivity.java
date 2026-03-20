package no.nordicsemi.android.swaromapmesh;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import java.util.HashSet;
import java.util.Set;

import dagger.hilt.android.AndroidEntryPoint;
import no.nordicsemi.android.swaromapmesh.ble.ScannerActivity;
import no.nordicsemi.android.swaromapmesh.databinding.ActivityDeviceDetailBinding;
import no.nordicsemi.android.swaromapmesh.node.NodeConfigurationActivity;
import no.nordicsemi.android.swaromapmesh.utils.Utils;

@AndroidEntryPoint
public class DeviceDetailActivity extends AppCompatActivity {

    private static final String TAG = "DeviceDetailActivity";

    private static final String PREFS_NAME              = "mesh_prefs";
    private static final String KEY_PROVISIONED_DEVICES = "provisioned_devices";

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

        deviceId   = getIntent().getStringExtra(EXTRA_DEVICE_ID);
        elementId  = getIntent().getStringExtra(EXTRA_ELEMENT_ID);
        deviceName = getIntent().getStringExtra(EXTRA_DEVICE_NAME);

        if (deviceId == null) {
            Log.e(TAG, "No device id — finishing");
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
        binding.tvDeviceIdValue.setText(deviceId);
        binding.tvElementIdValue.setText(
                (elementId != null && !elementId.isEmpty()) ? elementId : "—");
        Log.d(TAG, "Showing device: id=" + deviceId + " elementId=" + elementId);
    }

    private void setupButtons() {

        binding.btnConnect.setOnClickListener(v -> {
            Intent intent = new Intent(this, ScannerActivity.class);
            intent.putExtra(EXTRA_DEVICE_ID, deviceId);
            intent.putExtra(EXTRA_ELEMENT_ID, elementId);
            startActivity(intent);
        });

        binding.addToNetwork.setOnClickListener(v -> {
            Log.d(TAG, "Add to Network: " + deviceId);
            Toast.makeText(this, "Starting provisioning for: " + deviceId,
                    Toast.LENGTH_SHORT).show();

            Intent intent = new Intent(this, ScannerActivity.class);
            intent.putExtra(Utils.EXTRA_DATA_PROVISIONING_SERVICE, true);
            intent.putExtra(Utils.EXTRA_SVG_DEVICE_ID, deviceId);
            provisioner.launch(intent);
        });

        binding.btnTest.setOnClickListener(v -> {
            Log.d(TAG, "Test Device: " + deviceId);
            Toast.makeText(this, "Testing: " + deviceId, Toast.LENGTH_SHORT).show();
        });
    }

    // ==================== RESULT HANDLERS ====================

    private void handleProvisioningResult(final ActivityResult result) {
        Log.d(TAG, "handleProvisioningResult: code=" + result.getResultCode());

        if (result.getResultCode() != RESULT_OK || result.getData() == null) {
            Log.d(TAG, "Provisioning cancelled or no data");
            return;
        }

        final Intent data = result.getData();

        boolean provisioningSuccess = data.getBooleanExtra(Utils.PROVISIONING_COMPLETED, false);
        Log.d(TAG, "PROVISIONING_COMPLETED=" + provisioningSuccess);

        if (!provisioningSuccess) {
            Log.d(TAG, "Provisioning not completed");
            return;
        }

        String svgDeviceId = data.getStringExtra(Utils.EXTRA_SVG_DEVICE_ID);
        Log.d(TAG, "svgDeviceId from result=" + svgDeviceId);

        if (svgDeviceId == null || svgDeviceId.isEmpty()) {
            svgDeviceId = deviceId;
            Log.w(TAG, "svgDeviceId null, fallback to deviceId=" + deviceId);
        }

        // ✅ Directly SharedPreferences mein save — ViewModel scope bypass
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        Set<String> current = new HashSet<>(
                prefs.getStringSet(KEY_PROVISIONED_DEVICES, new HashSet<>()));
        current.add(svgDeviceId);
        prefs.edit().putStringSet(KEY_PROVISIONED_DEVICES, new HashSet<>(current)).apply();

        Log.d(TAG, "Saved provisioned device to prefs: " + svgDeviceId);

        Toast.makeText(this,
                deviceId + " provisioned! Icon will turn green.",
                Toast.LENGTH_LONG).show();

        finish();
    }

    private void handleProxyConnectResult(final ActivityResult result) {
        Log.d(TAG, "handleProxyConnectResult: code=" + result.getResultCode());
        if (result.getResultCode() == RESULT_OK) {
            startActivity(new Intent(this, NodeConfigurationActivity.class));
        }
        finish();
    }

    // ==================== NAVIGATION ====================

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}