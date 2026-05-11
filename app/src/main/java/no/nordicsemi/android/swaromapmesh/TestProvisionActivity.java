package no.nordicsemi.android.swaromapmesh;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;
import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textview.MaterialTextView;
import com.google.android.material.button.MaterialButton;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import dagger.hilt.android.AndroidEntryPoint;
import no.nordicsemi.android.swaromapmesh.ble.MeshCommandManager;
import no.nordicsemi.android.swaromapmesh.transport.GenericLightSet;
import no.nordicsemi.android.swaromapmesh.transport.ProvisionedMeshNode;
import no.nordicsemi.android.swaromapmesh.viewmodels.ClientServerElementStore;
import no.nordicsemi.android.swaromapmesh.viewmodels.SharedViewModel;

@AndroidEntryPoint
public class TestProvisionActivity extends AppCompatActivity {

    private static final String TAG               = "TestProvisionActivity";
    private static final String PREFS_NAME        = "mesh_prefs";
    private static final String PREFS_DEVICE_ADDR = "device_address_prefs";

    // ── Long Command Fixed Values ─────────────────────────────────────────
    private static final int LONG_CMD_LENGTH   = 1;
    private static final int LONG_CMD_COMMAND  = 3;
    private static final int LONG_DATA_1       = 11;
    private static final int LONG_DATA_2       = 1;
    private static final int LONG_DATA_DEFAULT = 0;

    private static final int MAX_TID = 255;

    // ── Instance variables ────────────────────────────────────────────────
    private String deviceId;
    private String elementId;
    private String svgName;
    private String topicPrefix;
    private String areaName;
    private String relationDeviceName;

    private MaterialTextView  tvDeviceId;
    private MaterialTextView  tvElementId;
    private MaterialTextView  tvReceiveId;
    private MaterialTextView  tvStatus;
    private MaterialTextView  tvMacAddress;
    private MaterialTextView  tvUnicastAddress;
    private MaterialTextView  tvMqttTopic;
    private MaterialTextView  tvRelationDeviceId;
    private MaterialButton    btnTestBle;
    private MaterialButton    btnTestMqtt;
    private MaterialButton    btnSaveAddress;
    private TextInputEditText etAddress;

    private SharedPreferences devicePrefs;

    private SharedViewModel       mViewModel;
    private final AtomicInteger   tidCounter             = new AtomicInteger(0);
    private final AtomicInteger   genericLightTidCounter = new AtomicInteger(0);
    private int                   mUnicastAddress        = -1;
    private MqttClient            mqttClient;
    private final ExecutorService mqttExecutor           = Executors.newSingleThreadExecutor();

    // =========================================================================
    // Lifecycle
    // =========================================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_test_provision);

        mViewModel = new ViewModelProvider(this).get(SharedViewModel.class);

        // ── Intent extras ─────────────────────────────────────────────────
        deviceId           = getIntent().getStringExtra(DeviceDetailActivity.EXTRA_DEVICE_ID);
        elementId          = getIntent().getStringExtra(DeviceDetailActivity.EXTRA_ELEMENT_ID);
        svgName            = getIntent().getStringExtra("svg_name");
        topicPrefix        = getIntent().getStringExtra("topic_prefix");
        areaName           = getIntent().getStringExtra("area_name");
        relationDeviceName = getIntent().getStringExtra("EXTRA_RELATION_DEVICE_NAME");

        // ── View bindings ─────────────────────────────────────────────────
        tvDeviceId         = findViewById(R.id.tv_device_id);
        tvElementId        = findViewById(R.id.tv_element_id);
        tvReceiveId        = findViewById(R.id.tv_recive_id);
        tvStatus           = findViewById(R.id.tv_status);
        tvMacAddress       = findViewById(R.id.tv_mac_address);
        tvUnicastAddress   = findViewById(R.id.tv_unicast_address);
        tvMqttTopic        = findViewById(R.id.tv_mqtttopic);
        tvRelationDeviceId = findViewById(R.id.tv_relation_device_id);
        btnTestBle         = findViewById(R.id.btn_testble);
        btnTestMqtt        = findViewById(R.id.btn_testmqqt);
        btnSaveAddress     = findViewById(R.id.btn_save_address);
        etAddress          = findViewById(R.id.et_address);

        // ── Static text ───────────────────────────────────────────────────
        tvDeviceId.setText(deviceId != null ? deviceId : "N/A");
        tvElementId.setText(elementId != null ? elementId : "N/A");
        tvRelationDeviceId.setText(relationDeviceName != null ? relationDeviceName : "N/A");

        // ── Receive ID ────────────────────────────────────────────────────
        String intentReceiveId = getIntent().getStringExtra(DeviceDetailActivity.EXTRA_RECEIVE_ID);
        String normalizedId    = deviceId != null ? deviceId.trim().toLowerCase() : null;
        String storeReceiveId  = ClientServerElementStore.getReceiveId(normalizedId);

        String receiveId = (intentReceiveId != null && !intentReceiveId.isEmpty())
                ? intentReceiveId : storeReceiveId;

        if (tvReceiveId != null) {
            tvReceiveId.setText(receiveId != null && !receiveId.isEmpty() ? receiveId : "N/A");
        }
        if (intentReceiveId != null && !intentReceiveId.isEmpty() && storeReceiveId == null) {
            ClientServerElementStore.saveReceiveIdOnly(normalizedId, intentReceiveId);
        }

        // ── SharedPreferences init ────────────────────────────────────────
        devicePrefs = getSharedPreferences(PREFS_DEVICE_ADDR, MODE_PRIVATE);

        // ── Prefill saved address ─────────────────────────────────────────
        String savedAddress = devicePrefs.getString("address_" + deviceId, "");
        if (!savedAddress.isEmpty()) {
            etAddress.setText(savedAddress);
        }

        updateMqttTopicDisplay(relationDeviceName);
        updateStatus();
       // ── Show Address input & Save button only for LC Node devices ─────────
        android.view.View layoutAddress  = findViewById(R.id.layout_address);
        boolean isLcNode = isLcNodeDevice(deviceId);
        layoutAddress.setVisibility(isLcNode
                ? android.view.View.VISIBLE : android.view.View.GONE);
        btnSaveAddress.setVisibility(isLcNode
                ? android.view.View.VISIBLE : android.view.View.GONE);
        // ── Node observer ─────────────────────────────────────────────────
        mViewModel.getNodes().observe(this, nodes -> {
            if (nodes == null || nodes.isEmpty()) {
                setAddressFields("N/A", "N/A");
                return;
            }
            loadAddressesFromNodes(nodes);
        });

        // ── Save Address button ───────────────────────────────────────────
        btnSaveAddress.setOnClickListener(v -> {
            String addrStr = etAddress.getText() != null
                    ? etAddress.getText().toString().trim() : "";

            if (addrStr.isEmpty()) {
                Toast.makeText(this, "Assign Address!", Toast.LENGTH_SHORT).show();
                etAddress.requestFocus();
                return;
            }

            int userAddress;
            try {
                userAddress = Integer.parseInt(addrStr);
                if (userAddress < 1 || userAddress > 8) {
                    Toast.makeText(this, "Address must be 1–8", Toast.LENGTH_SHORT).show();
                    return;
                }
            } catch (NumberFormatException e) {
                Toast.makeText(this, "Invalid address!", Toast.LENGTH_SHORT).show();
                return;
            }

            if (!isProvisioned(deviceId)) {
                Toast.makeText(this, "Device not provisioned!", Toast.LENGTH_SHORT).show();
                return;
            }

            if (mUnicastAddress == -1) {
                Toast.makeText(this, "Unicast address not loaded yet!", Toast.LENGTH_SHORT).show();
                return;
            }

            // ── Agar pehle se address saved hai toh confirm dialog ────
            String existingAddr = devicePrefs.getString("address_" + deviceId, "");
            if (!existingAddr.isEmpty() && !existingAddr.equals(addrStr)) {
                final int finalUserAddress = userAddress;
                new androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("Address Change")
                        .setMessage("Address already set to " + existingAddr
                                + ".\nChange to " + addrStr + "?")
                        .setPositiveButton("Yes, Change", (dialog, which) -> {
                            saveAndSendAddress(addrStr, finalUserAddress);
                        })
                        .setNegativeButton("Cancel", (dialog, which) -> dialog.dismiss())
                        .show();
                return;
            }
            // ─────────────────────────────────────────────────────────

            saveAndSendAddress(addrStr, userAddress);
        });

        // ── BLE Test button ───────────────────────────────────────────────────
        btnTestBle.setOnClickListener(v -> {
            if (!isProvisioned(deviceId)) {
                Toast.makeText(this, "Device not provisioned!", Toast.LENGTH_SHORT).show();
                return;
            }
            if (mUnicastAddress == -1) {
                Toast.makeText(this, "Unicast address not loaded yet!", Toast.LENGTH_SHORT).show();
                return;
            }

            // ── LC Node: address-based command ────────────────────────────
            if (isLcNodeDevice(deviceId)) {
                String savedAddr = devicePrefs.getString("address_" + deviceId, "");
                if (savedAddr.isEmpty()) {
                    Toast.makeText(this, "Firstly Assign Address!", Toast.LENGTH_SHORT).show();
                    return;
                }
                int userAddress;
                try {
                    userAddress = Integer.parseInt(savedAddr);
                    if (userAddress < 1 || userAddress > 8) {
                        Toast.makeText(this, "Saved address invalid (1–8)!", Toast.LENGTH_SHORT).show();
                        return;
                    }
                } catch (NumberFormatException e) {
                    Toast.makeText(this, "Saved address invalid!", Toast.LENGTH_SHORT).show();
                    return;
                }
                int bleCommand = 50 + userAddress;
                MeshCommandManager.sendOnThenOff(
                        this, mViewModel, tidCounter,
                        mUnicastAddress, relationDeviceName, bleCommand);
                Toast.makeText(this,
                        "Short CMD → cmd=" + bleCommand + " (addr=" + userAddress + ")",
                        Toast.LENGTH_SHORT).show();

            } else {
                // ── Other devices: purana hardcoded cmd=2 flow ────────────
                MeshCommandManager.sendOnThenOff(
                        this, mViewModel, tidCounter,
                        mUnicastAddress, relationDeviceName);
                Toast.makeText(this, "Sending ON → OFF...", Toast.LENGTH_SHORT).show();
            }

            btnTestBle.setEnabled(false);
            btnTestBle.postDelayed(() -> btnTestBle.setEnabled(true), 2100);
        });
        // ── MQTT Test button ──────────────────────────────────────────────
        btnTestMqtt.setOnClickListener(v -> {
            if (!isProvisioned(deviceId)) {
                Toast.makeText(this, "Device not provisioned!", Toast.LENGTH_SHORT).show();
                return;
            }
            SharedPreferences mqttPrefs = getSharedPreferences(
                    MqttSettingsActivity.PREFS_MQTT, Context.MODE_PRIVATE);

            final String finalHost  = mqttPrefs.getString(MqttSettingsActivity.KEY_BROKER_HOST, "");
            final int    finalPort  = mqttPrefs.getInt(MqttSettingsActivity.KEY_BROKER_PORT, 1883);
            final String finalUser  = mqttPrefs.getString(MqttSettingsActivity.KEY_USERNAME, "");
            final String finalPass  = mqttPrefs.getString(MqttSettingsActivity.KEY_PASSWORD, "");
            final String finalTopic = getMqttTopicForPublish();

            if (finalTopic == null || finalTopic.isEmpty()) {
                Toast.makeText(this, "Topic build nahi hua! SVG name check.",
                        Toast.LENGTH_LONG).show();
                return;
            }
            if (finalHost.isEmpty()) {
                Toast.makeText(this,
                        "MQTT not configured! Go to Settings → MQTT Configuration",
                        Toast.LENGTH_LONG).show();
                return;
            }
            if (elementId == null || elementId.isEmpty()) {
                Toast.makeText(this, "Element ID not found for this device!",
                        Toast.LENGTH_LONG).show();
                return;
            }
            String onValue = MqttSettingsActivity.getOnValue(mqttPrefs, relationDeviceName);
            if (onValue.isEmpty()) {
                Toast.makeText(this,
                        "ON value resolve nahi hua for " + deviceId,
                        Toast.LENGTH_LONG).show();
                return;
            }

            final String payloadOn  = MqttSettingsActivity.buildOnCommand(elementId, onValue);
            final String payloadOff = MqttSettingsActivity.buildOffCommand(elementId);

            Log.d(TAG, "MQTT → " + finalHost + ":" + finalPort + " topic=" + finalTopic);

            btnTestMqtt.setEnabled(false);
            Toast.makeText(this, "Sending Command 1... (ON value: " + onValue + ")",
                    Toast.LENGTH_SHORT).show();

            mqttExecutor.execute(() -> {
                boolean ok = publishMqtt(finalHost, finalPort, finalUser, finalPass,
                        finalTopic, payloadOn);
                if (!ok) return;
                try { Thread.sleep(3000); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                runOnUiThread(() ->
                        Toast.makeText(this, "Sending Command 2...", Toast.LENGTH_SHORT).show());
                publishMqtt(finalHost, finalPort, finalUser, finalPass, finalTopic, payloadOff);
                runOnUiThread(() -> {
                    Toast.makeText(this, "✓ Both commands sent!", Toast.LENGTH_SHORT).show();
                    btnTestMqtt.postDelayed(() -> btnTestMqtt.setEnabled(true), 500);
                });
            });
        });
    }

    private void saveAndSendAddress(String addrStr, int userAddress) {
        devicePrefs.edit()
                .putString("address_" + deviceId, addrStr)
                .apply();

        etAddress.clearFocus();
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager)
                        getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(etAddress.getWindowToken(), 0);
        // ─────────────────────────────────────────────────────────

        Toast.makeText(this,
                "Address saved: " + addrStr + " → Sending command...",
                Toast.LENGTH_SHORT).show();

        sendLongCommand(userAddress);

        btnSaveAddress.setEnabled(false);
        btnSaveAddress.postDelayed(() -> btnSaveAddress.setEnabled(true), 2100);
    }

    /**
     * Returns true if deviceId contains "LC Node" (case-insensitive)
     * after the last ':' separator.
     * e.g. "Area1 : LC Node 3"  → true
     *      "Area1 : Dimmer 2"   → false
     */
    private boolean isLcNodeDevice(String id) {
        if (id == null) return false;
        String part = id;
        int colon = id.lastIndexOf(":");
        if (colon != -1) {
            part = id.substring(colon + 1).trim();
        }
        return part.toLowerCase().contains("lc node");
    }

    // =========================================================================
    // Long Command
    // =========================================================================

    private void sendLongCommand(int userAddress) {
        ApplicationKey appKey = getFirstAppKey();
        if (appKey == null) {
            Toast.makeText(this, "No AppKey found in network!", Toast.LENGTH_SHORT).show();
            return;
        }

        int[] data = new int[8];
        data[0] = LONG_DATA_1;        // 11
        data[1] = LONG_DATA_2;        // 1
        data[2] = userAddress;        // user input
        data[3] = LONG_DATA_DEFAULT;  // 0
        data[4] = LONG_DATA_DEFAULT;  // 0
        data[5] = LONG_DATA_DEFAULT;  // 0
        data[6] = LONG_DATA_DEFAULT;  // 0
        data[7] = LONG_DATA_DEFAULT;  // 0

        int tid = getNextLightTid();

        Log.d(TAG, "══ sendLongCommand ══");
        Log.d(TAG, String.format("  Dest UnicastAddr : 0x%04X", mUnicastAddress));
        Log.d(TAG, String.format("  Length           : %d",     LONG_CMD_LENGTH));
        Log.d(TAG, String.format("  Command          : %d (0x%02X)", LONG_CMD_COMMAND, LONG_CMD_COMMAND));
        Log.d(TAG, String.format("  Data             : %s",     Arrays.toString(data)));
        Log.d(TAG, String.format("  TID              : %d",     tid));
        Log.d(TAG, "════════════════════");

        try {
            GenericLightSet msg = new GenericLightSet(
                    appKey, LONG_CMD_LENGTH, LONG_CMD_COMMAND, data, tid);
            mViewModel.getMeshManagerApi().createMeshPdu(mUnicastAddress, msg);
            Toast.makeText(this,
                    String.format("Long CMD sent → addr=0x%04X data3=%d TID=%d",
                            mUnicastAddress, userAddress, tid),
                    Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Log.e(TAG, "sendLongCommand failed", e);
            Toast.makeText(this, "Send failed: " + e.getMessage(),
                    Toast.LENGTH_SHORT).show();
        }
    }

    // ── TID counter ───────────────────────────────────────────────────────
    private int getNextLightTid() {
        int c = genericLightTidCounter.getAndIncrement();
        if (c > MAX_TID) { genericLightTidCounter.set(0); c = 0; }
        return c;
    }

    // ── AppKey helper ─────────────────────────────────────────────────────
    private ApplicationKey getFirstAppKey() {
        try {
            List<ApplicationKey> keys = mViewModel.getNetworkLiveData().getAppKeys();
            if (keys != null && !keys.isEmpty()) return keys.get(0);
        } catch (Exception e) {
            Log.e(TAG, "getFirstAppKey error", e);
        }
        return null;
    }

    // =========================================================================
    // Address loading
    // =========================================================================

    private void loadAddressesFromNodes(List<ProvisionedMeshNode> nodes) {
        if (deviceId == null) { setAddressFields("N/A", "N/A"); return; }

        String storeKey      = deviceId.trim().toLowerCase();
        int    storedUnicast = ClientServerElementStore.getServerUnicastAddress(storeKey);
        String storedMac     = ClientServerElementStore.getServerMacAddress(storeKey);

        if (storedUnicast != -1) {
            for (ProvisionedMeshNode node : nodes) {
                if (node.getUnicastAddress() == storedUnicast) {
                    mUnicastAddress = storedUnicast;
                    setAddressFields(
                            storedMac != null ? storedMac : "N/A",
                            String.format("0x%04X", mUnicastAddress));
                    return;
                }
            }
        }

        for (ProvisionedMeshNode node : nodes) {
            String mappedSvg = mViewModel.getSvgIdFromNode(node);
            if (deviceId.equalsIgnoreCase(mappedSvg)) {
                mUnicastAddress = node.getUnicastAddress();
                String mac = node.getMacAddress() != null ? node.getMacAddress() : "N/A";
                ClientServerElementStore.saveServerUnicastAddress(storeKey, mUnicastAddress);
                if (!mac.equals("N/A"))
                    ClientServerElementStore.saveServerMacAddress(storeKey, mac);
                setAddressFields(mac, String.format("0x%04X", mUnicastAddress));
                return;
            }
        }

        mUnicastAddress = -1;
        setAddressFields("N/A", "N/A");
    }

    // =========================================================================
    // MQTT helpers
    // =========================================================================

    private String getMqttTopicForPublish() {
        if (svgName != null && !svgName.isEmpty()
                && relationDeviceName != null && !relationDeviceName.isEmpty()) {
            String[] parts  = relationDeviceName.split("_");
            String   prefix = parts[0].trim().toLowerCase();
            return svgName + "/" + prefix + "/in";
        }
        return null;
    }

    private void updateMqttTopicDisplay(String relDevName) {
        if (tvMqttTopic == null) return;
        String finalTopic;
        if (svgName != null && !svgName.isEmpty()
                && relDevName != null && !relDevName.isEmpty()) {
            String[] parts  = relDevName.split("_");
            String   prefix = parts[0].trim().toLowerCase();
            finalTopic = svgName + "/" + prefix + "/in";
        } else if (svgName != null && !svgName.isEmpty()
                && topicPrefix != null && !topicPrefix.isEmpty()) {
            finalTopic = svgName + "/" + topicPrefix + "/in";
        } else {
            finalTopic = "default/in";
        }
        tvMqttTopic.setText(finalTopic);
    }

    private boolean publishMqtt(String host, int port,
                                String username, String password,
                                String topic, String payload) {
        String clientId  = "mesh-android-" + System.currentTimeMillis();
        String brokerUri = "tcp://" + host + ":" + port;
        try {
            if (mqttClient != null && mqttClient.isConnected()) mqttClient.disconnect();
            mqttClient = new MqttClient(brokerUri, clientId, new MemoryPersistence());
            MqttConnectOptions opts = new MqttConnectOptions();
            opts.setCleanSession(true);
            opts.setConnectionTimeout(10);
            opts.setKeepAliveInterval(30);
            if (!username.isEmpty()) {
                opts.setUserName(username);
                opts.setPassword(password.toCharArray());
            }
            mqttClient.setCallback(new MqttCallback() {
                @Override public void connectionLost(Throwable cause) {}
                @Override public void messageArrived(String t, MqttMessage m) {}
                @Override public void deliveryComplete(IMqttDeliveryToken token) {
                    Log.d(TAG, "MQTT delivery complete: " + payload);
                }
            });
            mqttClient.connect(opts);
            MqttMessage msg = new MqttMessage(payload.getBytes());
            msg.setQos(1);
            msg.setRetained(false);
            mqttClient.publish(topic, msg);
            mqttClient.disconnect();
            return true;
        } catch (MqttException e) {
            Log.e(TAG, "MQTT publish failed", e);
            runOnUiThread(() -> {
                Toast.makeText(this, "MQTT Error: " + e.getMessage(),
                        Toast.LENGTH_LONG).show();
                btnTestMqtt.setEnabled(true);
            });
            return false;
        }
    }

    // =========================================================================
    // Misc helpers
    // =========================================================================

    private void setAddressFields(String mac, String unicast) {
        if (tvMacAddress     != null) tvMacAddress.setText(mac);
        if (tvUnicastAddress != null) tvUnicastAddress.setText(unicast);
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
        if (id == null) return false;
        return ClientServerElementStore
                .getServerUnicastAddress(id.trim().toLowerCase()) != -1;
    }

    private String extractAreaPrefix(String fullId) {
        if (fullId == null || !fullId.contains(":")) return "";
        return fullId.split(":")[0].trim().toUpperCase();
    }

    private String extractBaseName(String fullId) {
        if (fullId == null) return "";
        String name  = fullId.trim().toLowerCase();
        int    colon = name.lastIndexOf(":");
        if (colon != -1) name = name.substring(colon + 1).trim();
        return name;
    }

    private String extractPureNameNoNumber(String fullId) {
        String base = extractBaseName(fullId);
        return base.replaceAll("\\s*\\d+$", "").replaceAll("\\d+$", "").trim();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mqttExecutor.shutdown();
        try {
            if (mqttClient != null && mqttClient.isConnected()) mqttClient.disconnect();
        } catch (MqttException e) {
            Log.e(TAG, "MQTT disconnect error", e);
        }
    }
}