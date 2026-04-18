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

    // ✅ Public getter for NrfMeshRepository to iterate keys
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

    public static boolean hasClientData(String deviceId) {
        if (getPrefs() == null || deviceId == null) return false;
        return getPrefs().contains(PRE_CLIENT_ADDR + deviceId + "_0");
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

    // ✅ NEW: Unicast address se stored key dhundho
    // e.g. unicast=0x0002 → "pdri:relay node1"
    public static String getKeyByUnicastAddress(int unicastAddress) {
        SharedPreferences prefs = getPrefs();
        if (prefs == null) return null;
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            if (!entry.getKey().startsWith(PRE_SVR_UNICAST)) continue;
            Object val = entry.getValue();
            if (val instanceof Integer && (Integer) val == unicastAddress) {
                // "server_unicast_pdri:relay node1" → "pdri:relay node1"
                String key = entry.getKey().substring(PRE_SVR_UNICAST.length());
                Log.d(TAG, "getKeyByUnicastAddress: 0x"
                        + String.format("%04X", unicastAddress) + " → " + key);
                return key;
            }
        }
        Log.w(TAG, "getKeyByUnicastAddress: no key found for unicast=0x"
                + String.format("%04X", unicastAddress));
        return null;
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

    // ✅ NEW: SVG element ID se stored key dhundho
    // e.g. svgElementId=1 → "pdri:relay node1"
    // e.g. svgElementId=5 → "pdri:relay node5"
    public static String getKeyBySvgElementId(int svgElementId) {
        SharedPreferences prefs = getPrefs();
        if (prefs == null) return null;
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            if (!entry.getKey().startsWith(PRE_SVR_SVG_ID)) continue;
            Object val = entry.getValue();
            if (val instanceof Integer && (Integer) val == svgElementId) {
                // "server_svg_element_id_pdri:relay node1" → "pdri:relay node1"
                String key = entry.getKey().substring(PRE_SVR_SVG_ID.length());
                Log.d(TAG, "getKeyBySvgElementId: svgId=" + svgElementId + " → " + key);
                return key;
            }
        }
        Log.w(TAG, "getKeyBySvgElementId: no key found for svgElementId=" + svgElementId);
        return null;
    }

    // =========================================================================
    // ✅ Get all keys that have SVG element IDs saved
    // Used by NrfMeshRepository.resolveKeyByNodeName() for suffix matching
    // =========================================================================

    public static List<String> getAllServerSvgKeys() {
        List<String> result = new ArrayList<>();
        SharedPreferences prefs = getPrefs();
        if (prefs == null) return result;
        for (String key : prefs.getAll().keySet()) {
            if (key.startsWith(PRE_SVR_SVG_ID)) {
                // Strip prefix → "pdri:relay node1", "pdri:relay node5" etc.
                result.add(key.substring(PRE_SVR_SVG_ID.length()));
            }
        }
        return result;
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
    // SERVER — batch get
    // =========================================================================

    public static ServerInfo getCompleteServerInfo(String deviceId) {
        int unicast = getServerUnicastAddress(deviceId);
        if (unicast == -1) return null;
        int meshIdx     = getServerMeshElementIndex(deviceId);
        int primaryAddr = getServerPrimaryElementAddress(deviceId);
        int svgId       = getServerSvgElementId(deviceId);
        return new ServerInfo(unicast, meshIdx, primaryAddr, svgId);
    }

    // =========================================================================
    // Clear
    // =========================================================================

    public static void clearDevice(String deviceId) {
        if (getPrefs() == null || deviceId == null) return;
        SharedPreferences.Editor editor = getPrefs().edit();
        for (int i = 0; i <= 40; i++)
            editor.remove(PRE_CLIENT_ADDR + deviceId + "_" + i);
        editor.remove(PRE_SVR_UNICAST   + deviceId);
        editor.remove(PRE_SVR_MESH_IDX  + deviceId);
        editor.remove(PRE_SVR_PRIM_ADDR + deviceId);
        editor.remove(PRE_SVR_SVG_ID    + deviceId);
        editor.apply();
        Log.d(TAG, "Cleared all data for: " + deviceId);
    }

    public static void clearAll() {
        if (getPrefs() == null) return;
        SharedPreferences.Editor editor = getPrefs().edit();
        for (String key : getPrefs().getAll().keySet()) {
            if (key.startsWith(PRE_CLIENT_ADDR)
                    || key.startsWith(PRE_SVR_UNICAST)
                    || key.startsWith(PRE_SVR_MESH_IDX)
                    || key.startsWith(PRE_SVR_PRIM_ADDR)
                    || key.startsWith(PRE_SVR_SVG_ID)) {
                editor.remove(key);
            }
        }
        editor.apply();
        Log.d(TAG, "Cleared ALL client + server data");
    }

    // =========================================================================
    // ServerInfo
    // =========================================================================

    public static class ServerInfo {
        public final int unicastAddress;
        public final int meshElementIndex;
        public final int primaryElementAddress;
        public final int svgElementId;

        public ServerInfo(int unicastAddress, int meshElementIndex,
                          int primaryElementAddress, int svgElementId) {
            this.unicastAddress        = unicastAddress;
            this.meshElementIndex      = meshElementIndex;
            this.primaryElementAddress = primaryElementAddress;
            this.svgElementId          = svgElementId;
        }

        @Override
        public String toString() {
            return String.format(
                    "ServerInfo{unicast=0x%04X, meshIdx=%d, primaryAddr=0x%04X, svgId=%d}",
                    unicastAddress, meshElementIndex, primaryElementAddress, svgElementId);
        }
    }
}