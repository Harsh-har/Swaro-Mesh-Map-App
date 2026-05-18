package no.nordicsemi.android.swarorgbww.ble;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.concurrent.atomic.AtomicInteger;

import no.nordicsemi.android.swarorgbww.MqttSettingsActivity;
import no.nordicsemi.android.swarorgbww.viewmodels.SharedViewModel;

public class MeshCommandManager {

    private static final String TAG             = "MeshCommandManager";
    private static final int    HARDCODED_COMMAND = 2;
    private static final int    VALUE_OFF         = 0;
    private static final int    DELAY_MS          = 2000;

    // ─────────────────────────────────────────────────────────────
    // Overload: Other devices → hardcoded cmd = 2
    // ─────────────────────────────────────────────────────────────
    public static void sendOnThenOff(
            Context context,
            SharedViewModel mViewModel,
            AtomicInteger tidCounter,
            int unicastAddress,
            String relationDeviceName
    ) {
        sendOnThenOff(context, mViewModel, tidCounter,
                unicastAddress, relationDeviceName, HARDCODED_COMMAND);
    }

    // ─────────────────────────────────────────────────────────────
    // Main Flow: ON → 2 sec → OFF  (command is passed explicitly)
    // LC Node  → cmd = 51–58
    // Others   → cmd = 2  (via overload above)
    // ─────────────────────────────────────────────────────────────
    public static void sendOnThenOff(
            Context context,
            SharedViewModel mViewModel,
            AtomicInteger tidCounter,
            int unicastAddress,
            String relationDeviceName,
            int command
    ) {
        Log.d(TAG, "=== SINGLE ON → OFF START === cmd=" + command);

        String typeKey = MqttSettingsActivity.extractDeviceTypeKey(relationDeviceName);
        int onValue;
        try {
            onValue = Integer.parseInt(MqttSettingsActivity.getOnValueForType(typeKey));
        } catch (NumberFormatException e) {
            onValue = 1; // fallback
        }

        final int finalOnValue = onValue;
        Log.d(TAG, "BLE ON value for type='" + typeKey + "' → " + finalOnValue);

        sendMeshCommand(mViewModel, tidCounter, finalOnValue, unicastAddress, command);
        Log.d(TAG, "Sent ON cmd=" + command);

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            sendMeshCommand(mViewModel, tidCounter, VALUE_OFF, unicastAddress, command);
            Log.d(TAG, "Sent OFF cmd=" + command);
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
            int unicastAddress,
            int command
    ) {
        try {
            java.util.List<no.nordicsemi.android.swarorgbww.ApplicationKey> appKeys =
                    mViewModel.getNetworkLiveData().getAppKeys();

            if (appKeys == null || appKeys.isEmpty()) {
                Log.e(TAG, "No AppKey found!");
                return;
            }

            int tid = tidCounter.getAndIncrement();
            if (tid > 255) { tidCounter.set(0); tid = 0; }

            Log.d(TAG, String.format(
                    "📤 CMD=0x%02X DATA=0x%02X TID=%d → 0x%04X",
                    command, dataValue, tid, unicastAddress));

            mViewModel.getMeshManagerApi().createMeshPdu(
                    unicastAddress,
                    new no.nordicsemi.android.swarorgbww.transport.GenericOnOffSet(
                            appKeys.get(0),
                            command,
                            dataValue,
                            tid
                    )
            );

        } catch (Exception e) {
            Log.e(TAG, "sendMeshCommand failed: " + e.getMessage());
        }
    }
}