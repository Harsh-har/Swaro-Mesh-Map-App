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
import no.nordicsemi.android.swaromapmesh.viewmodels.SharedViewModel;

@AndroidEntryPoint
public class DeviceDetailActivity extends AppCompatActivity {

    private static final String TAG = "DeviceDetailActivity";

    private static final String PREFS_NAME = "mesh_prefs";
    private static final String KEY_PROVISIONED_DEVICES = "provisioned_devices";
    private static final String KEY_SERVER_SVG_DEVICE_ID = "server_svg_device_id";

    public static final String EXTRA_DEVICE_ID = "device_id";
    public static final String EXTRA_ELEMENT_ID = "element_id";
    public static final String EXTRA_DEVICE_NAME = "device_name";
    public static final String EXTRA_AUTO_FILTER_DEVICE = "auto_filter_device";
    public static final String EXTRA_DEVICE_TYPE = "device_type";
    public static final String DEVICE_TYPE_SERVER = "server";
    public static final String DEVICE_TYPE_CLIENT = "client";

    private ActivityDeviceDetailBinding binding;
    private SharedViewModel sharedViewModel;

    private String deviceId;
    private String elementId;
    private String deviceName;
    private String deviceType;

    private final ActivityResultLauncher<Intent> provisioner =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    this::handleProvisioningResult);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityDeviceDetailBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        sharedViewModel = new androidx.lifecycle.ViewModelProvider(this)
                .get(SharedViewModel.class);

        deviceId = getIntent().getStringExtra(EXTRA_DEVICE_ID);
        elementId = getIntent().getStringExtra(EXTRA_ELEMENT_ID);
        deviceName = getIntent().getStringExtra(EXTRA_DEVICE_NAME);
        deviceType = getIntent().getStringExtra(EXTRA_DEVICE_TYPE);

        if (deviceId == null) {
            Log.e(TAG, "No device id — finishing");
            finish();
            return;
        }

        // ✅ IMPORTANT: Save element ID immediately when activity opens
        if (elementId != null && !elementId.isEmpty()) {
            sharedViewModel.saveElementId(deviceId, elementId);

            // Also save in SharedPreferences for direct access
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().putString("element_id_" + deviceId, elementId).apply();

            Log.d(TAG, "✅ Saved element ID: " + elementId + " for device: " + deviceId);
        } else {
            Log.w(TAG, "⚠️ No element ID provided for device: " + deviceId);
        }

        setupToolbar();
        populateDeviceInfo();
        setupButtons();
    }

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
        Log.d(TAG, "Showing device: id=" + deviceId
                + " elementId=" + elementId
                + " type=" + deviceType);
    }

    private void setupButtons() {
        binding.btnConnect.setOnClickListener(v -> {
            Intent intent = new Intent(this, ScannerActivity.class);
            intent.putExtra(EXTRA_DEVICE_ID, deviceId);
            intent.putExtra(EXTRA_ELEMENT_ID, elementId);
            startActivity(intent);
        });

        binding.addToNetwork.setOnClickListener(v -> {
            Log.d(TAG, "Add to Network: " + deviceId + " type=" + deviceType);
            Toast.makeText(this,
                    "Starting provisioning for: " + deviceId,
                    Toast.LENGTH_SHORT).show();

            Intent intent = new Intent(this, ScannerActivity.class);
            intent.putExtra(Utils.EXTRA_DATA_PROVISIONING_SERVICE, true);
            intent.putExtra(Utils.EXTRA_SVG_DEVICE_ID, deviceId);
            intent.putExtra(EXTRA_AUTO_FILTER_DEVICE, deviceId);
            intent.putExtra(EXTRA_DEVICE_TYPE, deviceType);
            intent.putExtra(EXTRA_ELEMENT_ID, elementId); // Pass element ID
            Log.d(TAG, "Passing auto_filter_device=" + deviceId
                    + " type=" + deviceType
                    + " elementId=" + elementId);

            provisioner.launch(intent);
        });
    }

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

        final SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        // ✅ Save provisioned device
        Set<String> current = new HashSet<>(
                prefs.getStringSet(KEY_PROVISIONED_DEVICES, new HashSet<>()));
        current.add(svgDeviceId);
        prefs.edit()
                .putStringSet(KEY_PROVISIONED_DEVICES, new HashSet<>(current))
                .apply();

        // ✅ CRITICAL: Save element ID for this device (for both server and client)
        if (elementId != null && !elementId.isEmpty()) {
            // Save in SharedViewModel
            sharedViewModel.saveElementId(svgDeviceId, elementId);

            // Save in SharedPreferences
            prefs.edit()
                    .putString("element_id_" + svgDeviceId, elementId)
                    .apply();

            Log.d(TAG, "✅ Saved elementId=" + elementId + " for svgDeviceId=" + svgDeviceId);
        } else {
            Log.e(TAG, "❌ elementId is null/empty — cannot save for svgDeviceId=" + svgDeviceId);
        }

        // ✅ Handle based on device type
        if (DEVICE_TYPE_CLIENT.equals(deviceType)) {
            // Client provisioning
            Log.d(TAG, "📱 CLIENT provisioned: " + svgDeviceId + " with elementId=" + elementId);
            Toast.makeText(this,
                    "Client " + deviceId + " provisioned!\nElement ID: " + elementId,
                    Toast.LENGTH_LONG).show();

        } else if (DEVICE_TYPE_SERVER.equals(deviceType)) {
            // ✅ Server provisioning - Save as server device
            Log.d(TAG, "🖥️ SERVER provisioned: " + svgDeviceId + " with elementId=" + elementId);

            // Save server device ID
            sharedViewModel.setServerSvgDeviceId(svgDeviceId);
            prefs.edit().putString(KEY_SERVER_SVG_DEVICE_ID, svgDeviceId).apply();

            Toast.makeText(this,
                    "Server " + deviceId + " provisioned!\nElement ID: " + elementId,
                    Toast.LENGTH_LONG).show();
        } else {
            // Unknown type
            Log.d(TAG, "Unknown device type: " + deviceType);
            Toast.makeText(this,
                    deviceId + " provisioned successfully!",
                    Toast.LENGTH_LONG).show();
        }

        Log.d(TAG, "✅ Provisioning completed for: " + svgDeviceId);
        finish();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}