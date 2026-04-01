package no.nordicsemi.android.swaromapmesh.viewmodels;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.Map;

/**
 * Static helper to persist and retrieve Generic On Off Client element addresses.
 *
 * Key format:
 *   Client element address : "element_addr_<svgDeviceId>_<index>"
 *   Server unicast address : "server_unicast_<svgDeviceId>"  (NEW)
 *
 * Uses the same SharedPreferences file ("mesh_prefs") as SharedViewModel
 */
public final class ClientElementStore {

    private static final String TAG    = "ClientElementStore";
    private static final String PREFS  = "mesh_prefs";
    private static final String PREFIX = "element_addr_";

    // ✅ NEW PREFIX (non-breaking)
    private static final String PREFIX_SVR_UCAST = "server_unicast_";

    private static SharedPreferences sPrefs;

    private ClientElementStore() {}

    // =========================================================================
    // Init
    // =========================================================================

    public static void init(Context appContext) {
        if (sPrefs == null) {
            sPrefs = appContext.getApplicationContext()
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            Log.d(TAG, "Initialised with prefs file: " + PREFS);
        }
    }

    // =========================================================================
    // Write (Client Element Addresses)
    // =========================================================================

    public static void saveAll(String svgDeviceId,
                               Map<Integer, Integer> addressMap) {
        if (sPrefs == null) {
            Log.e(TAG, "ClientElementStore not initialised — call init(context) first");
            return;
        }
        if (svgDeviceId == null || svgDeviceId.isEmpty()) {
            Log.e(TAG, "saveAll: svgDeviceId is null/empty — skip");
            return;
        }
        if (addressMap == null || addressMap.isEmpty()) {
            Log.w(TAG, "saveAll: empty addressMap for " + svgDeviceId);
            return;
        }

        SharedPreferences.Editor editor = sPrefs.edit();
        for (Map.Entry<Integer, Integer> entry : addressMap.entrySet()) {
            String key = PREFIX + svgDeviceId + "_" + entry.getKey();
            editor.putInt(key, entry.getValue());
            Log.d(TAG, "  save: " + key + " = 0x"
                    + String.format("%04X", entry.getValue()));
        }
        editor.apply();

        Log.d(TAG, "✅ saveAll complete: " + addressMap.size()
                + " elements for device=" + svgDeviceId);
    }

    // =========================================================================
    // Read (Client Element Address)
    // =========================================================================

    public static int get(String svgDeviceId, int index) {
        if (sPrefs == null) {
            Log.e(TAG, "ClientElementStore not initialised");
            return -1;
        }
        if (svgDeviceId == null || svgDeviceId.isEmpty()) return -1;

        String key = PREFIX + svgDeviceId + "_" + index;
        int address = sPrefs.getInt(key, -1);

        if (address != -1) {
            Log.d(TAG, "get: device=" + svgDeviceId
                    + " index=" + index
                    + " address=0x" + String.format("%04X", address));
        }
        return address;
    }

    public static boolean hasData(String svgDeviceId) {
        if (sPrefs == null || svgDeviceId == null) return false;
        return sPrefs.contains(PREFIX + svgDeviceId + "_1");
    }

    // =========================================================================
    // ✅ NEW: SERVER UNICAST ADDRESS (WRITE)
    // =========================================================================

    public static void saveServerUnicastAddress(String svgDeviceId, int unicastAddress) {
        if (sPrefs == null) {
            Log.e(TAG, "saveServerUnicastAddress: not initialised");
            return;
        }
        if (svgDeviceId == null || svgDeviceId.isEmpty()) {
            Log.e(TAG, "saveServerUnicastAddress: svgDeviceId null/empty");
            return;
        }

        String key = PREFIX_SVR_UCAST + svgDeviceId;
        sPrefs.edit().putInt(key, unicastAddress).apply();

        Log.d(TAG, "Saved server unicast: device=" + svgDeviceId
                + " addr=0x" + String.format("%04X", unicastAddress));
    }

    // =========================================================================
    // ✅ NEW: SERVER UNICAST ADDRESS (READ)
    // =========================================================================

    public static int getServerUnicastAddress(String svgDeviceId) {
        if (sPrefs == null) {
            Log.e(TAG, "getServerUnicastAddress: not initialised");
            return -1;
        }
        if (svgDeviceId == null || svgDeviceId.isEmpty()) return -1;

        String key = PREFIX_SVR_UCAST + svgDeviceId;
        int address = sPrefs.getInt(key, -1);

        if (address != -1) {
            Log.d(TAG, "getServerUnicastAddress: device=" + svgDeviceId
                    + " addr=0x" + String.format("%04X", address));
        }

        return address;
    }

    // =========================================================================
    // Clear
    // =========================================================================

    public static void clearDevice(String svgDeviceId) {
        if (sPrefs == null || svgDeviceId == null) return;

        SharedPreferences.Editor editor = sPrefs.edit();

        for (int i = 1; i <= 40; i++) {
            editor.remove(PREFIX + svgDeviceId + "_" + i);
        }

        // ✅ ALSO clear server unicast (safe addition)
        editor.remove(PREFIX_SVR_UCAST + svgDeviceId);

        editor.apply();

        Log.d(TAG, "Cleared element addresses for device: " + svgDeviceId);
    }

    public static void clearAll() {
        if (sPrefs == null) return;

        SharedPreferences.Editor editor = sPrefs.edit();

        for (String key : sPrefs.getAll().keySet()) {
            if (key.startsWith(PREFIX) || key.startsWith(PREFIX_SVR_UCAST)) {
                editor.remove(key);
            }
        }

        editor.apply();
        Log.d(TAG, "Cleared ALL client + server data");
    }
}