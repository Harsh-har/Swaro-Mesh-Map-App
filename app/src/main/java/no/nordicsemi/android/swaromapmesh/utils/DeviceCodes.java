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
        CODE_TO_NAME.put(LC_NODE, "Lc Node");
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
        if (filterCode == null || filterCode.isEmpty()) return true;
        if (deviceName == null) return false;

        String lowerDeviceName = deviceName.toLowerCase();
        String lowerFilterCode = filterCode.toLowerCase();

        // Direct match with code
        if (lowerDeviceName.contains(lowerFilterCode)) return true;

        // Match with friendly name associated with the code
        String friendlyName = CODE_TO_NAME.get(filterCode);
        if (friendlyName != null && lowerDeviceName.contains(friendlyName.toLowerCase())) {
            return true;
        }

        // Special case: if filterCode is "PSD02", it should also match "LC Node" (case insensitive)
        // This is already handled by the logic above.

        return false;
    }
}
