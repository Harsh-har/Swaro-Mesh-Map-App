package no.nordicsemi.android.swaromapmesh.ble;

import android.content.Context;
import android.util.Log;
import android.widget.Toast;

public class MeshCommandManager {

    private static final String TAG = "MeshCommandManager";

    public static void sendTestCommand(Context context, String deviceId) {
        Log.d(TAG, "Sending test command to: " + deviceId);

        // 👉 Yaha actual mesh command jayega
        // Example (pseudo):
        // meshManagerApi.sendGenericOnOffSet(...)

        Toast.makeText(context,
                "Command sent to " + deviceId,
                Toast.LENGTH_SHORT).show();
    }
}