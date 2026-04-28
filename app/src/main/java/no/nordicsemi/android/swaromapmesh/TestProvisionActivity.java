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
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import dagger.hilt.android.AndroidEntryPoint;
import no.nordicsemi.android.swaromapmesh.ble.MeshCommandManager;
import no.nordicsemi.android.swaromapmesh.transport.ProvisionedMeshNode;
import no.nordicsemi.android.swaromapmesh.viewmodels.ClientServerElementStore;
import no.nordicsemi.android.swaromapmesh.viewmodels.SharedViewModel;

@AndroidEntryPoint
public class TestProvisionActivity extends AppCompatActivity {

    private static final String TAG        = "TestProvisionActivity";
    private static final String PREFS_NAME = "mesh_prefs";
    private static final String KEY_PROVISIONED_DEVICES = "provisioned_devices";

    // ── Instance variables ────────────────────────────────────────────────
    private String deviceId;
    private String elementId;
    private String svgName;
    private String topicPrefix;
    private String areaName;
    private String relationDeviceName;

    private MaterialTextView tvDeviceId;
    private MaterialTextView tvElementId;
    private MaterialTextView tvStatus;
    private MaterialTextView tvMacAddress;
    private MaterialTextView tvUnicastAddress;
    private MaterialTextView tvMqttTopic;
    private MaterialButton   btnTestBle;
    private MaterialButton   btnTestMqtt;
    private MaterialTextView tvRelationDeviceId;

    private SharedViewModel       mViewModel;
    private final AtomicInteger   tidCounter      = new AtomicInteger(0);
    private int                   mUnicastAddress = -1;
    private MqttClient            mqttClient;
    private final ExecutorService mqttExecutor    = Executors.newSingleThreadExecutor();

    // =========================================================================
    // Lifecycle
    // =========================================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_test_provision);

        mViewModel = new ViewModelProvider(this).get(SharedViewModel.class);

        deviceId           = getIntent().getStringExtra(DeviceDetailActivity.EXTRA_DEVICE_ID);
        elementId          = getIntent().getStringExtra(DeviceDetailActivity.EXTRA_ELEMENT_ID);
        svgName            = getIntent().getStringExtra("svg_name");
        topicPrefix        = getIntent().getStringExtra("topic_prefix");
        areaName           = getIntent().getStringExtra("area_name");
        relationDeviceName = getIntent().getStringExtra("EXTRA_RELATION_DEVICE_NAME");

        tvDeviceId         = findViewById(R.id.tv_device_id);
        tvElementId        = findViewById(R.id.tv_element_id);
        tvStatus           = findViewById(R.id.tv_status);
        tvMacAddress       = findViewById(R.id.tv_mac_address);
        tvUnicastAddress   = findViewById(R.id.tv_unicast_address);
        tvMqttTopic        = findViewById(R.id.tv_mqtttopic);
        btnTestBle         = findViewById(R.id.btn_testble);
        btnTestMqtt        = findViewById(R.id.btn_testmqqt);
        tvRelationDeviceId = findViewById(R.id.tv_relation_device_id);

        tvDeviceId.setText(deviceId != null ? deviceId : "N/A");
        tvElementId.setText(elementId != null ? elementId : "N/A");
        tvRelationDeviceId.setText(relationDeviceName != null ? relationDeviceName : "N/A");

        updateMqttTopicDisplay(relationDeviceName);
        updateStatus();

        mViewModel.getNodes().observe(this, nodes -> {
            if (nodes == null || nodes.isEmpty()) {
                Log.w(TAG, "Nodes list empty");
                setAddressFields("N/A", "N/A");
                return;
            }
            loadAddressesFromNodes(nodes);
        });

        // ── BLE Test button ───────────────────────────────────────────────
        btnTestBle.setOnClickListener(v -> {
            if (!isProvisioned(deviceId)) {
                Toast.makeText(this, "Device not provisioned!", Toast.LENGTH_SHORT).show();
                return;
            }
            if (mUnicastAddress == -1) {
                Toast.makeText(this,
                        "Unicast address not loaded yet!", Toast.LENGTH_SHORT).show();
                return;
            }
            MeshCommandManager.sendOnThenOff(this, mViewModel, tidCounter, mUnicastAddress);
            Toast.makeText(this, "Sending ON → OFF...", Toast.LENGTH_SHORT).show();
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
                Toast.makeText(this, "Topic build nahi hua! SVG name check karo.",
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
                Toast.makeText(this,
                        "Element ID not found for this device!", Toast.LENGTH_LONG).show();
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
            Log.d(TAG, "Device=" + deviceId + " ElementId=" + elementId + " OnValue=" + onValue);
            Log.d(TAG, "CMD1=" + payloadOn + " | CMD2=" + payloadOff);

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

    // =========================================================================
    // loadAddressesFromNodes
    //
    // ROOT CAUSE (from logs):
    //   Both BLE nodes are named "Relay Node" (no number suffix).
    //   storedUnicast=0x0002 for smt:relay node1 exists in Store, but that
    //   node was NOT in the nodes list at query time → Step1a failed.
    //   No MAC was stored → Step1b couldn't run.
    //   Step4 → 2 candidates → AMBIGUOUS → N/A.
    //
    // FIX — 5-step matching with MAC as reliable fallback:
    //   Step 1a: elementId → Store unicast → match node by unicast       ✅ best
    //   Step 1b: elementId → Store MAC    → match node by MAC            ✅ new
    //   Step 1c: elementId → Store unicast → closest-unicast among       ✅ new
    //            same-base-name nodes (handles re-provision shifts)
    //   Step 2:  exact deviceId == nodeName
    //   Step 3:  area + baseName WITH number (no strip)
    //   Step 4:  number-stripped, only if exactly 1 candidate
    // =========================================================================
    private void loadAddressesFromNodes(List<ProvisionedMeshNode> nodes) {
        if (deviceId == null) { setAddressFields("N/A", "N/A"); return; }

        ProvisionedMeshNode matched       = null;
        String              storedKeyUsed = null;

        // ── Step 1: elementId → ClientServerElementStore ──────────────────────
        if (elementId != null && !elementId.isEmpty()) {
            try {
                int    targetSvgId = Integer.parseInt(elementId.trim());
                String storedKey   = ClientServerElementStore.getKeyBySvgElementId(targetSvgId);
                Log.d(TAG, "elementId=" + targetSvgId + " → storedKey=" + storedKey);

                if (storedKey != null) {
                    int    storedUnicast = ClientServerElementStore.getServerUnicastAddress(storedKey);
                    String storedMac     = ClientServerElementStore.getServerMacAddress(storedKey);

                    Log.d(TAG, "storedKey=" + storedKey
                            + " unicast=0x" + String.format("%04X", storedUnicast & 0xFFFF)
                            + " mac=" + storedMac);

                    // 1a: unicast direct
                    if (matched == null && storedUnicast != -1) {
                        for (ProvisionedMeshNode node : nodes) {
                            if (node.getUnicastAddress() == storedUnicast) {
                                matched       = node;
                                storedKeyUsed = storedKey;
                                Log.d(TAG, "✅ Step1a unicast match: 0x"
                                        + String.format("%04X", storedUnicast)
                                        + " node=" + node.getNodeName());
                                break;
                            }
                        }
                    }

                    // 1b: MAC match — works even if unicast changed after re-provision
                    if (matched == null && storedMac != null && !storedMac.isEmpty()) {
                        for (ProvisionedMeshNode node : nodes) {
                            String mac = node.getMacAddress();
                            if (storedMac.equalsIgnoreCase(mac)) {
                                matched       = node;
                                storedKeyUsed = storedKey;
                                // Refresh stored unicast if it shifted
                                if (storedUnicast != node.getUnicastAddress()) {
                                    ClientServerElementStore.saveServerUnicastAddress(
                                            storedKey, node.getUnicastAddress());
                                }
                                Log.d(TAG, "✅ Step1b MAC match: mac=" + mac
                                        + " node=" + node.getNodeName()
                                        + " unicast=0x"
                                        + String.format("%04X", node.getUnicastAddress()));
                                break;
                            }
                        }
                    }

                    // 1c: storedUnicast known but node not found → closest-unicast among
                    //     same-base-name nodes (covers "Relay Node" ambiguity safely)
                    if (matched == null && storedUnicast != -1) {
                        String storeNoNum = extractPureNameNoNumber(storedKey);

                        List<ProvisionedMeshNode> sameName = new ArrayList<>();
                        for (ProvisionedMeshNode node : nodes) {
                            if (extractPureNameNoNumber(node.getNodeName()).equals(storeNoNum))
                                sameName.add(node);
                        }

                        if (sameName.size() == 1) {
                            matched       = sameName.get(0);
                            storedKeyUsed = storedKey;
                            ClientServerElementStore.saveServerUnicastAddress(
                                    storedKey, matched.getUnicastAddress());
                            Log.d(TAG, "✅ Step1c single same-name: '"
                                    + storeNoNum + "' → " + matched.getNodeName()
                                    + " unicast=0x"
                                    + String.format("%04X", matched.getUnicastAddress()));

                        } else if (sameName.size() > 1) {
                            // Pick closest unicast — most likely same physical device
                            ProvisionedMeshNode closest = null;
                            int minDiff = Integer.MAX_VALUE;
                            for (ProvisionedMeshNode node : sameName) {
                                int diff = Math.abs(node.getUnicastAddress() - storedUnicast);
                                if (diff < minDiff) { minDiff = diff; closest = node; }
                            }
                            // Accept only within ±4 unicast addresses
                            if (closest != null && minDiff <= 4) {
                                matched       = closest;
                                storedKeyUsed = storedKey;
                                ClientServerElementStore.saveServerUnicastAddress(
                                        storedKey, matched.getUnicastAddress());
                                Log.d(TAG, "✅ Step1c closest-unicast: node="
                                        + matched.getNodeName()
                                        + " stored=0x" + String.format("%04X", storedUnicast)
                                        + " actual=0x"
                                        + String.format("%04X", matched.getUnicastAddress())
                                        + " diff=" + minDiff);
                            } else {
                                Log.w(TAG, "⚠️ Step1c: " + sameName.size()
                                        + " candidates, minDiff="
                                        + (closest != null ? minDiff : "N/A")
                                        + " > 4 — too risky, skipping");
                            }
                        }
                    }
                }
            } catch (NumberFormatException e) {
                Log.w(TAG, "elementId not a number: " + elementId);
            }
        }

        // ── Step 2: Exact deviceId == nodeName ───────────────────────────────
        if (matched == null) {
            for (ProvisionedMeshNode node : nodes) {
                if (deviceId.equalsIgnoreCase(node.getNodeName())) {
                    matched = node;
                    Log.d(TAG, "✅ Step2 exact name: " + node.getNodeName());
                    break;
                }
            }
        }

        // ── Step 3: Area + baseName WITH number (no stripping) ────────────────
        if (matched == null) {
            String deviceArea = extractAreaPrefix(deviceId);
            String deviceBase = extractBaseName(deviceId);
            for (ProvisionedMeshNode node : nodes) {
                String nodeName = node.getNodeName();
                if (nodeName == null) continue;
                boolean areaOk = deviceArea.isEmpty()
                        || extractAreaPrefix(nodeName).isEmpty()
                        || deviceArea.equalsIgnoreCase(extractAreaPrefix(nodeName));
                if (areaOk && extractBaseName(nodeName).equals(deviceBase)) {
                    matched = node;
                    Log.d(TAG, "✅ Step3 area+base(with number): " + node.getNodeName());
                    break;
                }
            }
        }

        // ── Step 4: Number-stripped fallback — only if exactly 1 candidate ────
        if (matched == null) {
            String deviceArea      = extractAreaPrefix(deviceId);
            String devicePureNoNum = extractPureNameNoNumber(deviceId);
            List<ProvisionedMeshNode> candidates = new ArrayList<>();
            for (ProvisionedMeshNode node : nodes) {
                String nodeName = node.getNodeName();
                if (nodeName == null) continue;
                boolean areaOk = deviceArea.isEmpty()
                        || extractAreaPrefix(nodeName).isEmpty()
                        || deviceArea.equalsIgnoreCase(extractAreaPrefix(nodeName));
                if (areaOk && extractPureNameNoNumber(nodeName).equals(devicePureNoNum))
                    candidates.add(node);
            }
            if (candidates.size() == 1) {
                matched = candidates.get(0);
                Log.d(TAG, "✅ Step4 single candidate: " + matched.getNodeName());
            } else if (candidates.size() > 1) {
                Log.e(TAG, "❌ Step4 AMBIGUOUS — " + candidates.size()
                        + " candidates for '" + devicePureNoNum
                        + "' deviceId=" + deviceId + " elementId=" + elementId
                        + " — MAC not yet stored. Will auto-save after first manual match.");
            }
        }

        // ── Result ────────────────────────────────────────────────────────────
        if (matched != null) {
            String mac    = matched.getMacAddress();
            if (mac == null || mac.isEmpty()) mac = "N/A";
            mUnicastAddress = matched.getUnicastAddress();

            // Auto-save MAC so Step1b works on all future opens
            if (storedKeyUsed != null && !"N/A".equals(mac)) {
                String existing = ClientServerElementStore.getServerMacAddress(storedKeyUsed);
                if (existing == null || !existing.equalsIgnoreCase(mac)) {
                    ClientServerElementStore.saveServerMacAddress(storedKeyUsed, mac);
                    Log.d(TAG, "✅ MAC auto-saved: key=" + storedKeyUsed + " mac=" + mac);
                }
            }

            Log.d(TAG, "Final → deviceId=" + deviceId
                    + " elementId=" + elementId
                    + " MAC=" + mac
                    + " Unicast=0x" + String.format("%04X", mUnicastAddress)
                    + " matchedNode=" + matched.getNodeName());
            setAddressFields(mac, String.format("0x%04X", mUnicastAddress));
        } else {
            Log.w(TAG, "❌ No node matched → deviceId=" + deviceId
                    + " elementId=" + elementId);
            mUnicastAddress = -1;
            setAddressFields("N/A", "N/A");
        }
    }

    // =========================================================================
    // String helpers
    // =========================================================================

    /** "smt:Relay Node1" → "SMT" */
    private String extractAreaPrefix(String fullId) {
        if (fullId == null || !fullId.contains(":")) return "";
        return fullId.split(":")[0].trim().toUpperCase();
    }

    /** "smt:Relay Node1" → "relay node1"  (number kept intentionally) */
    private String extractBaseName(String fullId) {
        if (fullId == null) return "";
        String name = fullId.trim().toLowerCase();
        int colon   = name.lastIndexOf(":");
        if (colon != -1) name = name.substring(colon + 1).trim();
        return name;
    }

    /** "relay node1" → "relay node"  (used in Step 4 and Step 1c) */
    private String extractPureNameNoNumber(String fullId) {
        String base = extractBaseName(fullId);
        return base.replaceAll("\\s*\\d+$", "").replaceAll("\\d+$", "").trim();
    }

    /** "relay node1" → "1",  "relay node" → "" */
    @SuppressWarnings("unused")
    private String extractTrailingNumber(String baseName) {
        if (baseName == null || baseName.isEmpty()) return "";
        Matcher m = Pattern.compile("(\\d+)$").matcher(baseName.trim());
        return m.find() ? m.group(1) : "";
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
                @Override public void connectionLost(Throwable cause) {
                    Log.w(TAG, "MQTT connection lost", cause);
                }
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
            Log.d(TAG, "✓ Published to topic '" + topic + "': " + payload);
            return true;
        } catch (MqttException e) {
            Log.e(TAG, "MQTT publish failed for topic '" + topic + "': " + payload, e);
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
            tvStatus.setTextColor(
                    getResources().getColor(android.R.color.holo_green_light));
        } else {
            tvStatus.setText("Not Provisioned");
            tvStatus.setTextColor(
                    getResources().getColor(android.R.color.holo_orange_light));
        }
    }

    private boolean isProvisioned(String id) {
        SharedPreferences prefs   = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        Set<String>       devices = prefs.getStringSet(KEY_PROVISIONED_DEVICES, new HashSet<>());
        return devices.contains(id);
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