package no.nordicsemi.android.swaromapmesh.viewmodels;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ClientServerElementStore {

    private static final String TAG  = "ClientServerElementStore";
    private static final String PREFS = "mesh_prefs";

    // ── Key prefixes ──────────────────────────────────────────────────────────
    private static final String PRE_SVR_UNICAST   = "server_unicast_";
    private static final String PRE_SVR_MESH_IDX  = "server_mesh_element_index_";
    private static final String PRE_SVR_PRIM_ADDR = "server_primary_addr_";
    private static final String PRE_SVR_SVG_ID    = "server_svg_element_id_";
    private static final String PRE_SVR_AREA_ID   = "server_area_id_";
    private static final String PRE_SVR_MAC       = "mac_";
    private static final String PRE_SVR_NODE_ID   = "server_node_id_";
    private static final String PRE_CLIENT_ADDR   = "element_addr_";
    private static final String PRE_CLIENT_TO_SVR = "client_to_server_";
    private static final String KEY_PROVISIONED   = "provisioned_devices";

    private static SharedPreferences sPrefs;
    private static Context           sAppContext;

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
        if (value == null || value.trim().isEmpty()) {
            Log.e(TAG, caller + ": key is null/empty");
            return true;
        }
        return false;
    }

    public static String normalize(String key) {
        if (key == null) return "";
        return key.trim().toLowerCase();
    }

    // =========================================================================
    // ✅ MASTER SAVE
    // =========================================================================

    /**
     * Save complete device record.
     * nodeId → sirf tab pass karo jab 2 IDs hon (e.g. "2", "3"), warna null.
     */
    public static void saveDevice(String deviceId,
                                  int unicastAddr,
                                  int svgElementId,
                                  String mac,
                                  String nodeId) {
        if (!checkInit("saveDevice") || isEmpty(deviceId, "saveDevice")) return;

        String key = normalize(deviceId);
        SharedPreferences.Editor ed = getPrefs().edit();

        ed.putInt(PRE_SVR_UNICAST   + key, unicastAddr);
        ed.putInt(PRE_SVR_SVG_ID    + key, svgElementId);
        ed.putInt(PRE_SVR_PRIM_ADDR + key, unicastAddr);

        if (mac != null && !mac.isEmpty()) {
            ed.putString(PRE_SVR_MAC + key, mac);
        }

        // nodeId: sirf tab save karo jab 2 IDs hon
        if (nodeId != null && !nodeId.isEmpty()) {
            ed.putString(PRE_SVR_NODE_ID + key, nodeId);
        } else {
            ed.remove(PRE_SVR_NODE_ID + key);
        }

        // provisioned set mein add karo
        Set<String> provisioned = new HashSet<>(
                getPrefs().getStringSet(KEY_PROVISIONED, new HashSet<>()));
        provisioned.add(key);
        ed.putStringSet(KEY_PROVISIONED, provisioned);

        ed.apply();

        Log.d(TAG, "✅ saveDevice: key=" + key
                + " unicast=0x" + String.format("%04X", unicastAddr)
                + " svgId=" + svgElementId
                + " nodeId=" + (nodeId != null ? nodeId : "—")
                + " mac=" + (mac != null ? mac : "null"));
    }

    // =========================================================================
    // SERVER — unicast address
    // =========================================================================

    public static void saveServerUnicastAddress(String deviceId, int unicastAddress) {
        if (!checkInit("saveServerUnicastAddress") || isEmpty(deviceId, "saveServerUnicastAddress")) return;
        String key = normalize(deviceId);
        getPrefs().edit().putInt(PRE_SVR_UNICAST + key, unicastAddress).apply();
        Log.d(TAG, "✅ saveServerUnicastAddress: " + key
                + " = 0x" + String.format("%04X", unicastAddress));
    }

    public static int getServerUnicastAddress(String deviceId) {
        if (!checkInit("getServerUnicastAddress") || deviceId == null) return -1;
        return getPrefs().getInt(PRE_SVR_UNICAST + normalize(deviceId), -1);
    }

    // =========================================================================
    // SERVER — mesh element index
    // =========================================================================

    public static void saveServerMeshElementIndex(String deviceId, int meshElementIndex) {
        if (!checkInit("saveServerMeshElementIndex") || isEmpty(deviceId, "saveServerMeshElementIndex")) return;
        getPrefs().edit().putInt(PRE_SVR_MESH_IDX + normalize(deviceId), meshElementIndex).apply();
    }

    public static int getServerMeshElementIndex(String deviceId) {
        if (!checkInit("getServerMeshElementIndex") || deviceId == null) return -1;
        return getPrefs().getInt(PRE_SVR_MESH_IDX + normalize(deviceId), -1);
    }

    // =========================================================================
    // SERVER — primary element address
    // =========================================================================

    public static void saveServerPrimaryElementAddress(String deviceId, int primaryAddress) {
        if (!checkInit("saveServerPrimaryElementAddress") || isEmpty(deviceId, "saveServerPrimaryElementAddress")) return;
        getPrefs().edit().putInt(PRE_SVR_PRIM_ADDR + normalize(deviceId), primaryAddress).apply();
    }

    public static int getServerPrimaryElementAddress(String deviceId) {
        if (!checkInit("getServerPrimaryElementAddress") || deviceId == null) return -1;
        return getPrefs().getInt(PRE_SVR_PRIM_ADDR + normalize(deviceId), -1);
    }

    // =========================================================================
    // SERVER — SVG element ID
    // =========================================================================

    public static void saveServerSvgElementId(String deviceId, int svgElementId) {
        if (!checkInit("saveServerSvgElementId") || isEmpty(deviceId, "saveServerSvgElementId")) return;
        String key = normalize(deviceId);
        getPrefs().edit().putInt(PRE_SVR_SVG_ID + key, svgElementId).apply();
        Log.d(TAG, "✅ saveServerSvgElementId: " + key + " = " + svgElementId);
    }

    public static int getServerSvgElementId(String deviceId) {
        if (!checkInit("getServerSvgElementId") || deviceId == null) return -1;
        return getPrefs().getInt(PRE_SVR_SVG_ID + normalize(deviceId), -1);
    }

    public static String getKeyBySvgElementIdAndArea(int svgElementId, String areaPrefix) {
        SharedPreferences prefs = getPrefs();
        if (prefs == null) return null;

        if (areaPrefix == null || areaPrefix.trim().isEmpty()) {
            Log.w(TAG, "getKeyBySvgElementIdAndArea: areaPrefix empty — falling back");
            return getKeyBySvgElementId(svgElementId);
        }

        String normalizedArea = normalize(areaPrefix);
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            if (!entry.getKey().startsWith(PRE_SVR_SVG_ID)) continue;
            Object val = entry.getValue();
            if (!(val instanceof Integer) || (Integer) val != svgElementId) continue;
            String key = entry.getKey().substring(PRE_SVR_SVG_ID.length());
            if (key.startsWith(normalizedArea + ":") || key.startsWith(normalizedArea + " ")) {
                Log.d(TAG, "✅ getKeyBySvgElementIdAndArea: svgId=" + svgElementId
                        + " area=" + normalizedArea + " → " + key);
                return key;
            }
        }
        Log.w(TAG, "getKeyBySvgElementIdAndArea: no match for svgId="
                + svgElementId + " area=" + normalizedArea);
        return null;
    }

    @Deprecated
    public static String getKeyBySvgElementId(int svgElementId) {
        SharedPreferences prefs = getPrefs();
        if (prefs == null) return null;
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            if (!entry.getKey().startsWith(PRE_SVR_SVG_ID)) continue;
            Object val = entry.getValue();
            if (val instanceof Integer && (Integer) val == svgElementId) {
                String key = entry.getKey().substring(PRE_SVR_SVG_ID.length());
                Log.w(TAG, "⚠️ getKeyBySvgElementId (deprecated): svgId=" + svgElementId + " → " + key);
                return key;
            }
        }
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

    // =========================================================================
    // SERVER — Node ID (only for 2-ID nodes)
    // =========================================================================

    public static void saveServerNodeId(String deviceId, String nodeId) {
        if (!checkInit("saveServerNodeId") || isEmpty(deviceId, "saveServerNodeId")) return;
        String key = normalize(deviceId);
        if (nodeId != null && !nodeId.isEmpty()) {
            getPrefs().edit().putString(PRE_SVR_NODE_ID + key, nodeId).apply();
            Log.d(TAG, "✅ saveServerNodeId: " + key + " = " + nodeId);
        }
    }

    public static String getServerNodeId(String deviceId) {
        if (!checkInit("getServerNodeId") || deviceId == null) return null;
        return getPrefs().getString(PRE_SVR_NODE_ID + normalize(deviceId), null);
    }

    // =========================================================================
    // SERVER — MAC address
    // =========================================================================

    public static void saveServerMacAddress(String deviceId, String mac) {
        if (!checkInit("saveServerMacAddress") || isEmpty(deviceId, "saveServerMacAddress") || mac == null) return;
        String key = normalize(deviceId);
        getPrefs().edit().putString(PRE_SVR_MAC + key, mac).apply();
        Log.d(TAG, "✅ saveServerMacAddress: " + key + " = " + mac);
    }

    public static String getServerMacAddress(String deviceId) {
        if (!checkInit("getServerMacAddress") || deviceId == null) return null;
        return getPrefs().getString(PRE_SVR_MAC + normalize(deviceId), null);
    }

    // =========================================================================
    // SERVER — Area ID
    // =========================================================================

    public static void saveServerAreaId(String deviceId, String areaId) {
        if (!checkInit("saveServerAreaId") || isEmpty(deviceId, "saveServerAreaId")) return;
        getPrefs().edit().putString(PRE_SVR_AREA_ID + normalize(deviceId), areaId).apply();
    }

    public static String getServerAreaId(String deviceId) {
        if (!checkInit("getServerAreaId") || deviceId == null) return null;
        return getPrefs().getString(PRE_SVR_AREA_ID + normalize(deviceId), null);
    }

    // =========================================================================
    // SERVER — batch save (backward compatibility)
    // =========================================================================

    public static void saveCompleteServerInfo(String deviceId,
                                              int unicastAddress,
                                              int meshElementIndex,
                                              int primaryElementAddress) {
        if (!checkInit("saveCompleteServerInfo") || isEmpty(deviceId, "saveCompleteServerInfo")) return;
        String key = normalize(deviceId);
        SharedPreferences.Editor ed = getPrefs().edit();
        ed.putInt(PRE_SVR_UNICAST   + key, unicastAddress);
        ed.putInt(PRE_SVR_MESH_IDX  + key, meshElementIndex);
        ed.putInt(PRE_SVR_PRIM_ADDR + key, primaryElementAddress);
        ed.apply();
        Log.d(TAG, String.format(
                "✅ saveCompleteServerInfo: key=%s unicast=0x%04X primaryAddr=0x%04X",
                key, unicastAddress, primaryElementAddress));
    }

    // =========================================================================
    // CLIENT — element addresses
    // =========================================================================

    public static void saveClientElementAddress(String deviceId, int index, int address) {
        if (!checkInit("saveClientElementAddress") || isEmpty(deviceId, "saveClientElementAddress")) return;
        String key = normalize(deviceId);
        getPrefs().edit().putInt(PRE_CLIENT_ADDR + key + "_" + index, address).apply();
        Log.d(TAG, "✅ saveClientElementAddress: " + key
                + "[" + index + "] = 0x" + String.format("%04X", address));
    }

    @Deprecated
    public static void saveAll(String deviceId, Map<Integer, Integer> addressMap) {
        saveAllClientElementAddresses(deviceId, addressMap);
    }

    public static void saveAllClientElementAddresses(String deviceId,
                                                     Map<Integer, Integer> addressMap) {
        if (!checkInit("saveAllClientElementAddresses") || isEmpty(deviceId, "saveAllClientElementAddresses")) return;
        if (addressMap == null || addressMap.isEmpty()) {
            Log.w(TAG, "saveAllClientElementAddresses: empty map for " + deviceId);
            return;
        }
        String key = normalize(deviceId);
        SharedPreferences.Editor editor = getPrefs().edit();
        for (Map.Entry<Integer, Integer> e : addressMap.entrySet()) {
            editor.putInt(PRE_CLIENT_ADDR + key + "_" + e.getKey(), e.getValue());
        }
        editor.apply();
        Log.d(TAG, "✅ saveAllClientElementAddresses: "
                + addressMap.size() + " elements for " + key);
    }

    public static int getClientAddress(String deviceId, int index) {
        if (!checkInit("getClientAddress") || deviceId == null) return -1;
        return getPrefs().getInt(PRE_CLIENT_ADDR + normalize(deviceId) + "_" + index, -1);
    }

    // =========================================================================
    // CLIENT → SERVER mapping
    // =========================================================================

    public static void saveClientToServerMapping(String clientDeviceId,
                                                 int elementIndex,
                                                 String serverDeviceId) {
        if (!checkInit("saveClientToServerMapping")
                || isEmpty(clientDeviceId, "saveClientToServerMapping")
                || isEmpty(serverDeviceId, "saveClientToServerMapping")) return;

        String key = PRE_CLIENT_TO_SVR + normalize(clientDeviceId) + "_" + elementIndex;
        getPrefs().edit().putString(key, normalize(serverDeviceId)).apply();
        Log.d(TAG, "✅ saveClientToServerMapping: "
                + normalize(clientDeviceId) + "[" + elementIndex + "] → " + normalize(serverDeviceId));
    }

    public static String getServerKeyForClient(String clientDeviceId, int elementIndex) {
        if (!checkInit("getServerKeyForClient") || clientDeviceId == null) return null;
        String key = PRE_CLIENT_TO_SVR + normalize(clientDeviceId) + "_" + elementIndex;
        return getPrefs().getString(key, null);
    }

    // =========================================================================
    // PROVISIONED DEVICES SET
    // =========================================================================

    public static boolean isProvisioned(String deviceId) {
        if (!checkInit("isProvisioned") || deviceId == null) return false;
        return getServerUnicastAddress(deviceId) != -1;
    }

    public static Set<String> getProvisionedKeys() {
        if (!checkInit("getProvisionedKeys")) return new HashSet<>();
        Set<String> raw = getPrefs().getStringSet(KEY_PROVISIONED, new HashSet<>());
        return new HashSet<>(raw);
    }

    public static void markProvisioned(String deviceId) {
        if (!checkInit("markProvisioned") || isEmpty(deviceId, "markProvisioned")) return;
        String key = normalize(deviceId);
        Set<String> current = new HashSet<>(
                getPrefs().getStringSet(KEY_PROVISIONED, new HashSet<>()));
        if (current.add(key)) {
            getPrefs().edit().putStringSet(KEY_PROVISIONED, current).apply();
            Log.d(TAG, "✅ markProvisioned: " + key);
        }
    }

    public static void unmarkProvisioned(String deviceId) {
        if (!checkInit("unmarkProvisioned") || deviceId == null) return;
        String key = normalize(deviceId);
        Set<String> current = new HashSet<>(
                getPrefs().getStringSet(KEY_PROVISIONED, new HashSet<>()));
        if (current.remove(key)) {
            getPrefs().edit().putStringSet(KEY_PROVISIONED, current).apply();
            Log.d(TAG, "✅ unmarkProvisioned: " + key);
        } else {
            Log.w(TAG, "⚠️ unmarkProvisioned: not found → " + key);
        }
    }

    // =========================================================================
    // CLEAR
    // =========================================================================

    public static void clearDevice(String deviceId) {
        if (!checkInit("clearDevice") || isEmpty(deviceId, "clearDevice")) return;

        String key = normalize(deviceId);
        SharedPreferences        prefs  = getPrefs();
        SharedPreferences.Editor editor = prefs.edit();

        editor.remove(PRE_SVR_UNICAST   + key);
        editor.remove(PRE_SVR_MESH_IDX  + key);
        editor.remove(PRE_SVR_PRIM_ADDR + key);
        editor.remove(PRE_SVR_SVG_ID    + key);
        editor.remove(PRE_SVR_AREA_ID   + key);
        editor.remove(PRE_SVR_MAC       + key);
        editor.remove(PRE_SVR_NODE_ID   + key);

        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            String k = entry.getKey();
            if (!k.startsWith(PRE_CLIENT_TO_SVR)) continue;
            if (key.equals(normalize(String.valueOf(entry.getValue())))) {
                editor.remove(k);
                Log.d(TAG, "🧹 clearDevice: removed client_to_server → " + k);
            }
        }

        Set<String> provisioned = new HashSet<>(
                prefs.getStringSet(KEY_PROVISIONED, new HashSet<>()));
        provisioned.remove(key);
        editor.putStringSet(KEY_PROVISIONED, provisioned);

        editor.apply();
        Log.d(TAG, "✅ clearDevice complete: key='" + key + "'");
    }

    @Deprecated
    public static void clearServerData(String serverStoreKey) {
        clearDevice(serverStoreKey);
    }

    // =========================================================================
    // DEBUG
    // =========================================================================

    public static void dumpAll() {
        SharedPreferences prefs = getPrefs();
        if (prefs == null) { Log.e(TAG, "dumpAll: not initialised"); return; }
        Log.d(TAG, "══════════════ ClientServerElementStore DUMP ══════════════");
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            Log.d(TAG, "  " + entry.getKey() + " = " + entry.getValue());
        }
        Log.d(TAG, "═══════════════════════════════════════════════════════════");
    }

    public static String getKeyByUnicastAddress(int unicastAddress) {
        if (!checkInit("getKeyByUnicastAddress") || unicastAddress == -1) return null;
        SharedPreferences prefs = getPrefs();
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            String k = entry.getKey();
            if (!k.startsWith(PRE_SVR_UNICAST)) continue;
            Object val = entry.getValue();
            if (val instanceof Integer && (Integer) val == unicastAddress) {
                return k.substring(PRE_SVR_UNICAST.length());
            }
        }
        return null;
    }
}