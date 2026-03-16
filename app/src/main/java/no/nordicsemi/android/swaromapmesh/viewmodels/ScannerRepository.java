package no.nordicsemi.android.swaromapmesh.viewmodels;

import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.LocationManager;
import android.os.ParcelUuid;
import android.util.Log;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.inject.Inject;

import dagger.hilt.android.qualifiers.ApplicationContext;
import no.nordicsemi.android.swaromapmesh.MeshManagerApi;
import no.nordicsemi.android.swaromapmesh.MeshNetwork;
import no.nordicsemi.android.swaromapmesh.ble.BleMeshManager;
import no.nordicsemi.android.swaromapmesh.transport.ProvisionedMeshNode;
import no.nordicsemi.android.swaromapmesh.utils.Utils;
import no.nordicsemi.android.support.v18.scanner.BluetoothLeScannerCompat;
import no.nordicsemi.android.support.v18.scanner.ScanCallback;
import no.nordicsemi.android.support.v18.scanner.ScanFilter;
import no.nordicsemi.android.support.v18.scanner.ScanRecord;
import no.nordicsemi.android.support.v18.scanner.ScanResult;
import no.nordicsemi.android.support.v18.scanner.ScanSettings;

/**
 * Repository for scanning Bluetooth Mesh devices.
 * Fixed: stopScan() no longer clears results; connected flag prevents scan restart after proxy connect.
 */
public class ScannerRepository {

    private static final String TAG = ScannerRepository.class.getSimpleName();

    private final Context mContext;
    private final MeshManagerApi mMeshManagerApi;

    private final ScannerLiveData mScannerLiveData;
    private final ScannerStateLiveData mScannerStateLiveData;

    private UUID mFilterUuid;

    // Proxy scan callback for auto-connect
    private ProxyScanCallback mProxyScanCallback;

    // ✅ FIX: Flag to prevent scan restart after proxy connection is established
    private boolean mIsConnected = false;

    // ------------------------------------------------------------------------
    // Scan Callback
    // ------------------------------------------------------------------------
    private final ScanCallback mScanCallbacks = new ScanCallback() {

        @Override
        public void onScanResult(final int callbackType, @NonNull final ScanResult result) {
            try {
                // ✅ FIX: If already connected, ignore all scan results to prevent interference
                if (mIsConnected) return;

                if (mFilterUuid == null) return;

                // ---------------- PROVISIONING SCAN ----------------
                if (mFilterUuid.equals(BleMeshManager.MESH_PROVISIONING_UUID)) {
                    if (Utils.isLocationRequired(mContext)
                            && !Utils.isLocationEnabled(mContext)) {
                        Utils.markLocationNotRequired(mContext);
                    }
                    updateScannerLiveData(result);
                }

                // ---------------- PROXY SCAN ----------------
                else if (mFilterUuid.equals(BleMeshManager.MESH_PROXY_UUID)) {

                    final byte[] serviceData =
                            Utils.getServiceData(result, BleMeshManager.MESH_PROXY_UUID);

                    if (serviceData == null || mMeshManagerApi == null) return;

                    boolean matched = false;

                    if (mMeshManagerApi.isAdvertisingWithNetworkIdentity(serviceData)) {
                        matched = mMeshManagerApi.networkIdMatches(serviceData);
                    } else if (mMeshManagerApi.isAdvertisedWithNodeIdentity(serviceData)) {
                        matched = checkIfNodeIdentityMatches(serviceData);
                    }

                    if (matched) {
                        updateScannerLiveData(result);

                        // Auto proxy callback
                        if (mProxyScanCallback != null) {
                            mProxyScanCallback.onProxyFound(result);
                        }
                    }
                }

            } catch (Exception ex) {
                Log.e(TAG, "Scan error", ex);
            }
        }

        @Override
        public void onBatchScanResults(@NonNull final List<ScanResult> results) {
            // Not used
        }

        @Override
        public void onScanFailed(final int errorCode) {
            Log.e(TAG, "Scan failed with error code: " + errorCode);
            mScannerStateLiveData.scanningStopped();
        }
    };

    // ------------------------------------------------------------------------
    // Broadcast Receivers
    // ------------------------------------------------------------------------
    private final BroadcastReceiver mLocationProviderChangedReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(final Context context, final Intent intent) {
                    final boolean enabled = Utils.isLocationEnabled(context);
                    mScannerStateLiveData.setLocationEnabled(enabled);
                }
            };

    private final BroadcastReceiver mBluetoothStateBroadcastReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(final Context context, final Intent intent) {

                    final int state =
                            intent.getIntExtra(BluetoothAdapter.EXTRA_STATE,
                                    BluetoothAdapter.STATE_OFF);

                    final int previousState =
                            intent.getIntExtra(BluetoothAdapter.EXTRA_PREVIOUS_STATE,
                                    BluetoothAdapter.STATE_OFF);

                    switch (state) {
                        case BluetoothAdapter.STATE_ON:
                            mScannerStateLiveData.bluetoothEnabled();
                            break;

                        case BluetoothAdapter.STATE_TURNING_OFF:
                        case BluetoothAdapter.STATE_OFF:
                            if (previousState != BluetoothAdapter.STATE_TURNING_OFF
                                    && previousState != BluetoothAdapter.STATE_OFF) {
                                stopScan();
                                mScannerStateLiveData.bluetoothDisabled();
                            }
                            break;
                    }
                }
            };

    // ------------------------------------------------------------------------
    // Constructor
    // ------------------------------------------------------------------------
    @Inject
    public ScannerRepository(
            @NonNull @ApplicationContext final Context context,
            @NonNull final MeshManagerApi meshManagerApi) {

        this.mContext = context;
        this.mMeshManagerApi = meshManagerApi;

        mScannerStateLiveData =
                new ScannerStateLiveData(Utils.isBleEnabled(),
                        Utils.isLocationEnabled(context));

        mScannerLiveData = new ScannerLiveData();
    }

    // ------------------------------------------------------------------------
    // Public getters
    // ------------------------------------------------------------------------
    public ScannerStateLiveData getScannerState() {
        return mScannerStateLiveData;
    }

    public ScannerLiveData getScannerResults() {
        return mScannerLiveData;
    }

    // ------------------------------------------------------------------------
    // Scan control
    // ------------------------------------------------------------------------
    public void startScan(@NonNull final UUID filterUuid) {
        // ✅ FIX: Do not start scan if already connected to proxy
        if (mIsConnected) {
            Log.d(TAG, "startScan() ignored — already connected to proxy");
            return;
        }

        mFilterUuid = filterUuid;

        if (mScannerStateLiveData.isScanning()) return;

        mScannerStateLiveData.scanningStarted();

        final ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setReportDelay(0)
                .setUseHardwareFilteringIfSupported(false)
                .build();

        final List<ScanFilter> filters = new ArrayList<>();
        filters.add(new ScanFilter.Builder()
                .setServiceUuid(new ParcelUuid(filterUuid))
                .build());

        BluetoothLeScannerCompat.getScanner()
                .startScan(filters, settings, mScanCallbacks);

        Log.d(TAG, "Scan started with UUID: " + filterUuid);
    }

    /**
     * Start proxy scan with a callback for when a proxy node is found.
     */
    public void startProxyScan(@NonNull final ProxyScanCallback callback) {
        mProxyScanCallback = callback;
        startScan(BleMeshManager.MESH_PROXY_UUID);
    }

    /**
     * ✅ FIX: stopScan() does NOT clear scanner results anymore.
     * Results are preserved so auto-connect can still find the device.
     * Call clearResults() explicitly if you want to wipe the list.
     */
    public void stopScan() {
        try {
            BluetoothLeScannerCompat.getScanner().stopScan(mScanCallbacks);
        } catch (Exception e) {
            Log.e(TAG, "stopScan error: " + e.getMessage());
        }
        mScannerStateLiveData.scanningStopped();
        mProxyScanCallback = null;
        Log.d(TAG, "Scan stopped. Results preserved.");
    }

    /**
     * Explicitly clear scan results. Call this only when starting a fresh scan session.
     */
    public void clearResults() {
        mScannerLiveData.clear();
        Log.d(TAG, "Scanner results cleared.");
    }

    // ------------------------------------------------------------------------
    // ✅ Connection state management
    // ------------------------------------------------------------------------

    /**
     * Call this when proxy connection is successfully established.
     * Prevents scan observer from restarting scan and disconnecting the proxy.
     */
    public void onProxyConnected() {
        mIsConnected = true;
        stopScan();
        Log.d(TAG, "Proxy connected — scanning suppressed.");
    }

    /**
     * Call this when proxy is disconnected (e.g. user navigates back).
     * Re-enables scanning.
     */
    public void onProxyDisconnected() {
        mIsConnected = false;
        Log.d(TAG, "Proxy disconnected — scanning re-enabled.");
    }

    /**
     * Returns true if currently connected to a proxy node.
     */
    public boolean isConnected() {
        return mIsConnected;
    }

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------
    private void updateScannerLiveData(final ScanResult result) {
        final ScanRecord scanRecord = result.getScanRecord();
        if (scanRecord != null && scanRecord.getBytes() != null) {

            final byte[] beaconData =
                    mMeshManagerApi.getMeshBeaconData(scanRecord.getBytes());

            if (beaconData != null) {
                mScannerLiveData.deviceDiscovered(
                        result,
                        mMeshManagerApi.getMeshBeacon(beaconData));
            } else {
                mScannerLiveData.deviceDiscovered(result);
            }

            mScannerStateLiveData.deviceFound();
        }
    }

    private boolean checkIfNodeIdentityMatches(final byte[] serviceData) {
        final MeshNetwork network = mMeshManagerApi.getMeshNetwork();
        if (network != null) {
            for (ProvisionedMeshNode node : network.getNodes()) {
                if (mMeshManagerApi.nodeIdentityMatches(node, serviceData)) {
                    return true;
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------------
    // Broadcast receiver control
    // ------------------------------------------------------------------------
    void registerBroadcastReceivers() {
        mContext.registerReceiver(
                mBluetoothStateBroadcastReceiver,
                new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));

        if (Utils.isWithinMarshmallowAndR()) {
            mContext.registerReceiver(
                    mLocationProviderChangedReceiver,
                    new IntentFilter(LocationManager.MODE_CHANGED_ACTION));
        }
    }

    void unregisterBroadcastReceivers() {
        mContext.unregisterReceiver(mBluetoothStateBroadcastReceiver);
        if (Utils.isWithinMarshmallowAndR()) {
            mContext.unregisterReceiver(mLocationProviderChangedReceiver);
        }
    }

    // ------------------------------------------------------------------------
    // Callback interface
    // ------------------------------------------------------------------------
    public interface ProxyScanCallback {
        void onProxyFound(@NonNull ScanResult result);
    }
}