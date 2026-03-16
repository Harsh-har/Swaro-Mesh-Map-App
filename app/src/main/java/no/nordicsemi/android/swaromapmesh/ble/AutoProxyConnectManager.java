package no.nordicsemi.android.swaromapmesh.ble;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AutoProxyConnectManager
 *
 * Performs a background BLE scan for MESH_PROXY_UUID devices.
 * - If a device is found with RSSI > -85 dBm, connects INSTANTLY (no wait).
 * - Otherwise waits for full scan window and picks the best RSSI.
 */
public class AutoProxyConnectManager {

    private static final String TAG                          = "AutoProxyConnectMgr";
    private static final long   DEFAULT_SCAN_WINDOW          = 5000L;

    // ✅ Instant connect: if RSSI is better than this, connect immediately
    private static final int    RSSI_INSTANT_CONNECT_THRESHOLD = -85;

    public interface BestProxyCallback {
        /** Called on the main thread. mac is null if nothing found. */
        void onBestProxyFound(String mac);
    }

    private final Context              mContext;
    private final Map<String, Integer> mRssiMap = new HashMap<>();

    private BluetoothLeScanner mScanner;
    private ScanCallback        mScanCallback;
    private final Handler       mHandler  = new Handler(Looper.getMainLooper());
    private boolean             mScanning = false;

    // Guard to ensure callback is only fired once
    private boolean mCallbackFired = false;

    public AutoProxyConnectManager(Context context) {
        // Use application context to avoid leaking Activity
        mContext = context.getApplicationContext();
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Start a background BLE scan and return the best proxy MAC.
     *
     * @param knownMacs    Upper-case MACs of provisioned nodes. Null = accept any proxy.
     * @param scanWindowMs Scan duration in ms (fallback). 0 = use default 5 s.
     * @param callback     Delivers result on main thread.
     */
    public void findBestProxy(Set<String> knownMacs,
                              long scanWindowMs,
                              BestProxyCallback callback) {
        if (mScanning) {
            Log.w(TAG, "Already scanning — ignoring duplicate request");
            return;
        }

        final BluetoothManager bluetoothManager =
                (BluetoothManager) mContext.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager == null) {
            Log.w(TAG, "BluetoothManager unavailable");
            callback.onBestProxyFound(null);
            return;
        }

        final BluetoothAdapter adapter = bluetoothManager.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            Log.w(TAG, "Bluetooth not available or disabled");
            callback.onBestProxyFound(null);
            return;
        }

        mScanner = adapter.getBluetoothLeScanner();
        if (mScanner == null) {
            Log.w(TAG, "BluetoothLeScanner unavailable");
            callback.onBestProxyFound(null);
            return;
        }

        mRssiMap.clear();
        mScanning      = true;
        mCallbackFired = false;

        // Filter: only Mesh Proxy service UUID
        final List<ScanFilter> filters = new ArrayList<>();
        filters.add(new ScanFilter.Builder()
                .setServiceUuid(new ParcelUuid(BleMeshManager.MESH_PROXY_UUID))
                .build());

        final ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setReportDelay(0)
                .build();

        mScanCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                // If callback already fired (instant connect), ignore further results
                if (mCallbackFired) return;

                final String mac = result.getDevice().getAddress();
                if (mac == null) return;

                final String upperMac = mac.toUpperCase();

                // Only track known provisioned nodes
                if (knownMacs != null && !knownMacs.isEmpty()
                        && !knownMacs.contains(upperMac)) {
                    return;
                }

                final int rssi = result.getRssi();

                // Update best RSSI for this MAC
                final Integer prev = mRssiMap.get(upperMac);
                if (prev == null || rssi > prev) {
                    mRssiMap.put(upperMac, rssi);
                    Log.d(TAG, "RSSI update: " + upperMac + " → " + rssi + " dBm");
                }

                // ✅ INSTANT CONNECT: RSSI better than threshold → connect immediately
                if (rssi > RSSI_INSTANT_CONNECT_THRESHOLD) {
                    Log.i(TAG, "⚡ Instant connect! " + upperMac + " @ " + rssi + " dBm (threshold: " + RSSI_INSTANT_CONNECT_THRESHOLD + ")");
                    mCallbackFired = true;
                    mHandler.removeCallbacksAndMessages(null); // cancel fallback timer
                    stopScan();
                    mHandler.post(() -> callback.onBestProxyFound(upperMac));
                }
            }

            @Override
            public void onScanFailed(int errorCode) {
                Log.e(TAG, "BLE scan failed, error code: " + errorCode);
                mScanning = false;
                if (!mCallbackFired) {
                    mCallbackFired = true;
                    mHandler.post(() -> callback.onBestProxyFound(null));
                }
            }
        };

        try {
            mScanner.startScan(filters, settings, mScanCallback);
            Log.d(TAG, "Background proxy scan started. Known MACs: "
                    + (knownMacs != null ? knownMacs.size() : "any")
                    + " | Instant threshold: " + RSSI_INSTANT_CONNECT_THRESHOLD + " dBm");
        } catch (SecurityException e) {
            Log.e(TAG, "Missing BLUETOOTH_SCAN permission: " + e.getMessage());
            mScanning = false;
            if (!mCallbackFired) {
                mCallbackFired = true;
                mHandler.post(() -> callback.onBestProxyFound(null));
            }
            return;
        }

        // Fallback: if no device met threshold in time, pick best found so far
        final long window = scanWindowMs > 0 ? scanWindowMs : DEFAULT_SCAN_WINDOW;
        mHandler.postDelayed(() -> {
            if (mCallbackFired) return; // instant connect already handled it
            stopScan();
            final String best = pickBestMac();
            mCallbackFired = true;
            if (best != null) {
                Log.i(TAG, "⏱ Fallback best proxy: " + best + " @ " + mRssiMap.get(best) + " dBm");
            } else {
                Log.d(TAG, "No suitable proxy found in scan window");
            }
            callback.onBestProxyFound(best);
        }, window);
    }

    /** Cancel any running scan immediately. Safe to call multiple times. */
    public void stop() {
        mHandler.removeCallbacksAndMessages(null);
        stopScan();
    }

    // ------------------------------------------------------------------
    // Private helpers
    // ------------------------------------------------------------------

    private void stopScan() {
        if (!mScanning) return;
        mScanning = false;
        if (mScanner != null && mScanCallback != null) {
            try {
                mScanner.stopScan(mScanCallback);
                Log.d(TAG, "Background proxy scan stopped");
            } catch (SecurityException | IllegalStateException e) {
                Log.w(TAG, "stopScan error: " + e.getMessage());
            }
        }
        mScanCallback = null;
    }

    /** Returns the MAC with the highest RSSI. Null if map is empty. */
    private String pickBestMac() {
        String bestMac  = null;
        int    bestRssi = Integer.MIN_VALUE;
        for (Map.Entry<String, Integer> entry : mRssiMap.entrySet()) {
            if (entry.getValue() > bestRssi) {
                bestRssi = entry.getValue();
                bestMac  = entry.getKey();
            }
        }
        return bestMac;
    }
}