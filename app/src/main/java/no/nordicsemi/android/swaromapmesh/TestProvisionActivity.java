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

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    private String elementId;   // comes from intent — used directly as element ID in command

    private MaterialTextView tvDeviceId;
    private MaterialTextView tvElementId;
    private MaterialTextView tvStatus;
    private MaterialTextView tvMacAddress;
    private MaterialTextView tvUnicastAddress;
    private MaterialButton   btnTestBle;
    private MaterialButton   btnTestMqtt;

    private SharedViewModel     mViewModel;
    private final AtomicInteger tidCounter = new AtomicInteger(0);

    private int mUnicastAddress = -1;

    private MqttClient            mqttClient;
    private final ExecutorService mqttExecutor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_test_provision);

        mViewModel = new ViewModelProvider(this).get(SharedViewModel.class);

        // elementId from intent is used directly as the element ID in MQTT command
        deviceId  = getIntent().getStringExtra(DeviceDetailActivity.EXTRA_DEVICE_ID);
        elementId = getIntent().getStringExtra(DeviceDetailActivity.EXTRA_ELEMENT_ID);

        tvDeviceId       = findViewById(R.id.tv_device_id);
        tvElementId      = findViewById(R.id.tv_element_id);
        tvStatus         = findViewById(R.id.tv_status);
        tvMacAddress     = findViewById(R.id.tv_mac_address);
        tvUnicastAddress = findViewById(R.id.tv_unicast_address);
        btnTestBle       = findViewById(R.id.btn_testble);
        btnTestMqtt      = findViewById(R.id.btn_testmqqt);

        tvDeviceId.setText(deviceId   != null ? deviceId  : "N/A");
        tvElementId.setText(elementId != null ? elementId : "N/A");

        updateStatus();

        mViewModel.getNodes().observe(this, nodes -> {
            if (nodes == null || nodes.isEmpty()) {
                Log.w(TAG, "Nodes list empty");
                setAddressFields("N/A", "N/A");
                return;
            }
            loadAddressesFromNodes(nodes);
        });

        // ── BLE button ────────────────────────────────────────────────────────
        btnTestBle.setOnClickListener(v -> {
            if (!isProvisioned(deviceId)) {
                Toast.makeText(this, "Device not provisioned!", Toast.LENGTH_SHORT).show();
                return;
            }
            if (mUnicastAddress == -1) {
                Toast.makeText(this, "Unicast address not loaded yet!", Toast.LENGTH_SHORT).show();
                return;
            }
            MeshCommandManager.sendOnThenOff(this, mViewModel, tidCounter, mUnicastAddress);
            Toast.makeText(this, "Sending ON → OFF...", Toast.LENGTH_SHORT).show();
            btnTestBle.setEnabled(false);
            btnTestBle.postDelayed(() -> btnTestBle.setEnabled(true), 2100);
        });

        // ── MQTT button ───────────────────────────────────────────────────────
        btnTestMqtt.setOnClickListener(v -> {

            if (!isProvisioned(deviceId)) {
                Toast.makeText(this, "Device not provisioned!", Toast.LENGTH_SHORT).show();
                return;
            }

            SharedPreferences mqttPrefs =
                    getSharedPreferences(MqttSettingsActivity.PREFS_MQTT, Context.MODE_PRIVATE);

            String host  = mqttPrefs.getString(MqttSettingsActivity.KEY_BROKER_HOST, "");
            int    port  = mqttPrefs.getInt(MqttSettingsActivity.KEY_BROKER_PORT, 1883);
            String user  = mqttPrefs.getString(MqttSettingsActivity.KEY_USERNAME, "");
            String pass  = mqttPrefs.getString(MqttSettingsActivity.KEY_PASSWORD, "");
            String topic = mqttPrefs.getString(MqttSettingsActivity.KEY_PUBLISH_TOPIC, "mesh/device/cmd");

            if (host.isEmpty()) {
                Toast.makeText(this,
                        "MQTT not configured! Go to Settings → MQTT Configuration",
                        Toast.LENGTH_LONG).show();
                return;
            }

            // element ID comes from intent
            if (elementId == null || elementId.isEmpty()) {
                Toast.makeText(this,
                        "Element ID not found for this device!",
                        Toast.LENGTH_LONG).show();
                return;
            }

            // ON value comes from saved MQTT settings, matched by deviceId
            String onValue = MqttSettingsActivity.getOnValue(mqttPrefs, deviceId);

            if (onValue.isEmpty()) {
                Toast.makeText(this,
                        "ON value not set for " + deviceId +
                                "! Go to Settings → MQTT Configuration",
                        Toast.LENGTH_LONG).show();
                return;
            }

            final String payloadOn  = MqttSettingsActivity.buildOnCommand(elementId, onValue);
            final String payloadOff = MqttSettingsActivity.buildOffCommand(elementId);

            Log.d(TAG, "MQTT → " + host + ":" + port + " topic=" + topic);
            Log.d(TAG, "Device=" + deviceId + " ElementId=" + elementId + " OnValue=" + onValue);
            Log.d(TAG, "CMD1=" + payloadOn + " | CMD2=" + payloadOff);

            btnTestMqtt.setEnabled(false);
            Toast.makeText(this, "Sending Command 1...", Toast.LENGTH_SHORT).show();

            mqttExecutor.execute(() -> {

                boolean ok = publishMqtt(host, port, user, pass, topic, payloadOn);
                if (!ok) return;

                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }

                runOnUiThread(() ->
                        Toast.makeText(this, "Sending Command 2...", Toast.LENGTH_SHORT).show());

                publishMqtt(host, port, user, pass, topic, payloadOff);

                runOnUiThread(() -> {
                    Toast.makeText(this, "✓ Both commands sent!", Toast.LENGTH_SHORT).show();
                    btnTestMqtt.postDelayed(() -> btnTestMqtt.setEnabled(true), 500);
                });
            });
        });
    }

    // ── MQTT publish ──────────────────────────────────────────────────────────

    private boolean publishMqtt(String host, int port,
                                String username, String password,
                                String topic, String payload) {
        String clientId  = "mesh-android-" + System.currentTimeMillis();
        String brokerUri = "tcp://" + host + ":" + port;

        try {
            if (mqttClient != null && mqttClient.isConnected()) {
                mqttClient.disconnect();
            }

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

            Log.d(TAG, "✓ Published: " + payload);
            return true;

        } catch (MqttException e) {
            Log.e(TAG, "MQTT publish failed: " + payload, e);
            runOnUiThread(() -> {
                Toast.makeText(this,
                        "MQTT Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                btnTestMqtt.setEnabled(true);
            });
            return false;
        }
    }

    // ── Address loading ───────────────────────────────────────────────────────

    private void loadAddressesFromNodes(List<ProvisionedMeshNode> nodes) {
        if (deviceId == null) {
            setAddressFields("N/A", "N/A");
            return;
        }

        ProvisionedMeshNode matched = null;

        for (ProvisionedMeshNode node : nodes) {
            if (deviceId.equalsIgnoreCase(node.getNodeName())) {
                matched = node;
                Log.d(TAG, "Node matched by name: " + node.getNodeName());
                break;
            }
        }

        if (matched == null && nodes.size() == 1) {
            matched = nodes.get(0);
            Log.d(TAG, "Single node fallback: " + matched.getNodeName());
        }

        if (matched != null) {
            String mac = matched.getMacAddress();
            if (mac == null || mac.isEmpty()) mac = "N/A";

            int unicastInt  = matched.getUnicastAddress();
            mUnicastAddress = unicastInt;
            String unicast  = String.format("0x%04X", unicastInt);

            Log.d(TAG, "MAC=" + mac + " Unicast=" + unicast);
            setAddressFields(mac, unicast);

        } else {
            Log.w(TAG, "No node matched for deviceId=" + deviceId);
            mUnicastAddress = -1;
            setAddressFields("N/A", "N/A");
        }
    }

    private void setAddressFields(String mac, String unicast) {
        if (tvMacAddress != null)     tvMacAddress.setText(mac);
        if (tvUnicastAddress != null) tvUnicastAddress.setText(unicast);
    }

    // ── Status ────────────────────────────────────────────────────────────────

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
        SharedPreferences prefs =
                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        Set<String> devices =
                prefs.getStringSet(KEY_PROVISIONED_DEVICES, new HashSet<>());
        return devices.contains(id);
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mqttExecutor.shutdown();
        try {
            if (mqttClient != null && mqttClient.isConnected()) {
                mqttClient.disconnect();
            }
        } catch (MqttException e) {
            Log.e(TAG, "MQTT disconnect error", e);
        }
    }
}