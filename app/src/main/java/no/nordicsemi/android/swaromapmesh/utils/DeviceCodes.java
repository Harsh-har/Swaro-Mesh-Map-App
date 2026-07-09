package no.nordicsemi.android.swaromapmesh.utils;

import java.util.HashMap;
import java.util.Map;

public class DeviceCodes {
    public static final String CONTROL_NODE = "CN01";
    public static final String WARDROBE_SENSOR = "SWO03";
    public static final String HIDDEN_OCCUPANCY_SENSOR = "SHO02";
    public static final String OCCUPANCY_SENSOR = "SSO01";
    public static final String STRIP_NODE = "PSS04";
    public static final String LC_NODE = "PSD02";
    public static final String AC_NODE = "IR01";
    public static final String RELAY_NODE = "RL01";
    public static final String TEMPERATURE_NODE = "STH06";
    public static final String AQI_NODE = "SAQ07";
    public static final String SWITCH_PLATE = "CS08";
    public static final String SINGLE_KNOB_NODE = "CS07";
    public static final String CLASSIC_SWITCHPLATE = "CS09";
    public static final String FAN_NODE = "CLF01";
    public static final String EXHAUST_NODE = "CLE02";

    private static final Map<String, String> CODE_TO_NAME = new HashMap<>();
    private static final Map<String, String> NAME_TO_CODE = new HashMap<>();

    static {
        CODE_TO_NAME.put(CONTROL_NODE, "Control Node");
        CODE_TO_NAME.put(WARDROBE_SENSOR, "Wardrobe Sensor");
        CODE_TO_NAME.put(HIDDEN_OCCUPANCY_SENSOR, "Hidden Occupancy Sensor");
        CODE_TO_NAME.put(OCCUPANCY_SENSOR, "Occupancy Sensor");
        CODE_TO_NAME.put(STRIP_NODE, "Strip Node");
        CODE_TO_NAME.put(LC_NODE, "LC Node");
        CODE_TO_NAME.put(AC_NODE, "AC Node");
        CODE_TO_NAME.put(RELAY_NODE, "Relay Node");
        CODE_TO_NAME.put(TEMPERATURE_NODE, "Temperature Node");
        CODE_TO_NAME.put(AQI_NODE, "AQI Node");
        CODE_TO_NAME.put(SWITCH_PLATE, "Switch Plate");
        CODE_TO_NAME.put(SINGLE_KNOB_NODE, "SingleKnob Node");
        CODE_TO_NAME.put(CLASSIC_SWITCHPLATE, "Classic Switchplate");
        CODE_TO_NAME.put(FAN_NODE, "Fan Node");
        CODE_TO_NAME.put(EXHAUST_NODE, "Exhaust Node");

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

        // Strip the trailing count number if it exists (e.g. "LC Node 5" -> "LC Node")
        // This ensures that when filtering by a specific map icon, we show all real devices of that type.
        String cleanFilter = filterCode.replaceAll("\\s+\\d+$", "").trim();

        if (deviceName == null) return false;

        String lowerDeviceName = deviceName.toLowerCase();
        String lowerFilterCode = cleanFilter.toLowerCase();

        // ── 1. Always allow generic mesh nodes so they are never filtered out during provisioning ──
        if (lowerDeviceName.contains("nrf mesh") || lowerDeviceName.contains("mesh node") || lowerDeviceName.equals("unknown")) {
            return true;
        }

        // ── 2. Direct match with filter string (code or name) ──
        if (lowerDeviceName.contains(lowerFilterCode)) return true;

        // ── 3. Check if filterCode is a known code (e.g. PSD02) ──
        String friendlyName = CODE_TO_NAME.get(cleanFilter);
        if (friendlyName != null && lowerDeviceName.contains(friendlyName.toLowerCase())) {
            return true;
        }

        // ── 4. Check if filterCode contains a known friendly name (e.g. "LC Node 5" matches "LC Node") ──
        for (Map.Entry<String, String> entry : NAME_TO_CODE.entrySet()) {
            String knownFriendly = entry.getKey();
            String code          = entry.getValue().toLowerCase();
            if (lowerFilterCode.contains(knownFriendly)) {
                if (lowerDeviceName.contains(code) || lowerDeviceName.contains(knownFriendly)) {
                    return true;
                }
            }
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

    /**
     * Extracts the trailing count number from a filter string (e.g. "LC Node 5" -> "5").
     */
    public static String extractCount(String filter) {
        if (filter == null) return null;
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("\\s+(\\d+)$");
        java.util.regex.Matcher m = p.matcher(filter);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }
}
