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
import no.nordicsemi.android.swaromapmesh.transport.ProvisionedMeshNode;
import no.nordicsemi.android.swaromapmesh.utils.Utils;
import no.nordicsemi.android.swaromapmesh.viewmodels.ClientServerElementStore;
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
    private int svgElementIdInt = -1;

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
        deviceName = getIntent().getStringExtra(EXTRA_DEVICE_NAME);
        elementId = getIntent().getStringExtra(EXTRA_ELEMENT_ID);
        deviceType = getIntent().getStringExtra(EXTRA_DEVICE_TYPE);

        if (elementId != null && !elementId.isEmpty()) {
            try {
                svgElementIdInt = Integer.parseInt(elementId.trim());
            } catch (NumberFormatException e) {
                Log.e(TAG, "Invalid element ID format: " + elementId);
            }
        }

        if (deviceId == null) {
            Log.e(TAG, "No device id — finishing");
            finish();
            return;
        }

        if (deviceName == null || deviceName.isEmpty()) {
            deviceName = extractPureDeviceName(deviceId);
        }

        if (elementId != null && !elementId.isEmpty()) {
            sharedViewModel.saveElementId(deviceId, elementId);

            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().putString("element_id_" + deviceId, elementId).apply();

            Log.d(TAG, "✅ Saved element ID: " + elementId + " for device: " + deviceId);
        }

        setupToolbar();
        populateDeviceInfo();
        setupButtons();
    }

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
        String pureDeviceName = getIntent().getStringExtra(EXTRA_PURE_DEVICE_NAME);

        if (pureDeviceName != null && !pureDeviceName.isEmpty()) {
            deviceName = pureDeviceName;
            binding.tvDeviceIdValue.setText(pureDeviceName);
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
                + " elementId=" + elementId
                + " svgElementIdInt=" + svgElementIdInt);
    }

    private void setupButtons() {
        binding.btnConnect.setOnClickListener(v -> {
            Intent intent = new Intent(this, ScannerActivity.class);
            intent.putExtra(EXTRA_DEVICE_ID, deviceId);
            intent.putExtra(EXTRA_DEVICE_NAME, deviceName);
            intent.putExtra(EXTRA_ELEMENT_ID, elementId);
            startActivity(intent);
        });

        binding.addToNetwork.setOnClickListener(v -> {
            Toast.makeText(this, "Starting provisioning for: " + deviceName, Toast.LENGTH_SHORT).show();

            Intent intent = new Intent(this, ScannerActivity.class);
            intent.putExtra(Utils.EXTRA_DATA_PROVISIONING_SERVICE, true);
            intent.putExtra(Utils.EXTRA_SVG_DEVICE_ID, deviceId);
            intent.putExtra(EXTRA_AUTO_FILTER_DEVICE, deviceName);
            intent.putExtra(EXTRA_DEVICE_NAME, deviceName);
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

        final String finalSvgDeviceId = svgDeviceId;
        final SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        // ✅ 1. Save provisioned device set
        Set<String> current = new HashSet<>(
                prefs.getStringSet(KEY_PROVISIONED_DEVICES, new HashSet<>()));
        current.add(finalSvgDeviceId);
        prefs.edit()
                .putStringSet(KEY_PROVISIONED_DEVICES, new HashSet<>(current))
                .apply();

        // ✅ 2. Mark provisioned in ViewModel
        sharedViewModel.markDeviceProvisioned(finalSvgDeviceId);
        Log.d(TAG, "✅ markDeviceProvisioned: " + finalSvgDeviceId);

        // ✅ 3. Save element ID
        if (elementId != null && !elementId.isEmpty()) {
            sharedViewModel.saveElementId(finalSvgDeviceId, elementId);
            prefs.edit()
                    .putString("element_id_" + finalSvgDeviceId, elementId)
                    .apply();
            Log.d(TAG, "✅ Saved elementId=" + elementId + " for svgDeviceId=" + finalSvgDeviceId);
        } else {
            Log.e(TAG, "❌ elementId is null/empty — cannot save for svgDeviceId=" + finalSvgDeviceId);
        }

        // ✅ 4. Node mapping — UUID, Unicast, MAC, SVG Element ID
        ProvisionedMeshNode provisionedNode = sharedViewModel.getLastProvisionedNode();

        if (provisionedNode != null) {
            String storeKey = finalSvgDeviceId.trim().toLowerCase();
            // e.g. "casting:relay node1"

            // 4a. UUID → SVG mapping
            sharedViewModel.mapNodeToSvg(provisionedNode.getUuid(), finalSvgDeviceId);
            Log.d(TAG, "✅ mapNodeToSvg: uuid=" + provisionedNode.getUuid()
                    + " → svgDeviceId=" + finalSvgDeviceId);

            // 4b. Unicast address
            ClientServerElementStore.saveServerUnicastAddress(
                    storeKey, provisionedNode.getUnicastAddress());
            Log.d(TAG, "✅ Saved unicast=" + provisionedNode.getUnicastAddress()
                    + " for storeKey=" + storeKey);

            // 4c. MAC address
            String mac = provisionedNode.getMacAddress();
            if (mac != null && !mac.isEmpty()) {
                ClientServerElementStore.saveServerMacAddress(storeKey, mac);
                Log.d(TAG, "✅ Saved MAC=" + mac + " for storeKey=" + storeKey);
            } else {
                Log.w(TAG, "⚠️ MAC address null/empty for storeKey=" + storeKey);
            }

            // 4d. SVG element ID
            int svgElementId = sharedViewModel.getSvgElementIdAsInt(finalSvgDeviceId);
            if (svgElementId != -1) {
                ClientServerElementStore.saveServerSvgElementId(storeKey, svgElementId);
                Log.d(TAG, "✅ Saved svgElementId=" + svgElementId
                        + " for storeKey=" + storeKey);
            } else {
                Log.w(TAG, "⚠️ svgElementId not found for svgDeviceId=" + finalSvgDeviceId);
            }

        } else {
            Log.e(TAG, "❌ provisionedNode is null — node mapping skipped for: " + finalSvgDeviceId);
        }

        // ✅ 5. Handle device type specific logic
        if (DEVICE_TYPE_CLIENT.equals(deviceType)) {
            Log.d(TAG, "📱 CLIENT provisioned: " + finalSvgDeviceId);
            prefs.edit().putString("client_svg_element_" + finalSvgDeviceId, elementId).apply();
            Toast.makeText(this,
                    "Client " + deviceName + " provisioned!\nElement ID: " + elementId,
                    Toast.LENGTH_LONG).show();

        } else if (DEVICE_TYPE_SERVER.equals(deviceType)) {
            Log.d(TAG, "🖥️ SERVER provisioned: " + finalSvgDeviceId);
            sharedViewModel.setServerSvgDeviceId(finalSvgDeviceId);
            prefs.edit().putString(KEY_SERVER_SVG_DEVICE_ID, finalSvgDeviceId).apply();
            Toast.makeText(this,
                    "Server " + deviceName + " provisioned!\nElement ID: " + elementId,
                    Toast.LENGTH_LONG).show();

        } else {
            Log.d(TAG, "Unknown device type: " + deviceType);
            Toast.makeText(this,
                    deviceName + " provisioned successfully!",
                    Toast.LENGTH_LONG).show();
        }

        Log.d(TAG, "✅ Provisioning fully completed for: " + finalSvgDeviceId);
        finish();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}