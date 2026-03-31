package no.nordicsemi.android.swaromapmesh.viewmodels;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.Map;

/**
 * Static helper to persist and retrieve Generic On Off Client element addresses.
 *
 * Key format:  "element_addr_<svgDeviceId>_<index>"   (index is 1-based, up to 40)
 *
 * Uses the same SharedPreferences file ("mesh_prefs") as SharedViewModel
 * so both can read/write the same keys without conflict.
 *
 * INITIALISE ONCE at app startup — call ClientElementStore.init(appContext)
 * in your Application.onCreate() or Hilt AppModule @Provides method.
 */
public final class ClientElementStore {

    private static final String TAG    = "ClientElementStore";
    private static final String PREFS  = "mesh_prefs";       // same file as SharedViewModel
    private static final String PREFIX = "element_addr_";    // same prefix as SharedViewModel

    private static SharedPreferences sPrefs;

    private ClientElementStore() {}

    // =========================================================================
    // Init
    // =========================================================================

    /**
     * Must be called once before any other method.
     * Place this call in your Application class:
     *
     *   ClientElementStore.init(this);
     *
     * Or in a Hilt @Provides @Singleton method that receives @ApplicationContext.
     */
    public static void init(Context appContext) {
        if (sPrefs == null) {
            sPrefs = appContext.getApplicationContext()
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            Log.d(TAG, "Initialised with prefs file: " + PREFS);
        }
    }

    // =========================================================================
    // Write
    // =========================================================================

    /**
     * Save all element addresses for one Generic On Off Client device.
     *
     * Called by NrfMeshRepository.onAllModelsBindComplete() after every
     * AppKey bind on that node has succeeded.
     *
     * @param svgDeviceId  The node's name / SVG icon ID  (e.g. "switch_01")
     * @param addressMap   1-based element index → unicast address
     */
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
    // Read
    // =========================================================================

    /**
     * Read back a single element address for a client device.
     *
     * @param svgDeviceId  node name / SVG icon ID
     * @param index        1-based element index  (1 = primary element)
     * @return unicast address, or -1 if not found / not yet provisioned
     */
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

    /**
     * Check whether any element addresses have been saved for this device.
     */
    public static boolean hasData(String svgDeviceId) {
        if (sPrefs == null || svgDeviceId == null) return false;
        // Check at least element index 1
        return sPrefs.contains(PREFIX + svgDeviceId + "_1");
    }

    // =========================================================================
    // Clear
    // =========================================================================

    /**
     * Remove all stored element addresses for a specific device.
     * Call this when a node is reset/deleted from the mesh.
     *
     * @param svgDeviceId  node name / SVG icon ID
     */
    public static void clearDevice(String svgDeviceId) {
        if (sPrefs == null || svgDeviceId == null) return;
        SharedPreferences.Editor editor = sPrefs.edit();
        for (int i = 1; i <= 40; i++) {
            editor.remove(PREFIX + svgDeviceId + "_" + i);
        }
        editor.apply();
        Log.d(TAG, "Cleared element addresses for device: " + svgDeviceId);
    }

    /**
     * Remove ALL stored element address data for all devices.
     * Use with caution — e.g. on full mesh network reset.
     */
    public static void clearAll() {
        if (sPrefs == null) return;
        SharedPreferences allEntries = sPrefs;
        SharedPreferences.Editor editor = sPrefs.edit();
        for (String key : allEntries.getAll().keySet()) {
            if (key.startsWith(PREFIX)) {
                editor.remove(key);
            }
        }
        editor.apply();
        Log.d(TAG, "Cleared ALL client element address data");
    }
}