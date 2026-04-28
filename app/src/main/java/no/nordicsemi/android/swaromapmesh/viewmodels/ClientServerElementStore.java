package no.nordicsemi.android.swaromapmesh.viewmodels;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;


public final class ClientServerElementStore {

    private static final String TAG   = "ClientServerElementStore";
    private static final String PREFS = "mesh_prefs";
    private static final String PRE_CLIENT_ADDR   = "element_addr_";
    private static final String PRE_SVR_UNICAST   = "server_unicast_";
    private static final String PRE_SVR_MESH_IDX  = "server_mesh_element_index_";
    private static final String PRE_SVR_PRIM_ADDR = "server_primary_addr_";
    private static final String PRE_SVR_SVG_ID    = "server_svg_element_id_";
    private static final String PRE_SVR_AREA_ID   = "server_area_id_";
    private static final String PRE_CLIENT_TO_SVR = "client_to_server_";

    private static SharedPreferences sPrefs;
    private static Context sAppContext;
    private ClientServerElementStore() {}

    // =========================================================================
    // Init
    // =========================================================================

    public static void init(Context appContext) {
        if (appContext == null) return;
        sAppContext = appContext.getApplicationContext();
        if (sPrefs == null) {
            sPrefs = sAppContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        }
    }

    private static SharedPreferences getPrefs() {
        if (sPrefs == null && sAppContext != null) {
            sPrefs = sAppContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        }
        return sPrefs;
    }

    public static SharedPreferences getPrefsPublic() {
        return getPrefs();
    }

    private static boolean checkInit(String caller) {
        if (getPrefs() == null) {
            Log.e(TAG, caller + ": not initialised — call init(context) first");
            return false;
        }
        return true;
    }

    private static boolean isEmpty(String value, String caller) {
        if (value == null || value.isEmpty()) {
            Log.e(TAG, caller + ": deviceId is null/empty");
            return true;
        }
        return false;
    }

    // =========================================================================
    // CLIENT — element addresses
    // =========================================================================

    public static void saveAll(String deviceId, Map<Integer, Integer> addressMap) {
        if (!checkInit("saveAll") || isEmpty(deviceId, "saveAll")) return;
        if (addressMap == null || addressMap.isEmpty()) {
            Log.w(TAG, "saveAll: empty map for " + deviceId);
            return;
        }
        SharedPreferences.Editor editor = getPrefs().edit();
        for (Map.Entry<Integer, Integer> e : addressMap.entrySet()) {
            editor.putInt(PRE_CLIENT_ADDR + deviceId + "_" + e.getKey(), e.getValue());
        }
        editor.apply();
        Log.d(TAG, "✅ saveAll: " + addressMap.size() + " client elements for " + deviceId);
    }

    public static int getClientAddress(String deviceId, int index) {
        if (!checkInit("getClientAddress") || deviceId == null) return -1;
        return getPrefs().getInt(PRE_CLIENT_ADDR + deviceId + "_" + index, -1);
    }

    /** @deprecated Use {@link #getClientAddress(String, int)} */
    @Deprecated
    public static int get(String deviceId, int index) {
        return getClientAddress(deviceId, index);
    }

    // =========================================================================
    // SERVER — unicast address
    // =========================================================================

    public static void saveServerUnicastAddress(String deviceId, int unicastAddress) {
        if (!checkInit("saveServerUnicastAddress")
                || isEmpty(deviceId, "saveServerUnicastAddress")) return;
        getPrefs().edit().putInt(PRE_SVR_UNICAST + deviceId, unicastAddress).apply();
    }

    public static int getServerUnicastAddress(String deviceId) {
        if (!checkInit("getServerUnicastAddress") || deviceId == null) return -1;
        return getPrefs().getInt(PRE_SVR_UNICAST + deviceId, -1);
    }

    // =========================================================================
    // SERVER — mesh element index
    // =========================================================================

    public static void saveServerMeshElementIndex(String deviceId, int meshElementIndex) {
        if (!checkInit("saveServerMeshElementIndex")
                || isEmpty(deviceId, "saveServerMeshElementIndex")) return;
        getPrefs().edit().putInt(PRE_SVR_MESH_IDX + deviceId, meshElementIndex).apply();
    }

    public static int getServerMeshElementIndex(String deviceId) {
        if (!checkInit("getServerMeshElementIndex") || deviceId == null) return -1;
        return getPrefs().getInt(PRE_SVR_MESH_IDX + deviceId, -1);
    }

    // =========================================================================
    // SERVER — primary element address
    // =========================================================================

    public static void saveServerPrimaryElementAddress(String deviceId, int primaryAddress) {
        if (!checkInit("saveServerPrimaryElementAddress")
                || isEmpty(deviceId, "saveServerPrimaryElementAddress")) return;
        getPrefs().edit().putInt(PRE_SVR_PRIM_ADDR + deviceId, primaryAddress).apply();
    }

    public static int getServerPrimaryElementAddress(String deviceId) {
        if (!checkInit("getServerPrimaryElementAddress") || deviceId == null) return -1;
        return getPrefs().getInt(PRE_SVR_PRIM_ADDR + deviceId, -1);
    }

    // =========================================================================
    // SERVER — SVG element ID
    // =========================================================================

    public static void saveServerSvgElementId(String deviceId, int svgElementId) {
        if (!checkInit("saveServerSvgElementId")
                || isEmpty(deviceId, "saveServerSvgElementId")) return;
        getPrefs().edit().putInt(PRE_SVR_SVG_ID + deviceId, svgElementId).apply();
        Log.d(TAG, "✅ Saved SVG element ID: " + deviceId + " = " + svgElementId);
    }

    public static int getServerSvgElementId(String deviceId) {
        if (!checkInit("getServerSvgElementId") || deviceId == null) return -1;
        return getPrefs().getInt(PRE_SVR_SVG_ID + deviceId, -1);
    }

    public static String getKeyBySvgElementId(int svgElementId) {
        SharedPreferences prefs = getPrefs();
        if (prefs == null) return null;
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            if (!entry.getKey().startsWith(PRE_SVR_SVG_ID)) continue;
            Object val = entry.getValue();
            if (val instanceof Integer && (Integer) val == svgElementId) {
                String key = entry.getKey().substring(PRE_SVR_SVG_ID.length());
                Log.d(TAG, "getKeyBySvgElementId: svgId=" + svgElementId + " → " + key);
                return key;
            }
        }
        Log.w(TAG, "getKeyBySvgElementId: no key found for svgElementId=" + svgElementId);
        return null;
    }

    public static List<String> getAllServerSvgKeys() {
        List<String> result = new ArrayList<>();
        SharedPreferences prefs = getPrefs();
        if (prefs == null) return result;
        for (String key : prefs.getAll().keySet()) {
            if (key.startsWith(PRE_SVR_SVG_ID)) {
                result.add(key.substring(PRE_SVR_SVG_ID.length()));
            }
        }
        return result;
    }
    public static void saveServerMacAddress(String key, String mac) {
        if (sPrefs == null || key == null || mac == null) return;
        sPrefs.edit().putString("mac_" + key.toLowerCase(), mac).apply();
    }

    public static String getServerMacAddress(String key) {
        if (sPrefs == null || key == null) return null;
        return sPrefs.getString("mac_" + key.toLowerCase(), null);
    }
    // =========================================================================
    // SERVER — batch save
    // =========================================================================

    public static void saveCompleteServerInfo(String deviceId,
                                              int unicastAddress,
                                              int meshElementIndex,
                                              int primaryElementAddress) {
        saveServerUnicastAddress(deviceId, unicastAddress);
        saveServerMeshElementIndex(deviceId, meshElementIndex);
        saveServerPrimaryElementAddress(deviceId, primaryElementAddress);
        Log.d(TAG, String.format(
                "✅ Server info saved: device=%s unicast=0x%04X primaryAddr=0x%04X",
                deviceId, unicastAddress, primaryElementAddress));
    }

    // =========================================================================
    // SERVER — Area ID
    // =========================================================================

    public static void saveServerAreaId(String deviceId, String areaId) {
        if (!checkInit("saveServerAreaId") || isEmpty(deviceId, "saveServerAreaId")) return;
        getPrefs().edit().putString(PRE_SVR_AREA_ID + deviceId, areaId).apply();
    }

    public static String getServerAreaId(String deviceId) {
        if (!checkInit("getServerAreaId") || deviceId == null) return null;
        return getPrefs().getString(PRE_SVR_AREA_ID + deviceId, null);
    }

    // =========================================================================
    // ✅ NEW: clearServerData
    // ─────────────────────────────────────────────────────────────────────────
    // Server node delete hone par:
    //   1. Server ke saare prefs keys hata do (unicast, primaryAddr, meshIdx, svgId, areaId)
    //   2. Jis svgElementId ka server tha, us index ke liye
    //      client_to_server_{clientKey}_{elementIndex} bhi clean karo
    //
    // @param serverStoreKey  e.g. "pdri:relay node1"
    // =========================================================================
    public static void clearServerData(String serverStoreKey) {
        if (!checkInit("clearServerData") || isEmpty(serverStoreKey, "clearServerData")) return;

        SharedPreferences prefs = getPrefs();
        SharedPreferences.Editor editor = prefs.edit();

        // ── Step 1: Remove server-specific keys ──────────────────────────────
        editor.remove(PRE_SVR_UNICAST   + serverStoreKey);
        editor.remove(PRE_SVR_MESH_IDX  + serverStoreKey);
        editor.remove(PRE_SVR_PRIM_ADDR + serverStoreKey);
        editor.remove(PRE_SVR_SVG_ID    + serverStoreKey);
        editor.remove(PRE_SVR_AREA_ID   + serverStoreKey);

        // ── Step 2: Get the svgElementId this server was assigned ─────────────
        // (read BEFORE removing — we need it to clean client mappings)
        int svgElementId = prefs.getInt(PRE_SVR_SVG_ID + serverStoreKey, -1);

        // ── Step 3: Remove client_to_server_ mappings that point to this server ─
        // These are saved as: "client_to_server_{clientKey}_{elementIndex}" = serverStoreKey
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            String k = entry.getKey();
            if (!k.startsWith(PRE_CLIENT_TO_SVR)) continue;
            Object v = entry.getValue();
            if (serverStoreKey.equalsIgnoreCase(String.valueOf(v))) {
                editor.remove(k);
                Log.d(TAG, "🧹 clearServerData: removed client_to_server mapping → " + k);
            }
        }

        // ── Step 4: If svgElementId was known, also clean element_id_ entry ──
        // element_id_{svgDeviceId} = svgElementId (saved by saveElementId in SharedViewModel)
        if (svgElementId != -1) {
            String prefix = "element_id_";
            for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
                String k = entry.getKey();
                if (!k.startsWith(prefix)) continue;
                Object v = entry.getValue();
                // The value stored is the svgElementId as string
                try {
                    if (Integer.parseInt(String.valueOf(v)) == svgElementId) {
                        editor.remove(k);
                        Log.d(TAG, "🧹 clearServerData: removed element_id mapping → " + k);
                    }
                } catch (NumberFormatException ignored) {}
            }
        }

        editor.apply();
        Log.d(TAG, "✅ clearServerData complete for key='" + serverStoreKey + "'"
                + (svgElementId != -1 ? " svgId=" + svgElementId : ""));
    }
}