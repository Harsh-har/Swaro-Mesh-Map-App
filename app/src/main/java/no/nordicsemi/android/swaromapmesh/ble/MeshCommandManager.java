package no.nordicsemi.android.swaromapmesh.ble;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.concurrent.atomic.AtomicInteger;

import no.nordicsemi.android.swaromapmesh.viewmodels.SharedViewModel;

public class MeshCommandManager {

    private static final String TAG = "MeshCommandManager";

    private static final int HARDCODED_COMMAND = 2;
    private static final int VALUE_ON          = 1;
    private static final int VALUE_OFF         = 0;
    private static final int DELAY_MS          = 5000;

    // ── Blink config ──────────────────────────────────────────────
    public static final int BLINK_ON_MS  = 200;
    public static final int BLINK_OFF_MS = 200;
    public static final int BLINK_COUNT  = 3;

    // Blink handler (static so we can cancel anytime)
    private static final Handler blinkHandler = new Handler(Looper.getMainLooper());
    private static Runnable blinkRunnable;
    private static boolean isBlinking = false;

    // ─────────────────────────────────────────────────────────────
    // Original test: ON → 5sec → OFF
    // ─────────────────────────────────────────────────────────────
    public static void sendTestCommand(
            Context context,
            SharedViewModel mViewModel,
            AtomicInteger tidCounter,
            int unicastAddress
    ) {
        Log.d(TAG, "=== TEST SEQUENCE START ===");

        sendMeshCommand(mViewModel, tidCounter, VALUE_ON, unicastAddress);
        Log.d(TAG, "Sent CMD=" + HARDCODED_COMMAND + " VALUE=" + VALUE_ON);

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            sendMeshCommand(mViewModel, tidCounter, VALUE_OFF, unicastAddress);
            Log.d(TAG, "Sent CMD=" + HARDCODED_COMMAND + " VALUE=" + VALUE_OFF);
            Log.d(TAG, "=== TEST SEQUENCE END ===");
        }, DELAY_MS);
    }

    // ─────────────────────────────────────────────────────────────
    // Blink: ON/OFF repeat for BLINK_COUNT times
    // ─────────────────────────────────────────────────────────────
    public static void startBlink(
            SharedViewModel mViewModel,
            AtomicInteger tidCounter,
            int unicastAddress
    ) {
        if (isBlinking) {
            Log.d(TAG, "Already blinking, ignoring.");
            return;
        }

        isBlinking = true;
        Log.d(TAG, "=== BLINK START === unicast=0x" + String.format("%04X", unicastAddress));

        final int[] count = {0};

        blinkRunnable = new Runnable() {
            @Override
            public void run() {
                if (count[0] >= BLINK_COUNT) {
                    // Blink done — turn off finally
                    sendMeshCommand(mViewModel, tidCounter, VALUE_OFF, unicastAddress);
                    isBlinking = false;
                    Log.d(TAG, "=== BLINK END ===");
                    return;
                }

                // Send ON
                sendMeshCommand(mViewModel, tidCounter, VALUE_ON, unicastAddress);
                Log.d(TAG, "BLINK ON  #" + (count[0] + 1));

                // After BLINK_ON_MS → send OFF, then schedule next ON
                blinkHandler.postDelayed(() -> {
                    sendMeshCommand(mViewModel, tidCounter, VALUE_OFF, unicastAddress);
                    Log.d(TAG, "BLINK OFF #" + (count[0] + 1));
                    count[0]++;
                    blinkHandler.postDelayed(blinkRunnable, BLINK_OFF_MS);
                }, BLINK_ON_MS);
            }
        };

        blinkHandler.post(blinkRunnable);
    }

    // ─────────────────────────────────────────────────────────────
    // Stop blink anytime
    // ─────────────────────────────────────────────────────────────
    public static void stopBlink(
            SharedViewModel mViewModel,
            AtomicInteger tidCounter,
            int unicastAddress
    ) {
        if (!isBlinking) return;
        blinkHandler.removeCallbacks(blinkRunnable);
        isBlinking = false;
        sendMeshCommand(mViewModel, tidCounter, VALUE_OFF, unicastAddress); // force OFF
        Log.d(TAG, "=== BLINK STOPPED ===");
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
            if (tid > 255) { tidCounter.set(0); tid = 0; }

            Log.d(TAG, String.format(
                    "📤 CMD=0x%02X  DATA=0x%02X  TID=%d  → 0x%04X",
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