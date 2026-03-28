package no.nordicsemi.android.swaromapmesh;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.textview.MaterialTextView;
import com.google.android.material.button.MaterialButton;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import dagger.hilt.android.AndroidEntryPoint;

import no.nordicsemi.android.swaromapmesh.ble.MeshCommandManager;
import no.nordicsemi.android.swaromapmesh.transport.ProvisionedMeshNode;
import no.nordicsemi.android.swaromapmesh.viewmodels.SharedViewModel;

@AndroidEntryPoint
public class TestProvisionActivity extends AppCompatActivity {

    private static final String TAG                     = "TestProvisionActivity";
    private static final String PREFS_NAME              = "mesh_prefs";
    private static final String KEY_PROVISIONED_DEVICES = "provisioned_devices";

    private String deviceId;
    private String elementId;

    private MaterialTextView tvDeviceId;
    private MaterialTextView tvElementId;
    private MaterialTextView tvStatus;
    private MaterialTextView tvMacAddress;
    private MaterialTextView tvUnicastAddress;
    private MaterialButton   btnTest;

    private SharedViewModel     mViewModel;
    private final AtomicInteger tidCounter = new AtomicInteger(0);

    // ── Dynamic unicast address (loaded from matched node) ────────
    private int mUnicastAddress = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_test_provision);

        mViewModel = new ViewModelProvider(this).get(SharedViewModel.class);

        deviceId  = getIntent().getStringExtra(DeviceDetailActivity.EXTRA_DEVICE_ID);
        elementId = getIntent().getStringExtra(DeviceDetailActivity.EXTRA_ELEMENT_ID);

        // ── View bindings ─────────────────────────────────────────
        tvDeviceId       = findViewById(R.id.tv_device_id);
        tvElementId      = findViewById(R.id.tv_element_id);
        tvStatus         = findViewById(R.id.tv_status);
        tvMacAddress     = findViewById(R.id.tv_mac_address);
        tvUnicastAddress = findViewById(R.id.tv_unicast_address);
        btnTest          = findViewById(R.id.btn_test);

        // ── Basic fields ──────────────────────────────────────────
        tvDeviceId.setText(deviceId   != null ? deviceId  : "N/A");
        tvElementId.setText(elementId != null ? elementId : "N/A");

        updateStatus();

        // ── MAC + Unicast: observe nodes LiveData ─────────────────
        mViewModel.getNodes().observe(this, nodes -> {
            if (nodes == null || nodes.isEmpty()) {
                Log.w(TAG, "Nodes list empty");
                setAddressFields("N/A", "N/A");
                return;
            }
            loadAddressesFromNodes(nodes);
        });

        // ── Test button ───────────────────────────────────────────
        btnTest.setOnClickListener(v -> {
            if (!isProvisioned(deviceId)) {
                Toast.makeText(this,
                        "Device not provisioned!", Toast.LENGTH_SHORT).show();
                return;
            }
            if (mUnicastAddress == -1) {
                Toast.makeText(this,
                        "Unicast address not loaded yet!", Toast.LENGTH_SHORT).show();
                return;
            }

            MeshCommandManager.startBlink(mViewModel, tidCounter, mUnicastAddress);
            Toast.makeText(this, "Blinking...", Toast.LENGTH_SHORT).show();
            btnTest.setEnabled(false);

            int totalBlinkMs =
                    (MeshCommandManager.BLINK_ON_MS + MeshCommandManager.BLINK_OFF_MS)
                            * MeshCommandManager.BLINK_COUNT + 100;

            btnTest.postDelayed(() -> btnTest.setEnabled(true), totalBlinkMs);
        });
    }

    // ==================== ADDRESS LOADING ====================

    private void loadAddressesFromNodes(List<ProvisionedMeshNode> nodes) {
        if (deviceId == null) {
            setAddressFields("N/A", "N/A");
            return;
        }

        ProvisionedMeshNode matched = null;

        // ── Step 1: Name se match karo ────────────────────────────
        for (ProvisionedMeshNode node : nodes) {
            if (deviceId.equalsIgnoreCase(node.getNodeName())) {
                matched = node;
                Log.d(TAG, "Node matched by name: " + node.getNodeName());
                break;
            }
        }

        // ── Step 2: Single node fallback ──────────────────────────
        if (matched == null && nodes.size() == 1) {
            matched = nodes.get(0);
            Log.d(TAG, "Single node fallback: " + matched.getNodeName());
        }

        if (matched != null) {
            // MAC
            String mac = matched.getMacAddress();
            if (mac == null || mac.isEmpty()) mac = "N/A";

            // Unicast — store dynamically
            int unicastInt = matched.getUnicastAddress();
            mUnicastAddress = unicastInt;  // ✅ store for use in blink
            String unicast = String.format("0x%04X", unicastInt);

            Log.d(TAG, "MAC=" + mac + "  Unicast=" + unicast
                    + "  mUnicastAddress=" + mUnicastAddress);

            setAddressFields(mac, unicast);

        } else {
            Log.w(TAG, "No node matched for deviceId=" + deviceId);
            mUnicastAddress = -1;
            setAddressFields("N/A", "N/A");
        }
    }

    private void setAddressFields(String mac, String unicast) {
        if (tvMacAddress     != null) tvMacAddress.setText(mac);
        if (tvUnicastAddress != null) tvUnicastAddress.setText(unicast);
    }

    // ==================== STATUS ====================

    private void updateStatus() {
        if (isProvisioned(deviceId)) {
            tvStatus.setText("Provisioned");
            tvStatus.setTextColor(
                    getResources().getColor(android.R.color.holo_green_light));
        } else {
            tvStatus.setText("Not Provisioned");
            tvStatus.setTextColor(
                    getResources().getColor(android.R.color.holo_orange_light));
        }
    }

    private boolean isProvisioned(String id) {
        SharedPreferences prefs =
                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        Set<String> devices =
                prefs.getStringSet(KEY_PROVISIONED_DEVICES, new HashSet<>());
        return devices.contains(id);
    }

    // ==================== LIFECYCLE ====================

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Pass mUnicastAddress — if -1, stopBlink will still safely turn off
        if (mUnicastAddress != -1) {
            MeshCommandManager.stopBlink(mViewModel, tidCounter, mUnicastAddress);
        }
    }
}