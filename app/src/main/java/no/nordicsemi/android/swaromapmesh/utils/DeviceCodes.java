package no.nordicsemi.android.swaromapmesh.utils;

import java.util.HashMap;
import java.util.Map;

public class DeviceCodes {
    public static final String LC_NODE = "PSD02";
    public static final String STRIP_NODE = "PSS04";
    public static final String CONTROL_NODE = "CN01";
    public static final String RELAY_NODE = "RL01";
    // Placeholders for others if they don't have codes yet
    public static final String AC_NODE = "AC01";
    public static final String FAN_NODE = "FN01";

    private static final Map<String, String> CODE_TO_NAME = new HashMap<>();
    private static final Map<String, String> NAME_TO_CODE = new HashMap<>();

    static {
        CODE_TO_NAME.put(LC_NODE, "LC Node");
        CODE_TO_NAME.put(STRIP_NODE, "Strip Node");
        CODE_TO_NAME.put(CONTROL_NODE, "Control Node");
        CODE_TO_NAME.put(RELAY_NODE, "Relay Node");
        CODE_TO_NAME.put(AC_NODE, "AC Node");
        CODE_TO_NAME.put(FAN_NODE, "Fan Node");

        for (Map.Entry<String, String> entry : CODE_TO_NAME.entrySet()) {
            NAME_TO_CODE.put(entry.getValue().toLowerCase(), entry.getKey());
        }
    }

    public static String getName(String code) {
        return CODE_TO_NAME.get(code);
    }

    public static String getCode(String name) {
        if (name == null) return null;
        return NAME_TO_CODE.get(name.toLowerCase());
    }

    /**
     * Returns true if the device name matches the filter code or its associated friendly name.
     */
    public static boolean matches(String deviceName, String filterCode) {
        if (filterCode == null || filterCode.isEmpty() || filterCode.equalsIgnoreCase("All Device")) return true;
        if (deviceName == null) return false;

        String lowerDeviceName = deviceName.toLowerCase();
        String lowerFilterCode = filterCode.toLowerCase();

        // ── 1. Always allow generic mesh nodes so they are never filtered out during provisioning ──
        if (lowerDeviceName.contains("nrf mesh") || lowerDeviceName.contains("mesh node") || lowerDeviceName.equals("unknown")) {
            return true;
        }

        // ── 2. Direct match with filter string (code or name) ──
        if (lowerDeviceName.contains(lowerFilterCode)) return true;

        // ── 3. Check if filterCode is a known code (e.g. PSD02) ──
        String friendlyName = CODE_TO_NAME.get(filterCode);
        if (friendlyName != null && lowerDeviceName.contains(friendlyName.toLowerCase())) {
            return true;
        }

        // ── 4. Check if filterCode is a friendly name (e.g. "LC Node") ──
        String codeForName = NAME_TO_CODE.get(lowerFilterCode);
        if (codeForName != null && lowerDeviceName.contains(codeForName.toLowerCase())) {
            return true;
        }

        // ── 5. Partial code match (e.g. "PSD" matches any device advertising "PSD02") ──
        for (Map.Entry<String, String> entry : CODE_TO_NAME.entrySet()) {
            String code = entry.getKey().toLowerCase();
            String name = entry.getValue();
            if (code.startsWith(lowerFilterCode)) {
                if (name != null && lowerDeviceName.contains(name.toLowerCase())) return true;
            }
        }

        return false;
    }
}
