package no.nordicsemi.android.swaromapmesh.swajaui.Svg_Operations;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;


public final class SvgDeviceMapper {

    // -------------------------------------------------------------------------
    // Short name → Full product code
    // -------------------------------------------------------------------------
    private static final Map<String, String> SHORT_TO_PRODUCT;

    static {
        Map<String, String> m = new HashMap<>();

        // Relay
        m.put("RL01",   "Relay Node");
        m.put("RL02",   "Relay Node");
        m.put("RL03",   "Relay Node");

        // Controllers
        m.put("CLF01",  "SW-CLF01-100");
        m.put("CLE02",  "SW-CLE02-050");
        m.put("CLC03",  "SW-CLC03-150");

        // Power Supply
        m.put("PSU01",  "SW-PSU01-30");
        m.put("PSD02",  "SW-PSD02-60");
        m.put("PSS04",  "SW-PSS04-60");
        m.put("PSR05",  "SW-PSR05-60");

        // Dimmers / Drivers
        m.put("DND01",  "SW-DND01-03");
        m.put("DNU02",  "SW-DNU02-10");
        m.put("DNT03",  "SW-DNT03-10");
        m.put("DNR04",  "SW-DNR04-60");

        // Dimmer Module
        m.put("DM01",   "SW-DM01-004");

        // Connectors
        m.put("CN01",   "SW-CN01-AA");
        m.put("IR01",   "SW-IR01-AA");

        // UI Keypads
        m.put("UIQP01", "SW-UIQP01-AA");
        m.put("UIQS02", "SW-UIQS02-AA");
        m.put("UIQB03", "SW-UIQB03-AA");
        m.put("UIKP04", "SW-UIKP04-AA");
        m.put("UIKS05", "SW-UIKS05-AA");
        m.put("UIKB06", "SW-UIKB06-AA");
        m.put("CS07",   "SW-CS07-1N");
        m.put("PB08",   "SW-PB08-AA");
        m.put("CS09",   "SW-CS09-6N");

        // Universal Remote / Hub
        m.put("URC01",  "SW-URC01-AA");
        m.put("URT02",  "SW-URT02-AA");
        m.put("UITC01", "SW-UITC01-10");
        m.put("HUB02",  "SW-HUB02-AA");

        // Scene / Output Modules
        m.put("SSO01",  "SW-SSO01-AA");
        m.put("SHO02",  "SW-SHO02-AA");
        m.put("SWO03",  "SW-SWO03-AA");
        m.put("SUVR04", "SW-SUVR04-AA");
        m.put("STH06",  "SW-STH06-AA");
        m.put("SAQ07",  "SW-SAQ07-AA");
        m.put("SFG08",  "SW-SFG08-AA");
        m.put("SAP12",  "SW-SAP12-AA");
        m.put("SOF09",  "SW-SOF09-AA");
        m.put("SCO218", "SW-SCO218-AA");
        m.put("STD10",  "SW-STD10-AA");
        m.put("SWT01",  "SW-SWT01-AA");
        m.put("SFR05",  "SW-SFR05-AA");
        m.put("SWP11",  "SW-SWP11-AA");
        m.put("STW13",  "SW-STW13-AA");
        m.put("SRS17",  "SW-SRS17-AA");
        m.put("SGB14",  "SW-SGB14-AA");
        m.put("SDS15",  "SW-SDS15-AA");
        m.put("SSM16",  "SW-SSM16-AA");

        // Misc
        m.put("SVL01",  "SW-SVL01-AA");
        m.put("SAS01",  "SW-SAS01-AA");
        m.put("HWS01",  "SW-HWS01-AA");
        m.put("MRB01",  "SW-MRB01-AA");
        m.put("MCS02",  "SW-MCS02-AA");

        // Lighting
        m.put("LR97",   "SW-LR97-10");
        m.put("LR95",   "SW-LR95-10");
        m.put("LR00",   "SW-LR00-05");
        m.put("LTW90",  "SW-LTW90-15");
        m.put("LRG00",  "SW-LRG00-28");
        m.put("LS97",   "SW-LS97-10");
        m.put("LGS02",  "SW-LGS02-AA");
        m.put("LGR03",  "SW-LGR03-AA");

        SHORT_TO_PRODUCT = Collections.unmodifiableMap(m);
    }

    // -------------------------------------------------------------------------
    // Short name → Category label
    // -------------------------------------------------------------------------
    private static final Map<String, String> SHORT_TO_CATEGORY;

    static {
        Map<String, String> c = new HashMap<>();
        c.put("RL01",   "Relay");
        c.put("RL02",   "Relay");
        c.put("RL03",   "Relay");
        c.put("CLF01",  "Controller");
        c.put("CLE02",  "Controller");
        c.put("CLC03",  "Controller");
        c.put("PSU01",  "Power Supply");
        c.put("PSD02",  "Power Supply");
        c.put("PSS04",  "Power Supply");
        c.put("PSR05",  "Power Supply");
        c.put("DND01",  "Dimmer");
        c.put("DNU02",  "Dimmer");
        c.put("DNT03",  "Dimmer");
        c.put("DNR04",  "Dimmer");
        c.put("DM01",   "Dimmer Module");
        c.put("CN01",   "Connector");
        c.put("IR01",   "IR Receiver");
        c.put("UIQP01", "UI Keypad");
        c.put("UIQS02", "UI Keypad");
        c.put("UIQB03", "UI Keypad");
        c.put("UIKP04", "UI Keypad");
        c.put("UIKS05", "UI Keypad");
        c.put("UIKB06", "UI Keypad");
        c.put("CS07",   "Control Surface");
        c.put("PB08",   "Push Button");
        c.put("CS09",   "Control Surface");
        c.put("URC01",  "Universal Remote");
        c.put("URT02",  "Universal Remote");
        c.put("UITC01", "Touch Controller");
        c.put("HUB02",  "Hub");
        c.put("SSO01",  "Scene Output");
        c.put("SHO02",  "Scene Output");
        c.put("SWO03",  "Scene Output");
        c.put("SUVR04", "Scene Output");
        c.put("STH06",  "Sensor");
        c.put("SAQ07",  "Sensor");
        c.put("SFG08",  "Scene Output");
        c.put("SAP12",  "Scene Output");
        c.put("SOF09",  "Scene Output");
        c.put("SCO218", "Scene Output");
        c.put("STD10",  "Scene Output");
        c.put("SWT01",  "Scene Output");
        c.put("SFR05",  "Scene Output");
        c.put("SWP11",  "Scene Output");
        c.put("STW13",  "Scene Output");
        c.put("SRS17",  "Scene Output");
        c.put("SGB14",  "Scene Output");
        c.put("SDS15",  "Scene Output");
        c.put("SSM16",  "Scene Output");
        c.put("SVL01",  "Misc");
        c.put("SAS01",  "Misc");
        c.put("HWS01",  "Hardware");
        c.put("MRB01",  "Module");
        c.put("MCS02",  "Module");
        c.put("LR97",   "Lighting");
        c.put("LR95",   "Lighting");
        c.put("LR00",   "Lighting");
        c.put("LTW90",  "Lighting");
        c.put("LRG00",  "Lighting");
        c.put("LS97",   "Lighting");
        c.put("LGS02",  "Lighting");
        c.put("LGR03",  "Lighting");
        SHORT_TO_CATEGORY = Collections.unmodifiableMap(c);
    }

    // Private constructor — utility class, instantiate
    private SvgDeviceMapper() {}

    // -------------------------------------------------------------------------
    // PUBLIC API
    // -------------------------------------------------------------------------

    @Nullable
    public static String getProductCode(@Nullable String svgIdOrShortName) {
        String shortName = extractShortName(svgIdOrShortName);
        if (shortName == null) return null;
        return SHORT_TO_PRODUCT.get(shortName);
    }


    @Nullable
    public static String getShortName(@Nullable String svgIdOrShortName) {
        return extractShortName(svgIdOrShortName);
    }


    @NonNull
    public static String getCategory(@Nullable String svgIdOrShortName) {
        String shortName = extractShortName(svgIdOrShortName);
        if (shortName == null) return "Unknown";
        String cat = SHORT_TO_CATEGORY.get(shortName);
        return cat != null ? cat : "Unknown";
    }

    @Nullable
    public static String getBleFilterName(@Nullable String svgIdOrShortName) {
        String productCode = getProductCode(svgIdOrShortName);
        if (productCode == null) return null;
        // "SW-CLE02-050" → ["SW", "CLE02", "050"] → "SW-CLE02"
        String[] parts = productCode.split("-");
        if (parts.length >= 2) {
            return parts[0] + "-" + parts[1];
        }
        return productCode;
    }

    @Nullable
    public static Result resolve(@Nullable String svgIdOrShortName) {
        String shortName   = extractShortName(svgIdOrShortName);
        if (shortName == null) return null;
        String productCode = SHORT_TO_PRODUCT.get(shortName);
        if (productCode == null) return null;

        Result r = new Result();
        r.svgId         = svgIdOrShortName != null ? svgIdOrShortName.trim() : shortName;
        r.shortName     = shortName;
        r.productCode   = productCode;
        r.bleFilterName = getBleFilterNameFromCode(productCode);
        r.category      = SHORT_TO_CATEGORY.getOrDefault(shortName, "Unknown");
        return r;
    }

    public static boolean isKnownDevice(@Nullable String svgIdOrShortName) {
        return getProductCode(svgIdOrShortName) != null;
    }

    @NonNull
    public static Map<String, String> getAllMappings() {
        return SHORT_TO_PRODUCT;
    }

    // -------------------------------------------------------------------------
    // Result model
    // -------------------------------------------------------------------------

    public static class Result {
        public String svgId;
        public String shortName;
        public String productCode;
        public String bleFilterName;
        public String category;

        @Override
        @NonNull
        public String toString() {
            return "SvgDeviceMapper.Result{"
                    + "svgId='" + svgId + '\''
                    + ", shortName='" + shortName + '\''
                    + ", productCode='" + productCode + '\''
                    + ", bleFilterName='" + bleFilterName + '\''
                    + ", category='" + category + '\''
                    + '}';
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    @Nullable
    private static String extractShortName(@Nullable String input) {
        if (input == null || input.trim().isEmpty()) return null;

        String upper = input.trim().toUpperCase();

        // 1. Direct lookup — short name (e.g., "CLF01")
        if (SHORT_TO_PRODUCT.containsKey(upper)) return upper;

        // 2. Check if input is a full Product Code (e.g., "SW-CLF01-100")
        for (Map.Entry<String, String> entry : SHORT_TO_PRODUCT.entrySet()) {
            if (entry.getValue().equalsIgnoreCase(upper)) {
                return entry.getKey();
            }
        }

        // 3. Check if input starts with SW- and is a partial match (e.g., "SW-CLF01")
        if (upper.startsWith("SW-") || upper.startsWith("SW_")) {
            String[] parts = upper.split("[-_]");
            if (parts.length >= 2) {
                String candidate = parts[1];
                if (SHORT_TO_PRODUCT.containsKey(candidate)) {
                    return candidate;
                }
            }
        }

        String[] parts = upper.split("_");
        for (int i = 0; i < parts.length; i++) {
            String candidate = parts[i];
            if (SHORT_TO_PRODUCT.containsKey(candidate)) {
                return candidate;
            }
        }

        return null;
    }

    @Nullable
    private static String getBleFilterNameFromCode(@Nullable String productCode) {
        if (productCode == null) return null;
        String[] parts = productCode.split("-");
        if (parts.length >= 2) return parts[0] + "-" + parts[1];
        return productCode;
    }
}