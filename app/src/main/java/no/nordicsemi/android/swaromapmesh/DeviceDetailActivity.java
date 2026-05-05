package no.nordicsemi.android.swaromapmesh;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Toast;
import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
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

    // ── Intent extras ──────────────────────────────────────────────────────
    public static final String EXTRA_PURE_DEVICE_NAME   = "pure_device_name";
    public static final String EXTRA_DEVICE_ID          = "device_id";
    public static final String EXTRA_ELEMENT_ID         = "element_id";
    public static final String EXTRA_NODE_ID            = "node_id";       // ← naya
    public static final String EXTRA_DEVICE_NAME        = "device_name";
    public static final String EXTRA_AUTO_FILTER_DEVICE = "auto_filter_device";
    public static final String EXTRA_DEVICE_TYPE        = "device_type";
    public static final String DEVICE_TYPE_SERVER       = "server";
    public static final String DEVICE_TYPE_CLIENT       = "client";

    private ActivityDeviceDetailBinding binding;
    private SharedViewModel             sharedViewModel;

    private String deviceId;
    private String elementId;
    private String nodeId;          // ← naya: sirf 2-ID nodes ke liye
    private String deviceName;
    private String deviceType;
    private int    svgElementIdInt = -1;

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
        elementId  = getIntent().getStringExtra(EXTRA_ELEMENT_ID);
        nodeId     = getIntent().getStringExtra(EXTRA_NODE_ID);     // ← naya
        deviceType = getIntent().getStringExtra(EXTRA_DEVICE_TYPE);

        // svgElementIdInt: nodeId prefer karo (2-ID case), fallback to elementId (1-ID case)
        String idToParse = (nodeId != null && !nodeId.isEmpty()) ? nodeId : elementId;
        if (idToParse != null && !idToParse.isEmpty()) {
            try {
                svgElementIdInt = Integer.parseInt(idToParse.trim());
            } catch (NumberFormatException e) {
                Log.e(TAG, "Not numeric (expected for group IDs like PDRD): " + idToParse);
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

        if (svgElementIdInt != -1) {
            ClientServerElementStore.saveServerSvgElementId(deviceId, svgElementIdInt);
            Log.d(TAG, "✅ onCreate: saved svgElementId=" + svgElementIdInt
                    + " for device=" + deviceId);
        }

        setupToolbar();
        populateDeviceInfo();
        setupButtons();
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

        // ── Device name ───────────────────────────────────────────────────
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

        // ── Element ID ────────────────────────────────────────────────────
        binding.tvElementIdValue.setText(
                (elementId != null && !elementId.isEmpty()) ? elementId : "—");

        // ── Node ID row: sirf tab dikhao jab 2 IDs hain ──────────────────
        boolean hasTwoIds = (nodeId != null && !nodeId.isEmpty());

        if (hasTwoIds) {
            binding.rowNodeId.setVisibility(View.VISIBLE);
            binding.tvNodeIdValue.setText(nodeId);
        } else {
            binding.rowNodeId.setVisibility(View.GONE);
        }

        Log.d(TAG, "populateDeviceInfo:"
                + " deviceName=" + deviceName
                + " elementId=" + elementId
                + " nodeId=" + nodeId
                + " hasTwoIds=" + hasTwoIds
                + " svgElementIdInt=" + svgElementIdInt);
    }

    private void setupButtons() {
        binding.btnConnect.setOnClickListener(v -> {
            Intent intent = new Intent(this, ScannerActivity.class);
            intent.putExtra(EXTRA_DEVICE_ID,   deviceId);
            intent.putExtra(EXTRA_DEVICE_NAME, deviceName);
            intent.putExtra(EXTRA_ELEMENT_ID,  elementId);
            intent.putExtra(EXTRA_NODE_ID,     nodeId);     // ← pass karo
            startActivity(intent);
        });

        binding.addToNetwork.setOnClickListener(v -> {
            Toast.makeText(this, "Starting provisioning for: " + deviceName,
                    Toast.LENGTH_SHORT).show();

            Intent intent = new Intent(this, ScannerActivity.class);
            intent.putExtra(Utils.EXTRA_DATA_PROVISIONING_SERVICE, true);
            intent.putExtra(Utils.EXTRA_SVG_DEVICE_ID,    deviceId);
            intent.putExtra(EXTRA_AUTO_FILTER_DEVICE,     deviceName);
            intent.putExtra(EXTRA_DEVICE_NAME,            deviceName);
            intent.putExtra(EXTRA_DEVICE_TYPE,            deviceType);
            intent.putExtra(EXTRA_ELEMENT_ID,             elementId);
            intent.putExtra(EXTRA_NODE_ID,                nodeId);   // ← pass karo

            Log.d(TAG, "Launch provisioning: deviceId=" + deviceId
                    + " deviceName=" + deviceName
                    + " elementId=" + elementId
                    + " nodeId=" + nodeId);
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

        if (svgElementIdInt == -1) {
            Log.e(TAG, "❌ svgElementIdInt = -1 — elementId was invalid: " + elementId);
        }

        // ── Get provisioned node ──────────────────────────────────────────
        ProvisionedMeshNode provisionedNode = sharedViewModel.getLastProvisionedNode();

        if (provisionedNode == null) {
            Log.e(TAG, "❌ provisionedNode is null — cannot save addresses for: "
                    + finalSvgDeviceId);
            ClientServerElementStore.markProvisioned(finalSvgDeviceId);
            sharedViewModel.syncFromStore();
            showProvisionedToast(finalSvgDeviceId);
            finish();
            return;
        }

        ClientServerElementStore.saveDevice(
                finalSvgDeviceId,
                provisionedNode.getUnicastAddress(),
                svgElementIdInt,
                provisionedNode.getMacAddress(),
                nodeId
        );

        Log.d(TAG, "✅ saveDevice: svgId=" + finalSvgDeviceId
                + " unicast=0x" + String.format("%04X", provisionedNode.getUnicastAddress())
                + " svgElementId=" + svgElementIdInt
                + " nodeId=" + nodeId
                + " mac=" + provisionedNode.getMacAddress());

        sharedViewModel.mapNodeToSvg(provisionedNode.getUuid(), finalSvgDeviceId);
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

        // 2 IDs hain to dono dikhao, warna sirf ek
        String elementDisplay = (nodeId != null && !nodeId.isEmpty())
                ? elementId + " | Node: " + nodeId
                : (elementId != null ? elementId : "—");

        String msg;
        if (DEVICE_TYPE_SERVER.equals(deviceType)) {
            msg = "Server " + label + " provisioned!\nElement ID: " + elementDisplay;
        } else if (DEVICE_TYPE_CLIENT.equals(deviceType)) {
            msg = "Client " + label + " provisioned!\nElement ID: " + elementDisplay;
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