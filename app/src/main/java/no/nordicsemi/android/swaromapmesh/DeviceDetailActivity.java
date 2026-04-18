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
import no.nordicsemi.android.swaromapmesh.utils.Utils;
import no.nordicsemi.android.swaromapmesh.viewmodels.SharedViewModel;

@AndroidEntryPoint
public class DeviceDetailActivity extends AppCompatActivity {

    private static final String TAG = "DeviceDetailActivity";
    public static final String EXTRA_PURE_DEVICE_NAME = "pure_device_name";

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

        // ✅ Original ID for backend (relation mapping)
        deviceId = getIntent().getStringExtra(EXTRA_DEVICE_ID);

        // ✅ Display name for user
        deviceName = getIntent().getStringExtra(EXTRA_DEVICE_NAME);
        elementId = getIntent().getStringExtra(EXTRA_ELEMENT_ID);
        deviceType = getIntent().getStringExtra(EXTRA_DEVICE_TYPE);

        if (deviceId == null) {
            Log.e(TAG, "No device id — finishing");
            finish();
            return;
        }

        // ✅ If deviceName is null, extract from deviceId for display only
        if (deviceName == null || deviceName.isEmpty()) {
            deviceName = extractPureDeviceName(deviceId);
        }

        // ✅ Save element ID with ORIGINAL deviceId (not display name)
        if (elementId != null && !elementId.isEmpty()) {
            sharedViewModel.saveElementId(deviceId, elementId);  // Original ID

            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().putString("element_id_" + deviceId, elementId).apply();  // Original ID

            Log.d(TAG, "✅ Saved element ID: " + elementId + " for device: " + deviceId);
        }

        setupToolbar();
        populateDeviceInfo();
        setupButtons();
    }

    // ✅ Helper method for display name only
    private String extractPureDeviceName(String fullDeviceId) {
        if (fullDeviceId == null || fullDeviceId.isEmpty()) return "";

        String name = fullDeviceId;
        if (name.contains(":")) {
            name = name.substring(name.lastIndexOf(":") + 1).trim();
        }
        name = name.replaceAll("\\s*\\d+$", "");
        name = name.replaceAll("\\d+$", "");
        name = name.replaceAll("\\s+", " ").trim();

        return name.isEmpty() ? fullDeviceId : name;
    }

    private void setupToolbar() {
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            String title = (deviceName != null && !deviceName.isEmpty()) ? deviceName : deviceId;
            getSupportActionBar().setTitle(title);
        }
    }

    private void populateDeviceInfo() {
        // Get pure device name from intent
        String pureDeviceName = getIntent().getStringExtra(EXTRA_PURE_DEVICE_NAME);

        if (pureDeviceName != null && !pureDeviceName.isEmpty()) {
            // ✅ CRITICAL: Set deviceName variable for BLE scanning
            deviceName = pureDeviceName;
            binding.tvDeviceIdValue.setText(pureDeviceName);

            // Update toolbar title
            if (getSupportActionBar() != null) {
                getSupportActionBar().setTitle(pureDeviceName);
            }
        } else if (deviceName != null && !deviceName.isEmpty()) {
            binding.tvDeviceIdValue.setText(deviceName);
            if (getSupportActionBar() != null) {
                getSupportActionBar().setTitle(deviceName);
            }
        } else {
            binding.tvDeviceIdValue.setText(deviceId);
        }

        binding.tvElementIdValue.setText(
                (elementId != null && !elementId.isEmpty()) ? elementId : "—");

        Log.d(TAG, "Showing device: deviceName=" + deviceName
                + " pureName=" + pureDeviceName
                + " originalId=" + deviceId
                + " elementId=" + elementId);
    }
    private void setupButtons() {
        binding.btnConnect.setOnClickListener(v -> {
            Intent intent = new Intent(this, ScannerActivity.class);
            intent.putExtra(EXTRA_DEVICE_ID, deviceId);           // Original ID
            intent.putExtra(EXTRA_DEVICE_NAME, deviceName);       // Display name
            intent.putExtra(EXTRA_ELEMENT_ID, elementId);
            startActivity(intent);
        });

        binding.addToNetwork.setOnClickListener(v -> {
            Toast.makeText(this, "Starting provisioning for: " + deviceName, Toast.LENGTH_SHORT).show();

            Intent intent = new Intent(this, ScannerActivity.class);
            intent.putExtra(Utils.EXTRA_DATA_PROVISIONING_SERVICE, true);
            intent.putExtra(Utils.EXTRA_SVG_DEVICE_ID, deviceId);     // Original ID
            intent.putExtra(EXTRA_AUTO_FILTER_DEVICE, deviceName);     // ✅ CHANGE: Display name for auto-click
            intent.putExtra(EXTRA_DEVICE_NAME, deviceName);            // Display name
            intent.putExtra(EXTRA_DEVICE_TYPE, deviceType);
            intent.putExtra(EXTRA_ELEMENT_ID, elementId);

            Log.d(TAG, "Provisioning: Original ID=" + deviceId + " Display Name=" + deviceName);
            Log.d(TAG, "EXTRA_AUTO_FILTER_DEVICE=" + deviceName);
            Log.d(TAG, "EXTRA_DEVICE_NAME=" + deviceName);

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

        // ✅ Save provisioned device with ORIGINAL full SVG id
        Set<String> current = new HashSet<>(
                prefs.getStringSet(KEY_PROVISIONED_DEVICES, new HashSet<>()));
        current.add(svgDeviceId);  // "PDRI:Relay Node1" — exact match with SVG group id
        prefs.edit()
                .putStringSet(KEY_PROVISIONED_DEVICES, new HashSet<>(current))
                .apply();

        // ✅ Notify ViewModel so NetworkFragment observer fires
        sharedViewModel.markDeviceProvisioned(svgDeviceId);
        Log.d(TAG, "✅ markDeviceProvisioned: " + svgDeviceId);

        // ✅ Save element ID
        if (elementId != null && !elementId.isEmpty()) {
            sharedViewModel.saveElementId(svgDeviceId, elementId);
            prefs.edit()
                    .putString("element_id_" + svgDeviceId, elementId)
                    .apply();
            Log.d(TAG, "✅ Saved elementId=" + elementId + " for svgDeviceId=" + svgDeviceId);
        } else {
            Log.e(TAG, "❌ elementId is null/empty — cannot save for svgDeviceId=" + svgDeviceId);
        }

        // Handle device type
        if (DEVICE_TYPE_CLIENT.equals(deviceType)) {
            Log.d(TAG, "📱 CLIENT provisioned: " + svgDeviceId);
            Toast.makeText(this,
                    "Client " + deviceName + " provisioned!\nElement ID: " + elementId,
                    Toast.LENGTH_LONG).show();
        } else if (DEVICE_TYPE_SERVER.equals(deviceType)) {
            Log.d(TAG, "🖥️ SERVER provisioned: " + svgDeviceId);
            sharedViewModel.setServerSvgDeviceId(svgDeviceId);
            prefs.edit().putString(KEY_SERVER_SVG_DEVICE_ID, svgDeviceId).apply();
            Toast.makeText(this,
                    "Server " + deviceName + " provisioned!\nElement ID: " + elementId,
                    Toast.LENGTH_LONG).show();
        } else {
            Log.d(TAG, "Unknown device type: " + deviceType);
            Toast.makeText(this,
                    deviceName + " provisioned successfully!",
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