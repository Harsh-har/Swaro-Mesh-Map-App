package no.nordicsemi.android.swaromapmesh;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;
import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import java.util.List;
import dagger.hilt.android.AndroidEntryPoint;
import no.nordicsemi.android.swaromapmesh.ble.ScannerActivity;
import no.nordicsemi.android.swaromapmesh.databinding.ActivityDeviceDetailBinding;
import no.nordicsemi.android.swaromapmesh.swajaui.Svg_Operations.SvgDeviceMapper;
import no.nordicsemi.android.swaromapmesh.transport.ProvisionedMeshNode;
import no.nordicsemi.android.swaromapmesh.utils.Utils;
import no.nordicsemi.android.swaromapmesh.viewmodels.ClientServerElementStore;
import no.nordicsemi.android.swaromapmesh.viewmodels.SharedViewModel;

@AndroidEntryPoint
public class DeviceDetailActivity extends AppCompatActivity {

    private static final String TAG = "DeviceDetailActivity";

    // ── Intent extras (public — used by callers) ───────────────────────────
    public static final String EXTRA_PURE_DEVICE_NAME   = "pure_device_name";
    public static final String EXTRA_DEVICE_ID          = "device_id";
    public static final String EXTRA_ELEMENT_ID         = "element_id";
    public static final String EXTRA_RECEIVE_ID         = "receive_id";
    public static final String EXTRA_DEVICE_NAME        = "device_name";
    public static final String EXTRA_AREA_ID           = "area_id";
    public static final String EXTRA_AUTO_FILTER_DEVICE = "auto_filter_device";
    public static final String EXTRA_DEVICE_TYPE        = "device_type";
    public static final String DEVICE_TYPE_SERVER       = "server";
    public static final String DEVICE_TYPE_CLIENT       = "client";

    private ActivityDeviceDetailBinding binding;
    private SharedViewModel             sharedViewModel;

    private String deviceId;
    private String areaId;
    private String elementId;
    private String receiveId;
    private String deviceName;
    private String deviceType;
    private int    svgElementIdInt = -1;

    private String mBleFilterName = null;

    private final ActivityResultLauncher<Intent> provisioner =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    this::handleProvisioningResult);

    // =========================================================================
    // onCreate
    // =========================================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityDeviceDetailBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        sharedViewModel = new androidx.lifecycle.ViewModelProvider(this)
                .get(SharedViewModel.class);

        deviceId   = getIntent().getStringExtra(EXTRA_DEVICE_ID);
        deviceName = getIntent().getStringExtra(EXTRA_DEVICE_NAME);
        areaId     = getIntent().getStringExtra(EXTRA_AREA_ID);
        elementId  = getIntent().getStringExtra(EXTRA_ELEMENT_ID);
        receiveId  = getIntent().getStringExtra(EXTRA_RECEIVE_ID);
        deviceType = getIntent().getStringExtra(EXTRA_DEVICE_TYPE);

        // ── SVG element ID (numeric) ───
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

        // ── BLE filter name nikalo SVG ID / deviceName se ─────────────────
        mBleFilterName = resolveBleFilterName(deviceId, deviceName);
        Log.d(TAG, "onCreate: deviceId='" + deviceId
                + "' deviceName='" + deviceName
                + "' → mBleFilterName='" + mBleFilterName + "'");

        if (svgElementIdInt != -1) {
            ClientServerElementStore.saveServerSvgElementId(deviceId, svgElementIdInt);
            Log.d(TAG, "✅ onCreate: saved svgElementId=" + svgElementIdInt
                    + " for device=" + deviceId);
        }

        if (areaId != null && !areaId.isEmpty()) {
            ClientServerElementStore.saveServerAreaId(deviceId, areaId);
            Log.d(TAG, "✅ onCreate: saved areaId=" + areaId + " for device=" + deviceId);
        }

        setupToolbar();
        populateDeviceInfo();
        setupButtons();
    }

    // =========================================================================
    // BLE filter name resolver
    // =========================================================================

    private String resolveBleFilterName(String id, String name) {
        if (id != null && !id.isEmpty()) {
            String normalised = id.trim().replace(" ", "_").replace("-", "_");
            String filter = SvgDeviceMapper.getProductCode(normalised);
            if (filter != null) {
                Log.d(TAG, "Full Product Code from deviceId: '" + id + "' → '" + filter + "'");
                return filter;
            }
        }

        // Try deviceName
        if (name != null && !name.isEmpty()) {
            String normalised = name.trim().replace(" ", "_").replace("-", "_");
            String filter = SvgDeviceMapper.getProductCode(normalised);
            if (filter != null) {
                Log.d(TAG, "Full Product Code from deviceName: '" + name + "' → '" + filter + "'");
                return filter;
            }
        }

        // Fallback — deviceName as-is
        Log.w(TAG, "BLE filter: no mapping found for id='" + id
                + "' name='" + name + "' — using name as fallback");
        return name;
    }

    // =========================================================================
    // UI helpers
    // =========================================================================

    private String extractPureDeviceName(String fullDeviceId) {
        if (fullDeviceId == null || fullDeviceId.isEmpty()) return "";
        String name = fullDeviceId;
        if (name.contains(":")) {
            name = name.substring(name.lastIndexOf(":") + 1).trim();
        }
        name = name.replaceAll("\\s*\\d+$", "")
                .replaceAll("\\d+$", "")
                .replaceAll("\\s+", " ")
                .trim();
        return name.isEmpty() ? fullDeviceId : name;
    }

    private void setupToolbar() {
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(
                    (deviceName != null && !deviceName.isEmpty()) ? deviceName : deviceId);
        }
    }

    private void populateDeviceInfo() {
        String pureDeviceName = getIntent().getStringExtra(EXTRA_PURE_DEVICE_NAME);

        if (pureDeviceName != null && !pureDeviceName.isEmpty()) {
            deviceName = pureDeviceName;
            binding.tvDeviceIdValue.setText(pureDeviceName);
            if (getSupportActionBar() != null) getSupportActionBar().setTitle(pureDeviceName);
        } else if (deviceName != null && !deviceName.isEmpty()) {
            binding.tvDeviceIdValue.setText(deviceName);
            if (getSupportActionBar() != null) getSupportActionBar().setTitle(deviceName);
        } else {
            binding.tvDeviceIdValue.setText(deviceId);
        }

        binding.tvElementIdValue.setText(
                (elementId != null && !elementId.isEmpty()) ? elementId : "—");
        binding.tvreciveldValue.setText(
                (receiveId != null && !receiveId.isEmpty()) ? receiveId : "—");

        Log.d(TAG, "populateDeviceInfo: deviceName=" + deviceName
                + " originalId=" + deviceId
                + " elementId=" + elementId
                + " receiveId=" + receiveId
                + " svgElementIdInt=" + svgElementIdInt
                + " bleFilterName=" + mBleFilterName);
    }

    private void setupButtons() {
        binding.btnConnect.setOnClickListener(v -> {
            Intent intent = new Intent(this, ScannerActivity.class);
            intent.putExtra(EXTRA_DEVICE_ID,   deviceId);
            intent.putExtra(EXTRA_DEVICE_NAME, deviceName);
            intent.putExtra(EXTRA_ELEMENT_ID,  elementId);
            startActivity(intent);
        });

        binding.addToNetwork.setOnClickListener(v -> {
            Toast.makeText(this, "Starting provisioning for: " + deviceName,
                    Toast.LENGTH_SHORT).show();

            Intent intent = new Intent(this, ScannerActivity.class);
            intent.putExtra(Utils.EXTRA_DATA_PROVISIONING_SERVICE, true);
            intent.putExtra(Utils.EXTRA_SVG_DEVICE_ID,    deviceId);
            intent.putExtra(EXTRA_DEVICE_NAME,            deviceName);
            intent.putExtra(EXTRA_DEVICE_TYPE,            deviceType);
            intent.putExtra(EXTRA_ELEMENT_ID,             elementId);
            intent.putExtra(EXTRA_RECEIVE_ID,             receiveId);


            intent.putExtra(EXTRA_AUTO_FILTER_DEVICE, mBleFilterName);

            Log.d(TAG, "Launch provisioning:"
                    + " deviceId=" + deviceId
                    + " deviceName=" + deviceName
                    + " bleFilterName=" + mBleFilterName
                    + " receiveId=" + receiveId);

            provisioner.launch(intent);
        });
    }

    // =========================================================================
    // Provisioning result
    // =========================================================================

    private void handleProvisioningResult(final ActivityResult result) {
        Log.d(TAG, "handleProvisioningResult: code=" + result.getResultCode());

        if (result.getResultCode() != RESULT_OK || result.getData() == null) {
            Log.d(TAG, "Provisioning cancelled or no data");
            return;
        }

        final Intent data = result.getData();
        boolean provisioningSuccess =
                data.getBooleanExtra(Utils.PROVISIONING_COMPLETED, false);

        if (!provisioningSuccess) {
            Log.d(TAG, "Provisioning not completed");
            return;
        }

        // ── Resolve svgDeviceId ───────────────────────────────────────────
        String svgDeviceId = data.getStringExtra(Utils.EXTRA_SVG_DEVICE_ID);
        if (svgDeviceId == null || svgDeviceId.isEmpty()) {
            svgDeviceId = deviceId;
            Log.w(TAG, "svgDeviceId null — fallback to deviceId=" + deviceId);
        }
        final String finalSvgDeviceId = svgDeviceId;

        // ── Resolve receiveId ─────────────────────────────────────────────
        String receivedReceiveId = data.getStringExtra(EXTRA_RECEIVE_ID);
        if (receivedReceiveId != null && !receivedReceiveId.isEmpty()) {
            receiveId = receivedReceiveId;
            Log.d(TAG, "handleProvisioningResult: receiveId from result='" + receiveId + "'");
        } else {
            Log.d(TAG, "handleProvisioningResult: receiveId from class field='" + receiveId + "'");
        }

        if (svgElementIdInt == -1) {
            Log.e(TAG, "❌ svgElementIdInt = -1 — elementId was invalid: " + elementId);
        }

        // ── Get provisioned node ──────────────────────────────────────────
        ProvisionedMeshNode provisionedNode = sharedViewModel.getLastProvisionedNode();

        if (provisionedNode == null) {
            Log.e(TAG, "❌ provisionedNode is null — trying network fallback");
            List<ProvisionedMeshNode> nodes = sharedViewModel.getAllProvisionedNodes();
            if (nodes != null && !nodes.isEmpty()) {
                provisionedNode = nodes.get(nodes.size() - 1);
                Log.d(TAG, "✅ Fallback node: 0x"
                        + String.format("%04X", provisionedNode.getUnicastAddress())
                        + " name=" + provisionedNode.getNodeName());
            }
        }

        if (provisionedNode == null) {
            Log.e(TAG, "❌ provisionedNode still null — saving partial data");
            ClientServerElementStore.markProvisioned(finalSvgDeviceId);
            if (receiveId != null && !receiveId.isEmpty()) {
                ClientServerElementStore.saveReceiveIdOnly(finalSvgDeviceId, receiveId);
                Log.d(TAG, "⚠️ receiveId saved: " + receiveId);
            }
            if (svgElementIdInt != -1) {
                ClientServerElementStore.saveServerSvgElementId(finalSvgDeviceId, svgElementIdInt);
                Log.d(TAG, "⚠️ svgElementId saved: " + svgElementIdInt);
            }
            sharedViewModel.syncFromStore();
            showProvisionedToast(finalSvgDeviceId);
            finish();
            return;
        }

        Log.d(TAG, "handleProvisioningResult: saving — receiveId='" + receiveId + "'");

        ClientServerElementStore.saveDevice(
                finalSvgDeviceId,
                provisionedNode.getUnicastAddress(),
                svgElementIdInt,
                provisionedNode.getMacAddress(),
                receiveId
        );

        Log.d(TAG, "✅ saveDevice: svgId=" + finalSvgDeviceId
                + " unicast=0x" + String.format("%04X", provisionedNode.getUnicastAddress())
                + " svgElementId=" + svgElementIdInt
                + " mac=" + provisionedNode.getMacAddress()
                + " receiveId=" + receiveId);

        sharedViewModel.mapNodeToSvg(provisionedNode.getUuid(), finalSvgDeviceId);
        Log.d(TAG, "✅ mapNodeToSvg: uuid=" + provisionedNode.getUuid()
                + " → " + finalSvgDeviceId);

        sharedViewModel.syncFromStore();

        if (DEVICE_TYPE_SERVER.equals(deviceType)) {
            sharedViewModel.setServerSvgDeviceId(finalSvgDeviceId);
            Log.d(TAG, "🖥️ SERVER provisioned: " + finalSvgDeviceId);
        } else if (DEVICE_TYPE_CLIENT.equals(deviceType)) {
            Log.d(TAG, "📱 CLIENT provisioned: " + finalSvgDeviceId);
        } else {
            Log.d(TAG, "ℹ️ Unknown device type: " + deviceType);
        }

        showProvisionedToast(finalSvgDeviceId);
        Log.d(TAG, "✅ Provisioning fully completed for: " + finalSvgDeviceId);
        finish();
    }

    // =========================================================================
    // Toast helper
    // =========================================================================

    private void showProvisionedToast(String svgDeviceId) {
        String label = (deviceName != null && !deviceName.isEmpty())
                ? deviceName : svgDeviceId;
        String msg;
        if (DEVICE_TYPE_SERVER.equals(deviceType)) {
            msg = "Server " + label + " provisioned!\nElement ID: " + elementId;
        } else if (DEVICE_TYPE_CLIENT.equals(deviceType)) {
            msg = "Client " + label + " provisioned!\nElement ID: " + elementId;
        } else {
            msg = label + " provisioned successfully!";
        }
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    // =========================================================================
    // Navigation
    // =========================================================================

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}
