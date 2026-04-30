package no.nordicsemi.android.swaromapmesh.viewmodels;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ============================================================
 * ClientServerElementStore — Single Source of Truth
 * ============================================================
 *
 * RULE: Koi bhi class directly mesh_prefs mein mat likhe.
 *       Sabka data sirf is class ke through aayega aur jaayega.
 *
 * Key format: ALWAYS lowercase  e.g. "casting:relay node1"
 *
 * Key prefixes:
 *   server_unicast_<key>            → int   unicast address
 *   server_svg_element_id_<key>     → int   svg element id
 *   server_primary_addr_<key>       → int   primary element address
 *   server_mesh_element_index_<key> → int   mesh element index
 *   server_area_id_<key>            → String area id
 *   mac_<key>                       → String mac address
 *   element_addr_<key>_<index>      → int   client element address (0-based)
 *   client_to_server_<key>_<index>  → String server store key
 *   provisioned_devices             → Set<String> all provisioned device keys (lowercase)
 * ============================================================
 */
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

    /** Public accessor for observers (e.g. SharedViewModel) */
    public static SharedPreferences getPrefsPublic() {
        return getPrefs();
    }

    // ── Guards ────────────────────────────────────────────────────────────────

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

    /**
     * Normalize any deviceId/key to lowercase trimmed.
     * ALWAYS call this before building a prefs key.
     * e.g. "Casting:Relay Node1" → "casting:relay node1"
     */
    public static String normalize(String key) {
        if (key == null) return "";
        return key.trim().toLowerCase();
    }

    // =========================================================================
    // ✅ MASTER SAVE — single call to persist a complete device record

    // =========================================================================
    public static void saveDevice(String deviceId,
                                  int unicastAddr,
                                  int svgElementId,
                                  String mac) {
        if (!checkInit("saveDevice") || isEmpty(deviceId, "saveDevice")) return;

        String key = normalize(deviceId);
        SharedPreferences.Editor ed = getPrefs().edit();

        ed.putInt(PRE_SVR_UNICAST   + key, unicastAddr);
        ed.putInt(PRE_SVR_SVG_ID    + key, svgElementId);
        ed.putInt(PRE_SVR_PRIM_ADDR + key, unicastAddr);   // primary = unicast by default

        if (mac != null && !mac.isEmpty()) {
            ed.putString(PRE_SVR_MAC + key, mac);
        }

        // ── Add to provisioned set (normalized) ───────────────────────────
        Set<String> provisioned = new HashSet<>(
                getPrefs().getStringSet(KEY_PROVISIONED, new HashSet<>()));
        provisioned.add(key);
        ed.putStringSet(KEY_PROVISIONED, provisioned);

        ed.apply();

        Log.d(TAG, "✅ saveDevice: key=" + key
                + " unicast=0x" + String.format("%04X", unicastAddr)
                + " svgId=" + svgElementId
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

    /**
     * Area-aware reverse lookup — svgElementId + areaPrefix → store key.
     *
     * e.g. svgElementId=1, areaPrefix="casting" → "casting:relay node1"
     *
     * Same elementId multiple areas mein ho sakta hai (e.g. smt:Relay Node1
     * aur casting:Relay Node1 dono ka svgId=1).
     * areaPrefix pass karo taaki correct key mile.
     */
    public static String getKeyBySvgElementIdAndArea(int svgElementId, String areaPrefix) {
        SharedPreferences prefs = getPrefs();
        if (prefs == null) return null;

        if (areaPrefix == null || areaPrefix.trim().isEmpty()) {
            Log.w(TAG, "getKeyBySvgElementIdAndArea: areaPrefix empty — falling back to ambiguous lookup");
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

    /**
     * @deprecated AMBIGUOUS — same elementId can exist in multiple areas.
     * Use {@link #getKeyBySvgElementIdAndArea(int, String)} instead.
     */
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
    // SERVER — batch save (kept for backward compatibility)
    // Prefer saveDevice() for new code.
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
    // CLIENT — element addresses  (0-based index)
    // =========================================================================

    /**
     * Save one client element address.
     * @param deviceId   client svg device id  e.g. "VCRI:SW-CN01-AA"
     * @param index      0-based element index
     * @param address    BLE mesh element address
     */
    public static void saveClientElementAddress(String deviceId, int index, int address) {
        if (!checkInit("saveClientElementAddress") || isEmpty(deviceId, "saveClientElementAddress")) return;
        String key = normalize(deviceId);
        getPrefs().edit().putInt(PRE_CLIENT_ADDR + key + "_" + index, address).apply();
        Log.d(TAG, "✅ saveClientElementAddress: " + key
                + "[" + index + "] = 0x" + String.format("%04X", address));
    }

    /**
     * @deprecated Renamed to {@link #saveAllClientElementAddresses(String, Map)}.
     * Kept so NrfMeshRepository.saveClientElementAddresses() compiles without change.
     */
    @Deprecated
    public static void saveAll(String deviceId, Map<Integer, Integer> addressMap) {
        saveAllClientElementAddresses(deviceId, addressMap);
    }

    /**
     * Save all client element addresses in one atomic write.
     * @param deviceId       client svg device id
     * @param addressMap     map of 0-based index → element address
     */
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

    /**
     * Check if a device is provisioned.
     * Normalizes the key before lookup — no case-mismatch bugs.
     */
    public static boolean isProvisioned(String deviceId) {
        if (!checkInit("isProvisioned") || deviceId == null) return false;
        // Primary check: unicast address exists → device is provisioned
        return getServerUnicastAddress(deviceId) != -1;
    }

    public static Set<String> getProvisionedKeys() {
        if (!checkInit("getProvisionedKeys")) return new HashSet<>();
        Set<String> raw = getPrefs().getStringSet(KEY_PROVISIONED, new HashSet<>());
        return new HashSet<>(raw); // defensive copy
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
    // CLEAR — full cleanup on node delete
    // =========================================================================
    public static void clearDevice(String deviceId) {
        if (!checkInit("clearDevice") || isEmpty(deviceId, "clearDevice")) return;

        String key = normalize(deviceId);
        SharedPreferences       prefs  = getPrefs();
        SharedPreferences.Editor editor = prefs.edit();

        // ── Step 1: Remove all server-specific keys ───────────────────────────
        editor.remove(PRE_SVR_UNICAST   + key);
        editor.remove(PRE_SVR_MESH_IDX  + key);
        editor.remove(PRE_SVR_PRIM_ADDR + key);
        editor.remove(PRE_SVR_SVG_ID    + key);
        editor.remove(PRE_SVR_AREA_ID   + key);
        editor.remove(PRE_SVR_MAC       + key);

        // ── Step 2: Remove client_to_server_ mappings pointing to this server ─
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            String k = entry.getKey();
            if (!k.startsWith(PRE_CLIENT_TO_SVR)) continue;
            if (key.equals(normalize(String.valueOf(entry.getValue())))) {
                editor.remove(k);
                Log.d(TAG, "🧹 clearDevice: removed client_to_server → " + k);
            }
        }

        // ── Step 3: Remove from provisioned set ───────────────────────────────
        Set<String> provisioned = new HashSet<>(
                prefs.getStringSet(KEY_PROVISIONED, new HashSet<>()));
        provisioned.remove(key);
        editor.putStringSet(KEY_PROVISIONED, provisioned);

        editor.apply();
        Log.d(TAG, "✅ clearDevice complete: key='" + key + "'");
    }

    /**
     * @deprecated Renamed to {@link #clearDevice(String)}.
     * Kept for backward compatibility — will be removed in future.
     */
    @Deprecated
    public static void clearServerData(String serverStoreKey) {
        clearDevice(serverStoreKey);
    }

    // =========================================================================
    // DEBUG — dump all store entries to logcat
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
}