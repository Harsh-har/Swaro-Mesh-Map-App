package no.nordicsemi.android.swaromapmesh.ble;

import android.content.Context;
import android.util.Log;

import java.util.concurrent.atomic.AtomicInteger;

import no.nordicsemi.android.swaromapmesh.mqtt.MqttSettingsActivity;
import no.nordicsemi.android.swaromapmesh.viewmodels.SharedViewModel;

public class MeshCommandManager {

    private static final String TAG             = "MeshCommandManager";
    private static final int    HARDCODED_COMMAND = 2;

    /**
     * Sends a single command (default CMD=2) based on the device name's mapped ON value.
     */
    public static void sendSingleCommand(
            Context context,
            SharedViewModel mViewModel,
            AtomicInteger tidCounter,
            int unicastAddress,
            String relationDeviceName
    ) {
        sendSingleCommand(context, mViewModel, tidCounter,
                unicastAddress, relationDeviceName, HARDCODED_COMMAND);
    }

    /**
     * Sends a single command with a specific CMD value.
     */
    public static void sendSingleCommand(
            Context context,
            SharedViewModel mViewModel,
            AtomicInteger tidCounter,
            int unicastAddress,
            String relationDeviceName,
            int command
    ) {
        Log.d(TAG, "=== SINGLE COMMAND START === cmd=" + command);

        int onValue;
        try {
            onValue = Integer.parseInt(MqttSettingsActivity.getOnValueForName(relationDeviceName));
        } catch (NumberFormatException e) {
            onValue = 1; // fallback
        }

        Log.d(TAG, "BLE ON value for name='" + relationDeviceName + "' → " + onValue);

        sendMeshCommand(mViewModel, tidCounter, onValue, unicastAddress, command);
        Log.d(TAG, "=== SINGLE COMMAND END ===");
    }

    /**
     * Core mesh send: Creates and sends a Mesh PDU.
     */
    public static void sendMeshCommand(
            SharedViewModel mViewModel,
            AtomicInteger tidCounter,
            int dataValue,
            int unicastAddress,
            int command
    ) {
        try {
            java.util.List<no.nordicsemi.android.swaromapmesh.ApplicationKey> appKeys =
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
                    new no.nordicsemi.android.swaromapmesh.transport.GenericOnOffSet(
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

    // Deprecated: Alias for sendSingleCommand
    @Deprecated
    public static void sendOnThenOff(Context c, SharedViewModel vm, AtomicInteger tc, int addr, String name) {
        sendSingleCommand(c, vm, tc, addr, name);
    }

    @Deprecated
    public static void sendOnThenOff(Context c, SharedViewModel vm, AtomicInteger tc, int addr, String name, int cmd) {
        sendSingleCommand(c, vm, tc, addr, name, cmd);
    }
}
