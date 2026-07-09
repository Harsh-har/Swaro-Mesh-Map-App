package no.nordicsemi.android.swaromapmesh;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textview.MaterialTextView;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;
import no.nordicsemi.android.swaromapmesh.ble.MeshCommandManager;
import no.nordicsemi.android.swaromapmesh.mqtt.MqttManager;
import no.nordicsemi.android.swaromapmesh.mqtt.MqttSettingsActivity;
import no.nordicsemi.android.swaromapmesh.transport.GenericLightSet;
import no.nordicsemi.android.swaromapmesh.transport.ProvisionedMeshNode;
import no.nordicsemi.android.swaromapmesh.utils.DeviceCodes;
import no.nordicsemi.android.swaromapmesh.utils.DeviceValueManager;
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

    private static final int MAX_TID = 255;

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
    private MaterialButton    btnSaveAddress;
    private TextInputEditText etAddress;

    private SeekBar bleBrightnessSeek, mqttBrightnessSeek;
    private View bleBrightnessFill, mqttBrightnessFill;
    private TextView bleBrightnessLabel, mqttBrightnessLabel;
    private ImageButton bleDecrement, bleIncrement, mqttDecrement, mqttIncrement;

    private SharedPreferences devicePrefs;
    private SharedViewModel   mViewModel;
    private final AtomicInteger tidCounter             = new AtomicInteger(0);
    private final AtomicInteger genericLightTidCounter = new AtomicInteger(0);
    private int               mUnicastAddress        = -1;

    @Inject
    MqttManager mqttManager;

    private final ExecutorService mqttExecutor = Executors.newSingleThreadExecutor();

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
        btnSaveAddress     = findViewById(R.id.btn_save_address);
        etAddress          = findViewById(R.id.et_address);

        bleBrightnessSeek  = findViewById(R.id.bleBrightnessSeek);
        bleBrightnessFill  = findViewById(R.id.bleBrightnessFill);
        bleBrightnessLabel = findViewById(R.id.bleBrightnessLabel);
        bleDecrement       = findViewById(R.id.bleDecrement);
        bleIncrement       = findViewById(R.id.bleIncrement);

        mqttBrightnessSeek  = findViewById(R.id.mqttBrightnessSeek);
        mqttBrightnessFill  = findViewById(R.id.mqttBrightnessFill);
        mqttBrightnessLabel = findViewById(R.id.mqttBrightnessLabel);
        mqttDecrement       = findViewById(R.id.mqttDecrement);
        mqttIncrement       = findViewById(R.id.mqttIncrement);

        // ── SharedPreferences init ────────────────────────────────────────
        devicePrefs = getSharedPreferences(PREFS_DEVICE_ADDR, MODE_PRIVATE);

        // ── Populate Device Details ───────────────────────────────────────
        String pureName = getIntent().getStringExtra(DeviceDetailActivity.EXTRA_PURE_DEVICE_NAME);
        tvDeviceId.setText(pureName != null ? pureName : (deviceId != null ? deviceId : "N/A"));
        tvElementId.setText(elementId != null ? elementId : "N/A");

        String displayRelationName = relationDeviceName;
        if (relationDeviceName != null) {
            String[] rParts = relationDeviceName.split("_");
            if (rParts.length >= 5) {
                String rName = rParts[1];
                String rFriendly = DeviceCodes.getName(rName);
                if (rFriendly != null) rName = rFriendly;
                displayRelationName = rName;
            } else if (relationDeviceName.startsWith("manual_")) {
                displayRelationName = relationDeviceName.substring("manual_".length())
                        .replaceAll("_\\d+$", "").replace("_", " ").trim();
            }
        }
        tvRelationDeviceId.setText(displayRelationName != null ? displayRelationName : "N/A");

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

        // ── Prefill saved address for LC Node ─────────────────────────────
        String savedAddress = devicePrefs.getString(getAddressKey(), "");
        if (savedAddress.isEmpty()) {
            savedAddress = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getString("address_" + getStoreKey(), "");
            if (!savedAddress.isEmpty()) {
                devicePrefs.edit().putString(getAddressKey(), savedAddress).apply();
            }
        }
        if (!savedAddress.isEmpty()) {
            etAddress.setText(savedAddress);
        }

        updateMqttTopicDisplay(relationDeviceName);
        updateStatus();

        // ── Visibility Logic for LC Node Address ──────────────────────────
        View layoutAddress = findViewById(R.id.layout_address);
        View cardOperations = findViewById(R.id.card_operations);
        boolean isLcNode = isLcNodeDevice(deviceId) || isLcNodeDevice(relationDeviceName) || isLcNodeDevice(pureName);
        boolean isControlNode = isControlNodeDevice(deviceId) || isControlNodeDevice(relationDeviceName) || isControlNodeDevice(pureName);
        
        Log.d(TAG, "Visibility Logic: deviceId=" + deviceId + ", relation=" + relationDeviceName + ", pureName=" + pureName + " => isLcNode=" + isLcNode + ", isControlNode=" + isControlNode);
        
        if (cardOperations != null) {
            cardOperations.setVisibility(isControlNode ? View.GONE : View.VISIBLE);
        }
        
        if (layoutAddress != null) {
            layoutAddress.setVisibility(isLcNode ? View.VISIBLE : View.GONE);
        }
        if (btnSaveAddress != null) {
            btnSaveAddress.setVisibility(isLcNode ? View.VISIBLE : View.GONE);
        }

        // ── Slider setup ──────────────────────────────────────────────────
        int savedBleBrightness = devicePrefs.getInt("ble_brightness_" + getStoreKey(), 100);
        int savedMqttBrightness = devicePrefs.getInt("mqtt_brightness_" + getStoreKey(), 100);

        bleBrightnessSeek.setProgress(savedBleBrightness);
        mqttBrightnessSeek.setProgress(savedMqttBrightness);

        setupSlider(bleBrightnessSeek, bleBrightnessFill, bleBrightnessLabel, true);
        setupSlider(mqttBrightnessSeek, mqttBrightnessFill, mqttBrightnessLabel, false);

        bleDecrement.setOnClickListener(v -> adjustProgress(bleBrightnessSeek, -10));
        bleIncrement.setOnClickListener(v -> adjustProgress(bleBrightnessSeek, 10));
        mqttDecrement.setOnClickListener(v -> adjustProgress(mqttBrightnessSeek, -10));
        mqttIncrement.setOnClickListener(v -> adjustProgress(mqttBrightnessSeek, 10));

        // ── Auto-fill address for LC Node if empty ────────────────────────
        if (savedAddress.isEmpty() && isLcNode && relationDeviceName != null) {
            String autoAddress = extractAddressFromRelation(relationDeviceName);
            if (autoAddress != null) {
                etAddress.setText(autoAddress);
            }
        }

        // ── Save Address button ───────────────────────────────────────────
        if (btnSaveAddress != null) {
            btnSaveAddress.setOnClickListener(v -> {
                String addrStr = etAddress.getText() != null ? etAddress.getText().toString().trim() : "";
                if (addrStr.isEmpty()) {
                    Toast.makeText(this, "Please enter an address!", Toast.LENGTH_SHORT).show();
                    return;
                }
                try {
                    int userAddress = Integer.parseInt(addrStr);
                    if (userAddress < 1 || userAddress > 8) {
                        Toast.makeText(this, "Address 1-8 only", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    saveAndSendAddress(addrStr, userAddress);
                } catch (NumberFormatException e) {
                    Toast.makeText(this, "Invalid address", Toast.LENGTH_SHORT).show();
                }
            });
        }

        // ── Node observer ─────────────────────────────────────────────────
        mViewModel.getNodes().observe(this, nodes -> {
            if (nodes == null || nodes.isEmpty()) {
                setAddressFields("N/A", "N/A");
                return;
            }
            loadAddressesFromNodes(nodes);
        });
    }

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

    // =========================================================================
    // Slider Helpers
    // =========================================================================

    private void setupSlider(SeekBar seekBar, View fill, TextView label, boolean isBle) {
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateSliderProgress(seekBar, fill, label, progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                if (seekBar.getParent() != null) {
                    seekBar.getParent().requestDisallowInterceptTouchEvent(true);
                }
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                if (seekBar.getParent() != null) {
                    seekBar.getParent().requestDisallowInterceptTouchEvent(false);
                }
                int progress = seekBar.getProgress();
                if (isBle) {
                    sendBleBrightness(progress);
                } else {
                    sendMqttBrightness(progress);
                }
            }
        });
        // Initial sync
        seekBar.post(() -> updateSliderProgress(seekBar, fill, label, seekBar.getProgress()));
    }

    private void updateSliderProgress(SeekBar seekBar, View fill, TextView label, int progress) {
        int max = DeviceValueManager.getMaxValue(deviceId, relationDeviceName);
        int scaledValue = DeviceValueManager.getScaledValue(progress, max);
        
        if (label != null) label.setText(scaledValue + " (" + progress + "%)");
        if (fill != null) {
            int width = seekBar.getWidth();
            if (width <= 0) {
                seekBar.post(() -> updateSliderProgress(seekBar, fill, label, progress));
                return;
            }
            android.view.ViewGroup.LayoutParams lp = fill.getLayoutParams();
            int minHeight = fill.getHeight();
            int minWidth = Math.max(0, minHeight); 
            int calculatedWidth = (int) (width * (progress / 100.0));
            lp.width = Math.max(minWidth, calculatedWidth);
            fill.setLayoutParams(lp);
        }
    }

    private void adjustProgress(SeekBar seekBar, int delta) {
        int newProgress = seekBar.getProgress() + delta;
        if (newProgress < 0) newProgress = 0;
        if (newProgress > 100) newProgress = 100;
        seekBar.setProgress(newProgress);

        if (seekBar == bleBrightnessSeek) {
            sendBleBrightness(newProgress);
        } else {
            sendMqttBrightness(newProgress);
        }
    }

    private void sendBleBrightness(int progress) {
        if (!isProvisioned(deviceId)) {
            Toast.makeText(this, "Device not provisioned!", Toast.LENGTH_SHORT).show();
            return;
        }
        if (mUnicastAddress == -1) {
            Toast.makeText(this, "Unicast address not loaded yet!", Toast.LENGTH_SHORT).show();
            return;
        }

        // Save brightness locally
        devicePrefs.edit().putInt("ble_brightness_" + getStoreKey(), progress).apply();

        int max = DeviceValueManager.getMaxValue(deviceId, relationDeviceName);
        int scaledValue = DeviceValueManager.getScaledValue(progress, max);

        int command = 2; // Default
        if (isLcNodeDevice(deviceId) || isLcNodeDevice(relationDeviceName)) {
            String savedAddr = devicePrefs.getString(getAddressKey(), "");
            if (!savedAddr.isEmpty()) {
                try {
                    int userAddress = Integer.parseInt(savedAddr);
                    command = 50 + userAddress;
                } catch (NumberFormatException ignored) {}
            }
        }

        MeshCommandManager.sendMeshCommand(mViewModel, tidCounter, scaledValue, mUnicastAddress, command);
        Toast.makeText(this, "BLE: " + scaledValue + " (Max: " + max + ")", Toast.LENGTH_SHORT).show();
    }

    private void sendMqttBrightness(int progress) {
        if (!isProvisioned(deviceId)) {
            Toast.makeText(this, "Device not provisioned!", Toast.LENGTH_SHORT).show();
            return;
        }
        SharedPreferences mqttPrefs = getSharedPreferences(MqttSettingsActivity.PREFS_MQTT, Context.MODE_PRIVATE);
        final String finalHost  = mqttPrefs.getString(MqttSettingsActivity.KEY_BROKER_HOST, "");
        final String finalTopic = getMqttTopicForPublish();

        if (finalTopic == null || finalTopic.isEmpty() || finalHost.isEmpty()) {
            Toast.makeText(this, "MQTT not configured or topic missing!", Toast.LENGTH_SHORT).show();
            return;
        }
        if (elementId == null || elementId.isEmpty()) {
            Toast.makeText(this, "Element ID missing", Toast.LENGTH_SHORT).show();
            return;
        }

        // Save brightness locally
        devicePrefs.edit().putInt("mqtt_brightness_" + getStoreKey(), progress).apply();

        int max = DeviceValueManager.getMaxValue(deviceId, relationDeviceName);
        DeviceValueManager.sendMqttBrightness(this, mqttManager, mqttExecutor, elementId, finalTopic, progress, max, this);
    }

    // =========================================================================
    // Address Helpers
    // =========================================================================

    private String getStoreKey() {
        if (relationDeviceName != null && !relationDeviceName.trim().isEmpty()) {
            return relationDeviceName.trim().toLowerCase();
        }
        return deviceId != null ? deviceId.trim().toLowerCase() : "unknown";
    }

    private String getAddressKey() {
        return "address_" + getStoreKey();
    }

    private void saveAndSendAddress(String addrStr, int userAddress) {
        final String storeKey = getStoreKey();
        devicePrefs.edit().putString(getAddressKey(), addrStr).apply();
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putInt("lc_address_" + storeKey, userAddress)
                .putString("address_" + storeKey, addrStr).apply();

        etAddress.clearFocus();
        android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(etAddress.getWindowToken(), 0);

        Toast.makeText(this, "Address saved: " + addrStr, Toast.LENGTH_SHORT).show();
        sendLongCommand(userAddress);
    }

    private void sendLongCommand(int userAddress) {
        no.nordicsemi.android.swaromapmesh.ApplicationKey appKey = getFirstAppKey();
        if (appKey == null || mUnicastAddress == -1) return;

        int[] data = new int[8];
        data[0] = LONG_DATA_1; data[1] = LONG_DATA_2; data[2] = userAddress;
        int tid = genericLightTidCounter.getAndIncrement();
        if (tid > MAX_TID) genericLightTidCounter.set(0);

        try {
            GenericLightSet msg = new GenericLightSet(appKey, LONG_CMD_LENGTH, LONG_CMD_COMMAND, data, tid);
            mViewModel.getMeshManagerApi().createMeshPdu(mUnicastAddress, msg);
        } catch (Exception e) {
            Log.e(TAG, "sendLongCommand failed", e);
        }
    }

    private no.nordicsemi.android.swaromapmesh.ApplicationKey getFirstAppKey() {
        try {
            List<no.nordicsemi.android.swaromapmesh.ApplicationKey> keys = mViewModel.getNetworkLiveData().getAppKeys();
            if (keys != null && !keys.isEmpty()) return keys.get(0);
        } catch (Exception ignored) {}
        return null;
    }

    private String getMqttTopicForPublish() {
        if (svgName != null && !svgName.isEmpty() && relationDeviceName != null && !relationDeviceName.isEmpty()) {
            String[] parts = relationDeviceName.split("_");
            return svgName + "/" + parts[0].trim().toLowerCase() + "/in";
        }
        return null;
    }

    private boolean isLcNodeDevice(String name) {
        if (name == null || name.isEmpty()) return false;
        String lower = name.toLowerCase();
        Log.d(TAG, "isLcNodeDevice check: '" + name + "'");
        
        // Literal "lc node" or "psd02" (the hardware code)
        if (lower.contains("lc node") || lower.contains("lcnode") || lower.contains("psd02")) {
            Log.d(TAG, "-> Match by literal");
            return true;
        }
        
        return false;
    }

    private boolean isControlNodeDevice(String name) {
        if (name == null || name.isEmpty()) return false;
        String lower = name.toLowerCase();
        // Check for "control node" or hardware code "cn01"
        return lower.contains("control node") || lower.contains("controlnode") || lower.contains("cn01");
    }

    private String extractAddressFromRelation(String relName) {
        if (relName == null) return null;
        String[] parts = relName.split("_");
        // Structure: Area_CategoryCode_Count_Index_EID_RID (6 parts)
        // e.g. Guest_PSD02_1_2_13_13 -> 2 is the address
        if (parts.length >= 6) {
            return parts[3]; // Index part
        }
        
        // Fallback for old "s" logic if it still exists in some places
        for (int i = 0; i < parts.length - 2; i++) {
            if (parts[i].equalsIgnoreCase("s") || parts[i].equalsIgnoreCase("st")) {
                return parts[i + 2];
            }
        }
        return null;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mqttExecutor.shutdown();
    }
}
