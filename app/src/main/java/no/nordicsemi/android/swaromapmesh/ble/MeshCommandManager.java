package no.nordicsemi.android.swaromapmesh.ble;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.concurrent.atomic.AtomicInteger;

import no.nordicsemi.android.swaromapmesh.MqttSettingsActivity;
import no.nordicsemi.android.swaromapmesh.viewmodels.SharedViewModel;

public class MeshCommandManager {

    private static final String TAG = "MeshCommandManager";

    private static final int HARDCODED_COMMAND = 2;
    private static final int VALUE_OFF = 0;

    private static final int DELAY_MS = 2000;

    // ─────────────────────────────────────────────────────────────
    // Single Flow: ON → 2 sec → OFF (device-type-aware)
    // ─────────────────────────────────────────────────────────────
    public static void sendOnThenOff(
            Context context,
            SharedViewModel mViewModel,
            AtomicInteger tidCounter,
            int unicastAddress,
            String relationDeviceName
    ) {
        Log.d(TAG, "=== SINGLE ON → OFF START ===");

        // Resolve ON value from device type (same logic as MQTT)
        String typeKey = MqttSettingsActivity.extractDeviceTypeKey(relationDeviceName);
        int onValue;
        try {
            onValue = Integer.parseInt(MqttSettingsActivity.getOnValueForType(typeKey));
        } catch (NumberFormatException e) {
            onValue = 1; // fallback
        }

        final int finalOnValue = onValue;
        Log.d(TAG, "BLE ON value for type='" + typeKey + "' → " + finalOnValue);

        sendMeshCommand(mViewModel, tidCounter, finalOnValue, unicastAddress);
        Log.d(TAG, "Sent ON");

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            sendMeshCommand(mViewModel, tidCounter, VALUE_OFF, unicastAddress);
            Log.d(TAG, "Sent OFF");
            Log.d(TAG, "=== SINGLE ON → OFF END ===");
        }, DELAY_MS);
    }

    // ─────────────────────────────────────────────────────────────
    // Core mesh send
    // ─────────────────────────────────────────────────────────────
    private static void sendMeshCommand(
            SharedViewModel mViewModel,
            AtomicInteger tidCounter,
            int dataValue,
            int unicastAddress
    ) {
        try {
            java.util.List<no.nordicsemi.android.swaromapmesh.ApplicationKey> appKeys =
                    mViewModel.getNetworkLiveData().getAppKeys();

            if (appKeys == null || appKeys.isEmpty()) {
                Log.e(TAG, "No AppKey found!");
                return;
            }

            int tid = tidCounter.getAndIncrement();
            if (tid > 255) {
                tidCounter.set(0);
                tid = 0;
            }

            Log.d(TAG, String.format(
                    "📤 CMD=0x%02X DATA=0x%02X TID=%d → 0x%04X",
                    HARDCODED_COMMAND, dataValue, tid, unicastAddress));

            mViewModel.getMeshManagerApi().createMeshPdu(
                    unicastAddress,
                    new no.nordicsemi.android.swaromapmesh.transport.GenericOnOffSet(
                            appKeys.get(0),
                            HARDCODED_COMMAND,
                            dataValue,
                            tid
                    )
            );

        } catch (Exception e) {
            Log.e(TAG, "sendMeshCommand failed: " + e.getMessage());
        }
    }
}