package no.nordicsemi.android.swarorgbww;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.SeekBar;
import android.widget.Toast;
import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.android.material.textview.MaterialTextView;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import dagger.hilt.android.AndroidEntryPoint;
import no.nordicsemi.android.swarorgbww.transport.GenericLightSet;
import no.nordicsemi.android.swarorgbww.transport.ProvisionedMeshNode;
import no.nordicsemi.android.swarorgbww.viewmodels.ClientServerElementStore;
import no.nordicsemi.android.swarorgbww.viewmodels.SharedViewModel;

@AndroidEntryPoint
public class TestProvisionActivity extends AppCompatActivity {

    private static final String TAG               = "TestProvisionActivity";
    private static final String PREFS_NAME        = "mesh_prefs";
    private static final String PREFS_DEVICE_ADDR = "device_address_prefs";

    // ── Brightness prefs key prefix ───────────────────────────────────────
    private static final String KEY_BRIGHTNESS    = "brightness_";

    private static final int  MAX_TID     = 255;
    private static final long DEBOUNCE_MS = 500L;

    // ── Long Command Fixed Values ─────────────────────────────────────────
    private static final int LONG_CMD_LENGTH   = 1;
    private static final int LONG_CMD_COMMAND  = 3;
    private static final int LONG_DATA_1       = 11;
    private static final int LONG_DATA_2       = 1;
    private static final int LONG_DATA_DEFAULT = 0;

    // ── Intent extras ────────────────────────────────────────────────────
    private String deviceId;
    private String relationDeviceName;

    // ── Views ─────────────────────────────────────────────────────────────
    private MaterialTextView  tvSliderValue;
    private MaterialTextView  brightnessLabel;
    private SeekBar           brightnessSeek;
    private View              brightnessFill;
    private View              brightnessThumb;
    private TextInputLayout   layoutAddress;
    private TextInputEditText etAddress;
    private MaterialButton    btnSaveAddress;

    private SharedPreferences              devicePrefs;
    private SharedViewModel                mViewModel;
    private final AtomicInteger            tidCounter             = new AtomicInteger(0);
    private final AtomicInteger            genericLightTidCounter = new AtomicInteger(0);
    private int                            mUnicastAddress        = -1;
    private int                            mSavedAddress          = -1;

    // ── Debounce ──────────────────────────────────────────────────────────
    private final Handler  debounceHandler = new Handler(Looper.getMainLooper());
    private final Runnable sendRunnable    = this::sendSliderCommand;

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
        String elementId   = getIntent().getStringExtra(DeviceDetailActivity.EXTRA_ELEMENT_ID);
        String svgName     = getIntent().getStringExtra("svg_name");
        String topicPrefix = getIntent().getStringExtra("topic_prefix");
        String areaName    = getIntent().getStringExtra("area_name");
        relationDeviceName = getIntent().getStringExtra("EXTRA_RELATION_DEVICE_NAME");

        // Kept for future use
        Log.d(TAG, String.format(Locale.US,
                "extras: elementId=%s svgName=%s topicPrefix=%s areaName=%s",
                elementId, svgName, topicPrefix, areaName));

        // ── View bindings ─────────────────────────────────────────────────
        tvSliderValue   = findViewById(R.id.tv_slider_value);
        brightnessLabel = findViewById(R.id.brightness_label);
        brightnessSeek  = findViewById(R.id.brightness_seek);
        brightnessFill  = findViewById(R.id.brightness_fill);
        brightnessThumb = findViewById(R.id.brightness_thumb);
        layoutAddress   = findViewById(R.id.layout_address);
        etAddress       = findViewById(R.id.et_address);
        btnSaveAddress  = findViewById(R.id.btn_save_address);

        // ── SharedPreferences ─────────────────────────────────────────────
        devicePrefs = getSharedPreferences(PREFS_DEVICE_ADDR, MODE_PRIVATE);

        // ── Load saved address (1–8) ──────────────────────────────────────
        String savedAddr = devicePrefs.getString(getAddressKey(), "");
        if (savedAddr != null && savedAddr.isEmpty()) {
            String fallback = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getString("address_" + getStoreKey(), "");
            if (fallback != null && !fallback.isEmpty()) {
                devicePrefs.edit().putString(getAddressKey(), fallback).apply();
                savedAddr = fallback;
            }
        }
        if (savedAddr != null && !savedAddr.isEmpty()) {
            etAddress.setText(savedAddr);
            try {
                mSavedAddress = Integer.parseInt(savedAddr);
            } catch (NumberFormatException ignored) {}
        }

        // ── Address card visibility ───────────────────────────────────────
        // Show ONLY if: device is LC Node AND address not yet assigned.
        // Once address is saved it auto-hides and won't reappear on next launch.
        boolean isLcNode   = isLcNodeDevice(deviceId);
        boolean hasAddress = mSavedAddress != -1;
        layoutAddress.setVisibility((isLcNode && !hasAddress) ? View.VISIBLE : View.GONE);
        btnSaveAddress.setVisibility((isLcNode && !hasAddress) ? View.VISIBLE : View.GONE);

        // ── Node observer ─────────────────────────────────────────────────
        mViewModel.getNodes().observe(this, nodes -> {
            if (nodes == null || nodes.isEmpty()) {
                mUnicastAddress = -1;
                return;
            }
            loadAddressesFromNodes(nodes);
        });

        // ── Slider init — restore saved brightness ────────────────────────
        int savedBrightness = devicePrefs.getInt(getBrightnessKey(), 0);

        brightnessSeek.setMax(255);
        brightnessSeek.setProgress(savedBrightness);

        String brightnessStr = String.valueOf(savedBrightness);
        tvSliderValue.setText(brightnessStr);
        brightnessLabel.setText(brightnessStr);

        // Update geometry after layout is measured
        brightnessSeek.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        brightnessSeek.getViewTreeObserver()
                                .removeOnGlobalLayoutListener(this);
                        updateSliderGeometry(
                                brightnessSeek.getProgress(),
                                brightnessSeek.getWidth());
                    }
                });

        brightnessSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                String val = String.valueOf(progress);
                tvSliderValue.setText(val);
                brightnessLabel.setText(val);
                updateSliderGeometry(progress, seekBar.getWidth());
                scheduleCommand();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                int progress = seekBar.getProgress();
                String val = String.valueOf(progress);
                tvSliderValue.setText(val);
                brightnessLabel.setText(val);
                updateSliderGeometry(progress, seekBar.getWidth());
                // Save brightness immediately on finger lift
                saveBrightness(progress);
                fireCommandNow();
            }
        });

        // ── Save Address button ───────────────────────────────────────────
        btnSaveAddress.setOnClickListener(v -> {
            String addrStr = etAddress.getText() != null
                    ? etAddress.getText().toString().trim() : "";

            if (addrStr.isEmpty()) {
                Toast.makeText(this,
                        getString(R.string.error_enter_address), Toast.LENGTH_SHORT).show();
                etAddress.requestFocus();
                return;
            }

            int userAddress;
            try {
                userAddress = Integer.parseInt(addrStr);
                if (userAddress < 1 || userAddress > 8) {
                    Toast.makeText(this,
                            getString(R.string.error_address_range), Toast.LENGTH_SHORT).show();
                    return;
                }
            } catch (NumberFormatException e) {
                Toast.makeText(this,
                        getString(R.string.error_invalid_address), Toast.LENGTH_SHORT).show();
                return;
            }

            if (!isProvisioned(deviceId)) {
                Toast.makeText(this,
                        getString(R.string.error_not_provisioned), Toast.LENGTH_SHORT).show();
                return;
            }

            if (mUnicastAddress == -1) {
                Toast.makeText(this,
                        getString(R.string.error_unicast_not_loaded), Toast.LENGTH_SHORT).show();
                return;
            }

            // No confirm dialog — user can't revisit this card once saved
            saveAndSendAddress(addrStr, userAddress);
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        debounceHandler.removeCallbacks(sendRunnable);
    }

    // =========================================================================
    // Brightness persistence
    // =========================================================================

    /** Key is per-device so every light remembers its own brightness. */
    private String getBrightnessKey() {
        return KEY_BRIGHTNESS + getStoreKey();
    }

    private void saveBrightness(int value) {
        devicePrefs.edit().putInt(getBrightnessKey(), value).apply();
    }

    // =========================================================================
    // Save & Send Address
    // =========================================================================

    private void saveAndSendAddress(String addrStr, int userAddress) {
        final String storeKey = getStoreKey();

        // 1. Save to devicePrefs
        devicePrefs.edit()
                .putString(getAddressKey(), addrStr)
                .apply();

        // 2. Save to mesh_prefs (for export/import)
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putInt("lc_address_" + storeKey, userAddress)
                .putString("address_" + storeKey, addrStr)
                .apply();

        // 3. Update in-memory value
        mSavedAddress = userAddress;

        // 4. Hide keyboard
        etAddress.clearFocus();
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager)
                        getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(etAddress.getWindowToken(), 0);

        Toast.makeText(this,
                getString(R.string.address_saved_sending, addrStr),
                Toast.LENGTH_SHORT).show();

        // 5. Send long BLE command
        sendLongCommand(userAddress);

        // 6. Disable button briefly, then auto-hide the address card permanently
        btnSaveAddress.setEnabled(false);
        btnSaveAddress.postDelayed(() -> {
            btnSaveAddress.setEnabled(true);
            layoutAddress.setVisibility(View.GONE);
            btnSaveAddress.setVisibility(View.GONE);
        }, 2100);
    }

    // =========================================================================
    // Long Command (address assignment)
    // =========================================================================

    private void sendLongCommand(int userAddress) {
        ApplicationKey appKey = getFirstAppKey();
        if (appKey == null) {
            Toast.makeText(this,
                    getString(R.string.error_no_app_key), Toast.LENGTH_SHORT).show();
            return;
        }

        int[] data = new int[8];
        data[0] = LONG_DATA_1;
        data[1] = LONG_DATA_2;
        data[2] = userAddress;
        data[3] = LONG_DATA_DEFAULT;
        data[4] = LONG_DATA_DEFAULT;
        data[5] = LONG_DATA_DEFAULT;
        data[6] = LONG_DATA_DEFAULT;
        data[7] = LONG_DATA_DEFAULT;

        int tid = getNextLightTid();

        Log.d(TAG, "══ sendLongCommand ══");
        Log.d(TAG, String.format(Locale.US, "  Dest : 0x%04X", mUnicastAddress));
        Log.d(TAG, String.format(Locale.US, "  CMD  : %d", LONG_CMD_COMMAND));
        Log.d(TAG, String.format(Locale.US, "  Data : %s", Arrays.toString(data)));
        Log.d(TAG, String.format(Locale.US, "  TID  : %d", tid));
        Log.d(TAG, "════════════════════");

        try {
            GenericLightSet msg = new GenericLightSet(
                    appKey, LONG_CMD_LENGTH, LONG_CMD_COMMAND, data, tid);
            mViewModel.getMeshManagerApi().createMeshPdu(mUnicastAddress, msg);
            Toast.makeText(this,
                    getString(R.string.long_cmd_sent, mUnicastAddress, userAddress, tid),
                    Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Log.e(TAG, "sendLongCommand failed", e);
            Toast.makeText(this,
                    getString(R.string.error_send_failed, e.getMessage()),
                    Toast.LENGTH_SHORT).show();
        }
    }

    // =========================================================================
    // Slider Geometry
    // =========================================================================

    private void updateSliderGeometry(int progress, int trackWidth) {
        if (trackWidth <= 0) return;

        int thumbW = brightnessThumb.getWidth();
        if (thumbW == 0) thumbW = dpToPx(40);

        int thumbH = brightnessThumb.getHeight();
        if (thumbH == 0) thumbH = dpToPx(40);

        int   trackH    = ((View) brightnessThumb.getParent()).getHeight();
        int   minOffset = dpToPx(4);
        int   extra     = dpToPx(35);

        // Compute ratio once, reuse for both thumb and fill
        float ratio    = progress / 255f;
        float fillBase = minOffset + ratio * (trackWidth - thumbW - minOffset);

        // Thumb X
        float thumbX = Math.max(minOffset, Math.min(fillBase, trackWidth - thumbW));
        brightnessThumb.setX(thumbX);

        // Fill width
        int fillWidth = Math.max(minOffset, Math.min(Math.round(fillBase) + extra, trackWidth));
        ViewGroup.LayoutParams lp = brightnessFill.getLayoutParams();
        lp.width = fillWidth;
        brightnessFill.setLayoutParams(lp);

        // Thumb Y — vertically centred
        brightnessThumb.setY((trackH - thumbH) / 2f);
    }

    // =========================================================================
    // Debounce helpers
    // =========================================================================

    private void scheduleCommand() {
        debounceHandler.removeCallbacks(sendRunnable);
        debounceHandler.postDelayed(sendRunnable, DEBOUNCE_MS);
    }

    private void fireCommandNow() {
        debounceHandler.removeCallbacks(sendRunnable);
        sendSliderCommand();
    }

    // =========================================================================
    // Send Slider BLE Command
    // =========================================================================

    private void sendSliderCommand() {
        if (!isProvisioned(deviceId)) {
            Toast.makeText(this,
                    getString(R.string.error_not_provisioned), Toast.LENGTH_SHORT).show();
            return;
        }
        if (mUnicastAddress == -1) {
            Toast.makeText(this,
                    getString(R.string.error_unicast_not_loaded), Toast.LENGTH_SHORT).show();
            return;
        }
        if (mSavedAddress == -1) {
            Toast.makeText(this,
                    getString(R.string.error_no_saved_address), Toast.LENGTH_SHORT).show();
            return;
        }

        int sliderValue = brightnessSeek.getProgress();
        int cmd         = 50 + mSavedAddress;

        ApplicationKey appKey = getFirstAppKey();
        if (appKey == null) {
            Toast.makeText(this,
                    getString(R.string.error_no_app_key), Toast.LENGTH_SHORT).show();
            return;
        }

        int tid = getNextTid();

        Log.d(TAG, "══ sendSliderCommand ══");
        Log.d(TAG, String.format(Locale.US, "  Dest  : 0x%04X", mUnicastAddress));
        Log.d(TAG, String.format(Locale.US, "  CMD   : %d  (50 + savedAddress=%d)", cmd, mSavedAddress));
        Log.d(TAG, String.format(Locale.US, "  VALUE : %d", sliderValue));
        Log.d(TAG, String.format(Locale.US, "  TID   : %d", tid));
        Log.d(TAG, "══════════════════════");

        try {
            mViewModel.getMeshManagerApi().createMeshPdu(
                    mUnicastAddress,
                    new no.nordicsemi.android.swarorgbww.transport.GenericOnOffSet(
                            appKey, cmd, sliderValue, tid));
            Log.d(TAG, "Sent OK");
        } catch (Exception e) {
            Log.e(TAG, "sendSliderCommand failed", e);
            Toast.makeText(this,
                    getString(R.string.error_send_failed, e.getMessage()),
                    Toast.LENGTH_SHORT).show();
        }
    }

    // =========================================================================
    // Address loading from nodes
    // =========================================================================

    private void loadAddressesFromNodes(List<ProvisionedMeshNode> nodes) {
        if (deviceId == null) { mUnicastAddress = -1; return; }

        String storeKey      = deviceId.trim().toLowerCase(Locale.US);
        int    storedUnicast = ClientServerElementStore.getServerUnicastAddress(storeKey);

        if (storedUnicast != -1) {
            for (ProvisionedMeshNode node : nodes) {
                if (node.getUnicastAddress() == storedUnicast) {
                    mUnicastAddress = storedUnicast;
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
                return;
            }
        }

        mUnicastAddress = -1;
    }

    // =========================================================================
    // Key helpers
    // =========================================================================

    private String getStoreKey() {
        if (relationDeviceName != null && !relationDeviceName.trim().isEmpty()) {
            return relationDeviceName.trim().toLowerCase(Locale.US);
        }
        return deviceId != null ? deviceId.trim().toLowerCase(Locale.US) : "unknown";
    }

    private String getAddressKey() {
        return "address_" + getStoreKey();
    }

    // =========================================================================
    // LC Node check
    // =========================================================================

    private boolean isLcNodeDevice(String id) {
        if (id == null) return false;
        String part = id;
        int colon = id.lastIndexOf(':');
        if (colon != -1) part = id.substring(colon + 1).trim();
        return part.toLowerCase(Locale.US).contains("lc node");
    }

    // =========================================================================
    // Misc helpers
    // =========================================================================

    private boolean isProvisioned(String id) {
        if (id == null) return false;
        return ClientServerElementStore
                .getServerUnicastAddress(id.trim().toLowerCase(Locale.US)) != -1;
    }

    private int getNextTid() {
        int c = tidCounter.getAndIncrement();
        if (c > MAX_TID) { tidCounter.set(0); c = 0; }
        return c;
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
        } catch (Exception e) {
            Log.e(TAG, "getFirstAppKey error", e);
        }
        return null;
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }
}