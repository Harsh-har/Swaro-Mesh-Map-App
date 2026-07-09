package no.nordicsemi.android.swaromapmesh.utils;

import android.app.Activity;
import android.content.Context;
import android.widget.Toast;
import java.util.concurrent.ExecutorService;
import no.nordicsemi.android.swaromapmesh.mqtt.MqttSettingsActivity;
import no.nordicsemi.android.swaromapmesh.mqtt.MqttManager;

/**
 * Utility to manage device-specific value ranges (max values) and MQTT operations.
 */
public class DeviceValueManager {

    /**
     * Returns the maximum value allowed for a device type.
     */
    public static int getMaxValue(String deviceId, String relationName) {
        String combined = (deviceId != null ? deviceId : "") + "|" + (relationName != null ? relationName : "");
        String lower = combined.toLowerCase();

        // 1. LC Node / Strip Node (s, st) -> 255
        if (lower.contains("psd02") || lower.contains("pss04") || lower.contains("lc node") || lower.contains("strip node") || lower.contains("dimmer")) {
            return 255;
        }
        // 2. Fan Node (f, clf01) -> 10
        if (lower.contains("clf01") || lower.contains("fan node")) {
            return 10;
        }
        // 3. Exhaust Node (ex, cle02) -> 4
        if (lower.contains("cle02") || lower.contains("exhaust node")) {
            return 4;
        }
        // 4. AC Node (ac, ir01) -> 100 (percentage)
        if (lower.contains("ir01") || lower.contains("ac node")) {
            return 100;
        }
        // 5. Control Node (b, cn01) -> 100
        if (lower.contains("cn01") || lower.contains("control node")) {
            return 100;
        }
        // 6. Relay Node (r, rl01) -> 1 (ON/OFF)
        if (lower.contains("rl01") || lower.contains("relay node") || lower.contains("switch")) {
            return 1;
        }

        return 100; // Default range
    }

    /**
     * Scales a 0-100 percentage value to the device's specific max value.
     */
    public static int getScaledValue(int progress, int max) {
        if (progress <= 0) return 0;
        if (progress >= 100) return max;
        return (int) Math.round((progress / 100.0) * max);
    }

    /**
     * Executes the MQTT brightness command.
     */
    public static void sendMqttBrightness(
            Context context,
            MqttManager mqttManager,
            ExecutorService executor,
            String elementId,
            String topic,
            int progress,
            int max,
            Activity activity
    ) {
        int scaledValue = getScaledValue(progress, max);
        String payload = MqttSettingsActivity.buildOnCommand(elementId, String.valueOf(scaledValue));

        executor.execute(() -> {
            try {
                mqttManager.publish(topic, payload);
                if (activity != null) {
                    activity.runOnUiThread(() -> {
                        Toast.makeText(context, "MQTT Sent: " + scaledValue + " (" + progress + "%)", Toast.LENGTH_SHORT).show();
                    });
                }
            } catch (Exception e) {
                if (activity != null) {
                    activity.runOnUiThread(() -> Toast.makeText(context, "MQTT Failed", Toast.LENGTH_SHORT).show());
                }
            }
        });
    }
}
