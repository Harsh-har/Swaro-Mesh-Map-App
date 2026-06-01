package no.nordicsemi.android.swaromapmesh;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.util.Log;
import android.view.View;
import no.nordicsemi.android.swaromapmesh.R;
import android.widget.LinearLayout;
import android.widget.Toast;
import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import dagger.hilt.android.AndroidEntryPoint;
import no.nordicsemi.android.swaromapmesh.ble.MeshCommandManager;
import no.nordicsemi.android.swaromapmesh.transport.Element;
import no.nordicsemi.android.swaromapmesh.transport.GenericLightSet;
import no.nordicsemi.android.swaromapmesh.transport.MeshModel;
import no.nordicsemi.android.swaromapmesh.transport.ProvisionedMeshNode;
import no.nordicsemi.android.swaromapmesh.viewmodels.ClientServerElementStore;
import no.nordicsemi.android.swaromapmesh.viewmodels.SharedViewModel;

@AndroidEntryPoint
public class TestProvisionActivity extends AppCompatActivity {

    private static final String TAG               = "TestProvisionActivity";
    private static final String PREFS_NAME        = "mesh_prefs";
    private static final String PREFS_DEVICE_ADDR = "device_address_prefs";

    private static final int LONG_CMD_LENGTH   = 1;
    private static final int LONG_CMD_COMMAND  = 3;
    private static final int LONG_DATA_1       = 11;
    private static final int LONG_DATA_2       = 1;
    private static final int LONG_DATA_DEFAULT = 0;
    private static final int MAX_TID           = 255;

    // OnOff Client model ID — used when re-publishing
    private static final int MODEL_ONOFF_CLIENT = 0x1001;

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
    private MaterialCardView  cardClientElements;
    private LinearLayout      llClientElements;

    private SharedPreferences devicePrefs;
    private SharedViewModel   mViewModel;

    private final AtomicInteger tidCounter             = new AtomicInteger(0);
    private final AtomicInteger genericLightTidCounter = new AtomicInteger(0);
    private int                 mUnicastAddress        = -1;

    // Keep reference to control node so the edit dialog can call sendMeshMessage
    private ProvisionedMeshNode mControlNode = null;

    private MqttClient            mqttClient;
    private final ExecutorService mqttExecutor = Executors.newSingleThreadExecutor();

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
        cardClientElements = findViewById(R.id.card_client_elements);
        llClientElements   = findViewById(R.id.ll_client_elements);

        tvDeviceId.setText(deviceId != null ? deviceId : "N/A");
        tvElementId.setText(elementId != null ? elementId : "N/A");
        tvRelationDeviceId.setText(relationDeviceName != null ? relationDeviceName : "N/A");

        String intentReceiveId = getIntent().getStringExtra(DeviceDetailActivity.EXTRA_RECEIVE_ID);
        String normalizedId    = deviceId != null ? deviceId.trim().toLowerCase() : null;
        String storeReceiveId  = ClientServerElementStore.getReceiveId(normalizedId);
        String receiveId = (intentReceiveId != null && !intentReceiveId.isEmpty())
                ? intentReceiveId : storeReceiveId;
        if (tvReceiveId != null)
            tvReceiveId.setText(receiveId != null && !receiveId.isEmpty() ? receiveId : "N/A");
        if (intentReceiveId != null && !intentReceiveId.isEmpty() && storeReceiveId == null)
            ClientServerElementStore.saveReceiveIdOnly(normalizedId, intentReceiveId);

        devicePrefs = getSharedPreferences(PREFS_DEVICE_ADDR, MODE_PRIVATE);
        String savedAddress = devicePrefs.getString(getAddressKey(), "");
        if (savedAddress.isEmpty()) {
            savedAddress = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getString("address_" + getStoreKey(), "");
            if (!savedAddress.isEmpty())
                devicePrefs.edit().putString(getAddressKey(), savedAddress).apply();
        }
        if (!savedAddress.isEmpty()) etAddress.setText(savedAddress);

        updateStatus();

        boolean isClient = isClientDevice(deviceId);
        boolean isLcNode = isLcNodeDevice(deviceId);

        Log.d(TAG, "deviceId=[" + deviceId + "] isClient=" + isClient);

        // ── Server-only rows ──────────────────────────────────────────────
        int sv = isClient ? View.GONE : View.VISIBLE;
        setVis(R.id.layout_mac_address, sv);
        setVis(R.id.layout_relation,    sv);
        setVis(R.id.layout_element_id,  sv);
        setVis(R.id.row_recive_id,      sv);
        setVis(R.id.layout_mqtt_topic,  sv);
        setVis(R.id.card_operations,    sv);

        if (!isClient) {
            updateMqttTopicDisplay(relationDeviceName);
            View layoutAddress = findViewById(R.id.layout_address);
            if (layoutAddress != null)
                layoutAddress.setVisibility(isLcNode ? View.VISIBLE : View.GONE);
            btnSaveAddress.setVisibility(isLcNode ? View.VISIBLE : View.GONE);
        }

        // ── Client elements card ──────────────────────────────────────────
        if (cardClientElements != null)
            cardClientElements.setVisibility(isClient ? View.VISIBLE : View.GONE);

        // ── Node observer ─────────────────────────────────────────────────
        mViewModel.getNodes().observe(this, nodes -> {
            if (nodes == null || nodes.isEmpty()) {
                setAddressFields("N/A", "N/A");
                return;
            }
            loadAddressesFromNodes(nodes);
            if (isClient) showClientElements(nodes);
        });

        // ── Save Address ──────────────────────────────────────────────────
        btnSaveAddress.setOnClickListener(v -> {
            String addrStr = etAddress.getText() != null
                    ? etAddress.getText().toString().trim() : "";
            if (addrStr.isEmpty()) {
                Toast.makeText(this, "Please enter an address!", Toast.LENGTH_SHORT).show();
                etAddress.requestFocus();
                return;
            }
            int userAddress;
            try {
                userAddress = Integer.parseInt(addrStr);
                if (userAddress < 1 || userAddress > 8) {
                    Toast.makeText(this, "Address must be between 1 and 8", Toast.LENGTH_SHORT).show();
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
            String existingAddr = devicePrefs.getString(getAddressKey(), "");
            if (!existingAddr.isEmpty() && !existingAddr.equals(addrStr)) {
                final int finalAddr = userAddress;
                new AlertDialog.Builder(this)
                        .setTitle("Address Change")
                        .setMessage("Address is already set to " + existingAddr
                                + ".\nDo you want to change it to " + addrStr + "?")
                        .setPositiveButton("Yes, Change", (d, w) -> saveAndSendAddress(addrStr, finalAddr))
                        .setNegativeButton("Cancel", (d, w) -> d.dismiss())
                        .show();
                return;
            }
            saveAndSendAddress(addrStr, userAddress);
        });

        // ── BLE Test ──────────────────────────────────────────────────────
        btnTestBle.setOnClickListener(v -> {
            if (!isProvisioned(deviceId)) {
                Toast.makeText(this, "Device not provisioned!", Toast.LENGTH_SHORT).show();
                return;
            }
            if (mUnicastAddress == -1) {
                Toast.makeText(this, "Unicast address not loaded yet!", Toast.LENGTH_SHORT).show();
                return;
            }
            if (isLcNodeDevice(deviceId)) {
                String sa = devicePrefs.getString(getAddressKey(), "");
                if (sa.isEmpty()) {
                    Toast.makeText(this, "Please assign an address first!", Toast.LENGTH_SHORT).show();
                    return;
                }
                int ua;
                try {
                    ua = Integer.parseInt(sa);
                    if (ua < 1 || ua > 8) {
                        Toast.makeText(this, "Saved address is invalid (must be 1-8)!", Toast.LENGTH_SHORT).show();
                        return;
                    }
                } catch (NumberFormatException e) {
                    Toast.makeText(this, "Saved address is invalid!", Toast.LENGTH_SHORT).show();
                    return;
                }
                int cmd = 50 + ua;
                MeshCommandManager.sendOnThenOff(this, mViewModel, tidCounter, mUnicastAddress, relationDeviceName, cmd);
                Toast.makeText(this, "Short CMD → cmd=" + cmd + " (addr=" + ua + ")", Toast.LENGTH_SHORT).show();
            } else {
                MeshCommandManager.sendOnThenOff(this, mViewModel, tidCounter, mUnicastAddress, relationDeviceName);
                Toast.makeText(this, "Sending ON → OFF...", Toast.LENGTH_SHORT).show();
            }
            btnTestBle.setEnabled(false);
            btnTestBle.postDelayed(() -> btnTestBle.setEnabled(true), 2100);
        });

        // ── MQTT Test ─────────────────────────────────────────────────────
        btnTestMqtt.setOnClickListener(v -> {
            if (!isProvisioned(deviceId)) {
                Toast.makeText(this, "Device not provisioned!", Toast.LENGTH_SHORT).show();
                return;
            }
            SharedPreferences mqttPrefs = getSharedPreferences(MqttSettingsActivity.PREFS_MQTT, Context.MODE_PRIVATE);
            final String host  = mqttPrefs.getString(MqttSettingsActivity.KEY_BROKER_HOST, "");
            final int    port  = mqttPrefs.getInt(MqttSettingsActivity.KEY_BROKER_PORT, 1883);
            final String user  = mqttPrefs.getString(MqttSettingsActivity.KEY_USERNAME, "");
            final String pass  = mqttPrefs.getString(MqttSettingsActivity.KEY_PASSWORD, "");
            final String topic = getMqttTopicForPublish();
            if (topic == null || topic.isEmpty()) {
                Toast.makeText(this, "Topic could not be built!", Toast.LENGTH_LONG).show(); return;
            }
            if (host.isEmpty()) {
                Toast.makeText(this, "MQTT not configured!", Toast.LENGTH_LONG).show(); return;
            }
            if (elementId == null || elementId.isEmpty()) {
                Toast.makeText(this, "Element ID not found!", Toast.LENGTH_LONG).show(); return;
            }
            String onValue = MqttSettingsActivity.getOnValue(mqttPrefs, relationDeviceName);
            if (onValue.isEmpty()) {
                Toast.makeText(this, "ON value not resolved for: " + deviceId, Toast.LENGTH_LONG).show(); return;
            }
            final String payloadOn  = MqttSettingsActivity.buildOnCommand(elementId, onValue);
            final String payloadOff = MqttSettingsActivity.buildOffCommand(elementId);
            btnTestMqtt.setEnabled(false);
            Toast.makeText(this, "Sending Command 1...", Toast.LENGTH_SHORT).show();
            mqttExecutor.execute(() -> {
                boolean ok = publishMqtt(host, port, user, pass, topic, payloadOn);
                if (!ok) return;
                try { Thread.sleep(3000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                runOnUiThread(() -> Toast.makeText(this, "Sending Command 2...", Toast.LENGTH_SHORT).show());
                publishMqtt(host, port, user, pass, topic, payloadOff);
                runOnUiThread(() -> {
                    Toast.makeText(this, "Both commands sent!", Toast.LENGTH_SHORT).show();
                    btnTestMqtt.postDelayed(() -> btnTestMqtt.setEnabled(true), 500);
                });
            });
        });
    }

    // =========================================================================
    // Client Elements
    // =========================================================================

    private void showClientElements(List<ProvisionedMeshNode> nodes) {
        if (deviceId == null || llClientElements == null) return;

        // ── Find control node ─────────────────────────────────────────────
        ProvisionedMeshNode controlNode = null;
        String lowerDeviceId = deviceId.trim().toLowerCase();
        for (ProvisionedMeshNode n : nodes) {
            if (n.getNodeName() == null) continue;
            if (n.getNodeName().trim().toLowerCase().equals(lowerDeviceId)) {
                controlNode = n; break;
            }
        }
        if (controlNode == null) {
            for (ProvisionedMeshNode n : nodes) {
                if (n.getNodeName() == null) continue;
                if (n.getNodeName().toLowerCase().contains(lowerDeviceId)) {
                    controlNode = n; break;
                }
            }
        }
        if (controlNode == null) {
            Log.w(TAG, "showClientElements: node not found for " + deviceId);
            return;
        }

        // Store reference for the edit dialog
        mControlNode = controlNode;

        // Update unicast display for client node
        mUnicastAddress = controlNode.getUnicastAddress();
        if (tvUnicastAddress != null)
            tvUnicastAddress.setText(String.format("0x%04X", mUnicastAddress));

        // ── Build unicast → node name map from live network ───────────────
        Map<Integer, String> uniToName = new HashMap<>();
        for (ProvisionedMeshNode n : nodes) {
            if (n.getNodeName() != null)
                uniToName.put(n.getUnicastAddress(), n.getNodeName());
            for (Element el : n.getElements().values())
                uniToName.put(el.getElementAddress(), n.getNodeName());
        }

        // ── Sort elements by address (ascending) ──────────────────────────
        List<Element> sorted = new ArrayList<>(controlNode.getElements().values());
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N)
            sorted.sort((a, b) -> Integer.compare(a.getElementAddress(), b.getElementAddress()));

        llClientElements.removeAllViews();

        // ✅ FIX: use a separate 0-based display counter instead of
        //         (elemAddr - base), so the list always starts at 0
        //         regardless of which elements have AppKey bound.
        int baseAddr = controlNode.getUnicastAddress();

        for (Element el : sorted) {
            MeshModel model = el.getMeshModels().get(0x1001);
            if (model == null) continue;

            int elemAddr = el.getElementAddress();
            int elementOffset = elemAddr - baseAddr;

            int    pubAddr    = 0;
            String pubHex     = "—";
            String serverName = "—";

            if (model.getPublicationSettings() != null) {
                pubAddr = model.getPublicationSettings().getPublishAddress();
                if (pubAddr != 0) {
                    pubHex = String.format("0x%04X", pubAddr);
                    String name = uniToName.get(pubAddr);
                    if (name != null) {
                        serverName = name;
                    } else {
                        serverName = pubAddr >= 0xC000
                                ? "Group " + pubHex
                                : "Unknown " + pubHex;
                    }
                }
            }

            llClientElements.addView(buildRow(
                    elementOffset,        // ✅ Use element offset (0-based) instead of running counter
                    String.format("0x%04X", elemAddr),
                    pubHex,
                    serverName,
                    elemAddr,
                    pubAddr,
                    uniToName));

            Log.d(TAG, "showClientElements: idx=" + elementOffset
                    + " elemAddr=0x" + String.format("%04X", elemAddr)
                    + " pub=" + pubHex + " server=" + serverName);
        }

        if (llClientElements.getChildCount() == 0) {
            MaterialTextView tv = new MaterialTextView(this);
            tv.setText("No mapped elements found");
            tv.setTextColor(0xFF888888);
            tv.setTextSize(13f);
            int p = dp(8);
            tv.setPadding(p, p, p, p);
            llClientElements.addView(tv);
        }

        Log.d(TAG, "showClientElements: " + llClientElements.getChildCount() + " rows for " + deviceId);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // buildRow
    // ─────────────────────────────────────────────────────────────────────────

    private View buildRow(int index,
                          String clientAddr,
                          String pubAddr,
                          String serverName,
                          int    elemAddrInt,
                          int    currentPubAddrInt,
                          Map<Integer, String> uniToName) {

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(6), 0, dp(6));

        MaterialTextView tvIdx =
                make(String.valueOf(index), 0xFFFFBB00, 12f, dp(50));

        MaterialTextView tvCli =
                make(clientAddr, 0xFFCCCCCC, 12f, dp(90));

        // ── "Publishes To" cell — tappable ────────────────────────────────
        MaterialTextView tvPub =
                make(pubAddr, 0xFF4FC3F7, 12f, dp(90));
        tvPub.setClickable(true);
        tvPub.setFocusable(true);
        tvPub.setPaintFlags(tvPub.getPaintFlags()
                | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
        tvPub.setOnClickListener(v ->
                showEditPublishDialog(elemAddrInt, currentPubAddrInt, tvPub, uniToName));

        MaterialTextView tvSrv =
                make(serverName, 0xFF888888, 12f, dp(160));

        row.addView(tvIdx);
        row.addView(tvCli);
        row.addView(tvPub);
        row.addView(tvSrv);

        return row;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Edit Publish Address Dialog
    // ─────────────────────────────────────────────────────────────────────────

    private void showEditPublishDialog(int elemAddrInt,
                                       int currentPubAddr,
                                       MaterialTextView tvPub,
                                       Map<Integer, String> uniToName) {
        if (mControlNode == null) {
            Toast.makeText(this, "Control node not loaded yet", Toast.LENGTH_SHORT).show();
            return;
        }

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        container.setPadding(pad, dp(12), pad, 0);

        TextInputLayout til = new TextInputLayout(this,
                null,
                com.google.android.material.R.style.Widget_MaterialComponents_TextInputLayout_OutlinedBox);
        til.setHint("New publish address (hex, e.g. 0196)");
        til.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);

        TextInputEditText etHex = new TextInputEditText(til.getContext());
        etHex.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        etHex.setFilters(new InputFilter[]{ new InputFilter.LengthFilter(4) });

        if (currentPubAddr > 0)
            etHex.setText(String.format("%04X", currentPubAddr));

        til.addView(etHex);
        container.addView(til);

        MaterialTextView tvHint = new MaterialTextView(this);
        tvHint.setText("Unicast: 0001–7FFF   |   Group: C000–FEFF");
        tvHint.setTextColor(0xFF888888);
        tvHint.setTextSize(11f);
        tvHint.setPadding(0, dp(4), 0, 0);
        container.addView(tvHint);

        new AlertDialog.Builder(this)
                .setTitle(String.format("Edit Publish — Element 0x%04X", elemAddrInt))
                .setView(container)
                .setPositiveButton("Update", (dialog, which) -> {
                    String raw = etHex.getText() != null
                            ? etHex.getText().toString().trim() : "";
                    if (raw.isEmpty()) {
                        Toast.makeText(this, "Address cannot be empty", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    int newAddr;
                    try {
                        newAddr = Integer.parseInt(raw, 16);
                    } catch (NumberFormatException e) {
                        Toast.makeText(this, "Invalid hex value: " + raw, Toast.LENGTH_SHORT).show();
                        return;
                    }

                    boolean isUnicast = newAddr >= 0x0001 && newAddr <= 0x7FFF;
                    boolean isGroup   = newAddr >= 0xC000 && newAddr <= 0xFEFF;
                    if (!isUnicast && !isGroup) {
                        Toast.makeText(this,
                                "Address 0x" + String.format("%04X", newAddr)
                                        + " is not a valid unicast (0001–7FFF)"
                                        + " or group (C000–FEFF) address",
                                Toast.LENGTH_LONG).show();
                        return;
                    }

                    ApplicationKey appKey = getFirstAppKey();
                    if (appKey == null) {
                        Toast.makeText(this, "No AppKey found in network", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    Log.d(TAG, "showEditPublishDialog: updating"
                            + " elem=0x" + String.format("%04X", elemAddrInt)
                            + " oldPub=0x" + String.format("%04X", currentPubAddr)
                            + " newPub=0x" + String.format("%04X", newAddr));

                    mViewModel.updatePublication(
                            mControlNode,
                            elemAddrInt,
                            MODEL_ONOFF_CLIENT,
                            newAddr,
                            appKey.getKeyIndex());

                    Toast.makeText(this,
                            "Publication update sent → 0x" + String.format("%04X", newAddr),
                            Toast.LENGTH_SHORT).show();

                    // Optimistically update the row label
                    String newHex = "0x" + String.format("%04X", newAddr);
                    tvPub.setText(newHex);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // =========================================================================
    // Private row / text helpers
    // =========================================================================

    private MaterialTextView make(String text, int color, float sizeSp, int minWidthPx) {
        MaterialTextView tv = new MaterialTextView(this);
        tv.setText(text);
        tv.setTextColor(color);
        tv.setTextSize(sizeSp);
        if (minWidthPx > 0) tv.setMinWidth(minWidthPx);
        return tv;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // =========================================================================
    // Key helpers
    // =========================================================================

    private String getStoreKey() {
        if (relationDeviceName != null && !relationDeviceName.trim().isEmpty())
            return relationDeviceName.trim().toLowerCase();
        return deviceId != null ? deviceId.trim().toLowerCase() : "unknown";
    }

    private String getAddressKey() { return "address_" + getStoreKey(); }

    // =========================================================================
    // Save & Send
    // =========================================================================

    private void saveAndSendAddress(String addrStr, int userAddress) {
        final String sk = getStoreKey();
        devicePrefs.edit().putString(getAddressKey(), addrStr).apply();
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putInt("lc_address_" + sk, userAddress)
                .putString("address_" + sk, addrStr)
                .apply();
        etAddress.clearFocus();
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(etAddress.getWindowToken(), 0);
        Toast.makeText(this, "Address saved: " + addrStr + " → Sending command...", Toast.LENGTH_SHORT).show();
        sendLongCommand(userAddress);
        btnSaveAddress.setEnabled(false);
        btnSaveAddress.postDelayed(() -> btnSaveAddress.setEnabled(true), 2100);
    }

    // =========================================================================
    // Device type
    // =========================================================================

    private boolean isClientDevice(String id) {
        if (id == null) return false;
        return id.trim().toLowerCase().startsWith("control node");
    }

    private boolean isLcNodeDevice(String id) {
        if (id == null) return false;
        String part = id;
        int c = id.lastIndexOf(":");
        if (c != -1) part = id.substring(c + 1).trim();
        return part.toLowerCase().contains("lc node");
    }

    // =========================================================================
    // Long Command
    // =========================================================================

    private void sendLongCommand(int userAddress) {
        ApplicationKey appKey = getFirstAppKey();
        if (appKey == null) {
            Toast.makeText(this, "No AppKey found!", Toast.LENGTH_SHORT).show();
            return;
        }
        int[] data = {LONG_DATA_1, LONG_DATA_2, userAddress,
                LONG_DATA_DEFAULT, LONG_DATA_DEFAULT, LONG_DATA_DEFAULT, LONG_DATA_DEFAULT, LONG_DATA_DEFAULT};
        int tid = getNextLightTid();
        try {
            GenericLightSet msg = new GenericLightSet(appKey, LONG_CMD_LENGTH, LONG_CMD_COMMAND, data, tid);
            mViewModel.getMeshManagerApi().createMeshPdu(mUnicastAddress, msg);
            Toast.makeText(this,
                    String.format("Long command sent → addr=0x%04X data3=%d TID=%d", mUnicastAddress, userAddress, tid),
                    Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Log.e(TAG, "sendLongCommand failed", e);
            Toast.makeText(this, "Send failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private int getNextLightTid() {
        int c = genericLightTidCounter.getAndIncrement();
        if (c > MAX_TID) { genericLightTidCounter.set(0); c = 0; }
        return c;
    }

    private ApplicationKey getFirstAppKey() {
        try {
            List<ApplicationKey> keys = mViewModel.getNetworkLiveData().getAppKeys();
            if (keys != null && !keys.isEmpty()) return keys.get(0);
        } catch (Exception e) { Log.e(TAG, "getFirstAppKey", e); }
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
                    setAddressFields(storedMac != null ? storedMac : "N/A",
                            String.format("0x%04X", mUnicastAddress));
                    return;
                }
            }
        }
        for (ProvisionedMeshNode node : nodes) {
            String mapped = mViewModel.getSvgIdFromNode(node);
            if (deviceId.equalsIgnoreCase(mapped)) {
                mUnicastAddress = node.getUnicastAddress();
                String mac = node.getMacAddress() != null ? node.getMacAddress() : "N/A";
                ClientServerElementStore.saveServerUnicastAddress(storeKey, mUnicastAddress);
                if (!mac.equals("N/A")) ClientServerElementStore.saveServerMacAddress(storeKey, mac);
                setAddressFields(mac, String.format("0x%04X", mUnicastAddress));
                return;
            }
        }
        mUnicastAddress = -1;
        setAddressFields("N/A", "N/A");
    }

    // =========================================================================
    // MQTT
    // =========================================================================

    private String getMqttTopicForPublish() {
        if (svgName != null && !svgName.isEmpty()
                && relationDeviceName != null && !relationDeviceName.isEmpty()) {
            return svgName + "/" + relationDeviceName.split("_")[0].trim().toLowerCase() + "/in";
        }
        return null;
    }

    private void updateMqttTopicDisplay(String relDevName) {
        if (tvMqttTopic == null) return;
        String t;
        if (svgName != null && !svgName.isEmpty() && relDevName != null && !relDevName.isEmpty()) {
            t = svgName + "/" + relDevName.split("_")[0].trim().toLowerCase() + "/in";
        } else if (svgName != null && !svgName.isEmpty() && topicPrefix != null && !topicPrefix.isEmpty()) {
            t = svgName + "/" + topicPrefix + "/in";
        } else {
            t = "default/in";
        }
        tvMqttTopic.setText(t);
    }

    private boolean publishMqtt(String host, int port, String user, String pass,
                                String topic, String payload) {
        String uri = "tcp://" + host + ":" + port;
        try {
            if (mqttClient != null && mqttClient.isConnected()) mqttClient.disconnect();
            mqttClient = new MqttClient(uri, "mesh-android-" + System.currentTimeMillis(), new MemoryPersistence());
            MqttConnectOptions opts = new MqttConnectOptions();
            opts.setCleanSession(true);
            opts.setConnectionTimeout(10);
            opts.setKeepAliveInterval(30);
            if (!user.isEmpty()) { opts.setUserName(user); opts.setPassword(pass.toCharArray()); }
            mqttClient.setCallback(new MqttCallback() {
                @Override public void connectionLost(Throwable c) {}
                @Override public void messageArrived(String t, MqttMessage m) {}
                @Override public void deliveryComplete(IMqttDeliveryToken tk) {
                    Log.d(TAG, "MQTT delivered: " + payload);
                }
            });
            mqttClient.connect(opts);
            MqttMessage msg = new MqttMessage(payload.getBytes());
            msg.setQos(1); msg.setRetained(false);
            mqttClient.publish(topic, msg);
            mqttClient.disconnect();
            return true;
        } catch (MqttException e) {
            Log.e(TAG, "MQTT publish failed", e);
            runOnUiThread(() -> {
                Toast.makeText(this, "MQTT Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                btnTestMqtt.setEnabled(true);
            });
            return false;
        }
    }

    // =========================================================================
    // Misc
    // =========================================================================

    private void setVis(int id, int vis) {
        View v = findViewById(id);
        if (v != null) v.setVisibility(vis);
    }

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
        return ClientServerElementStore.getServerUnicastAddress(id.trim().toLowerCase()) != -1;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mqttExecutor.shutdown();
        try {
            if (mqttClient != null && mqttClient.isConnected()) mqttClient.disconnect();
        } catch (MqttException e) {
            Log.e(TAG, "MQTT disconnect", e);
        }
    }
}