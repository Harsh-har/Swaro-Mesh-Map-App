package no.nordicsemi.android.swaromapmesh.ble;


import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;

import dagger.hilt.android.AndroidEntryPoint;
import no.nordicsemi.android.swaromapmesh.ProvisioningActivity;
import no.nordicsemi.android.swaromapmesh.R;
import no.nordicsemi.android.swaromapmesh.adapter.ExtendedBluetoothDevice;
import no.nordicsemi.android.swaromapmesh.ble.adapter.DevicesAdapter;
import no.nordicsemi.android.swaromapmesh.databinding.ActivityScannerBinding;
import no.nordicsemi.android.swaromapmesh.utils.Utils;
import no.nordicsemi.android.swaromapmesh.viewmodels.ScannerLiveData;
import no.nordicsemi.android.swaromapmesh.viewmodels.ScannerStateLiveData;
import no.nordicsemi.android.swaromapmesh.viewmodels.ScannerViewModel;
import no.nordicsemi.android.swaromapmesh.viewmodels.SharedViewModel;

import java.util.UUID;

@AndroidEntryPoint
public class ScannerActivity extends AppCompatActivity implements DevicesAdapter.OnItemClickListener {

    private static final String TAG = "ScannerActivity";
    private static final int    REQUEST_ACCESS_FINE_LOCATION          = 1022;
    private static final int    REQUEST_ACCESS_BLUETOOTH_PERMISSION   = 1023;
    private static final long   AUTO_CONNECT_RETRY_DELAY_MS           = 1000;
    private static final long   TARGET_CONNECT_TIMEOUT_MS             = 30000;
    private static final long   AUTO_CONNECT_AFTER_PROVISIONING_DELAY = 2000;

    private ActivityScannerBinding binding;
    private ScannerViewModel       mViewModel;
    private SharedViewModel        mSharedViewModel;
    private DevicesAdapter         adapter;

    // Active filters — name filter removed from UI, only signal remains
    private String mCurrentDeviceFilter = "";  // always empty; kept for adapter API compatibility
    private int    mCurrentSignalFilter = DevicesAdapter.SIGNAL_DEFAULT;

    private boolean mScanWithProxyService = true;
    private boolean mSilentConnect        = false;
    private boolean mAutoConnectStarted   = false;
    private boolean mIsNewlyProvisioned   = false;
    private String  targetProxyMac;

    private boolean mShouldAutoConnectAfterProvisioning = false;
    private String  mProvisionedDeviceMac               = null;

    private boolean mReconnectLaunched = false;
    private boolean mProxyConnected    = false;

    private Handler  mAutoConnectHandler;
    private Runnable mAutoConnectRunnable;
    private long     mScanStartTime;

    // -----------------------------------------------------------------------
    // Auto-click flag — set true when SW-RL01-006 (or any specific device) is selected
    // -----------------------------------------------------------------------
    private boolean mAutoClickEnabled = false;

    // -----------------------------------------------------------------------
    // Activity Result Launchers
    // -----------------------------------------------------------------------

    private final ActivityResultLauncher<Intent> provisioner =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    mIsNewlyProvisioned = true;

                    ExtendedBluetoothDevice provisionedDevice =
                            result.getData().getParcelableExtra(Utils.EXTRA_DEVICE);
                    boolean autoConnectAfterProvisioning =
                            result.getData().getBooleanExtra(Utils.EXTRA_AUTO_CONNECT_AFTER_PROVISIONING, false);

                    if (autoConnectAfterProvisioning && provisionedDevice != null) {
                        mProvisionedDeviceMac               = provisionedDevice.getAddress();
                        mShouldAutoConnectAfterProvisioning = true;
                        mIsNewlyProvisioned                 = true;

                        Log.d(TAG, "Provisioning complete for: " + mProvisionedDeviceMac);

                        showConnectingUI();
                        binding.textConnectingProgress.setText(
                                String.format("Provisioning complete!\nConnecting to %s...",
                                        formatMacForDisplay(mProvisionedDeviceMac)));

                        startAutoConnectAfterProvisioning();
                    } else {
                        setResultIntent(result.getData());
                    }
                }
            });

    private final ActivityResultLauncher<Intent> enableBluetooth =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK) {
                    mViewModel.getScannerRepository().getScannerState().startScanning();
                }
            });

    private final ActivityResultLauncher<Intent> reconnect =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK) {
                    mProxyConnected    = true;
                    mReconnectLaunched = false;

                    final Intent data = result.getData();
                    if (data == null) {
                        setResult(Activity.RESULT_OK);
                    } else {
                        data.putExtra(Utils.EXTRA_NEWLY_PROVISIONED_NODE, mIsNewlyProvisioned);
                        setResult(Activity.RESULT_OK, data);
                    }
                    finish();
                    overridePendingTransition(0, 0);

                } else {
                    mReconnectLaunched = false;
                    mProxyConnected    = false;

                    if (!mScanWithProxyService && mSilentConnect) {
                        showScannerUI();
                        mAutoConnectStarted = false;
                        if (targetProxyMac != null) {
                            startAutoConnectLoop();
                        }
                    }
                }
            });

    // -----------------------------------------------------------------------
    // onCreate
    // -----------------------------------------------------------------------

    @Override
    protected void onCreate(@Nullable final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding          = ActivityScannerBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        mViewModel       = new ViewModelProvider(this).get(ScannerViewModel.class);
        mSharedViewModel = new ViewModelProvider(this).get(SharedViewModel.class);

        final Toolbar toolbar = binding.toolbar;
        toolbar.setTitle(R.string.title_scanner);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null)
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        // Parse intent
        if (getIntent() != null) {
            mScanWithProxyService = getIntent().getBooleanExtra(Utils.EXTRA_DATA_PROVISIONING_SERVICE, true);
            mSilentConnect        = getIntent().getBooleanExtra(Utils.EXTRA_SILENT_CONNECT, false);

            boolean autoConnectAfterProvisioning =
                    getIntent().getBooleanExtra(Utils.EXTRA_AUTO_CONNECT_AFTER_PROVISIONING, false);

            if (autoConnectAfterProvisioning) {
                String deviceMac = getIntent().getStringExtra(Utils.EXTRA_TARGET_PROXY_MAC);
                if (deviceMac != null) {
                    mProvisionedDeviceMac               = deviceMac;
                    mShouldAutoConnectAfterProvisioning = true;
                    mSilentConnect                      = true;
                    mScanWithProxyService               = false;

                    Log.d(TAG, "Auto-connect from intent for MAC: " + deviceMac);

                    showConnectingUI();
                    binding.textConnectingProgress.setText(
                            String.format("Auto-connecting to provisioned device: %s...",
                                    formatMacForDisplay(deviceMac)));

                    startAutoConnectAfterProvisioning();
                }
            }

            if (getSupportActionBar() != null) {
                getSupportActionBar().setSubtitle(mScanWithProxyService
                        ? R.string.sub_title_scanning_nodes
                        : R.string.sub_title_scanning_proxy_node);
            }
        }

        if (!mScanWithProxyService && mViewModel.getBleMeshManager().isConnected()) {
            setResult(Activity.RESULT_OK);
            finish();
            overridePendingTransition(0, 0);
            return;
        }

        // RecyclerView
        final RecyclerView recyclerViewDevices = binding.recyclerViewBleDevices;
        recyclerViewDevices.setLayoutManager(new LinearLayoutManager(this));
        recyclerViewDevices.addItemDecoration(
                new DividerItemDecoration(recyclerViewDevices.getContext(),
                        DividerItemDecoration.VERTICAL));

        final SimpleItemAnimator itemAnimator =
                (SimpleItemAnimator) recyclerViewDevices.getItemAnimator();
        if (itemAnimator != null) itemAnimator.setSupportsChangeAnimations(false);

        adapter = new DevicesAdapter(this, mViewModel.getScannerRepository().getScannerResults());
        adapter.setOnItemClickListener(this);
        recyclerViewDevices.setAdapter(adapter);

        binding.noDevices.actionEnableLocation.setOnClickListener(v -> onEnableLocationClicked());
        binding.bluetoothOff.actionEnableBluetooth.setOnClickListener(v -> onEnableBluetoothClicked());
        binding.noLocationPermission.actionGrantLocationPermission.setOnClickListener(v -> onGrantLocationPermissionClicked());
        binding.noLocationPermission.actionPermissionSettings.setOnClickListener(v -> onPermissionSettingsClicked());
        binding.noBluetoothPermissions.actionGrantBluetoothPermission.setOnClickListener(v -> onGrantBluetoothPermissionClicked());

        mViewModel.getScannerRepository().getScannerState().observe(this, this::startScan);

        targetProxyMac      = getIntent().getStringExtra(Utils.EXTRA_TARGET_PROXY_MAC);
        mAutoConnectHandler = new Handler();

        if (targetProxyMac != null && !mShouldAutoConnectAfterProvisioning) {
            binding.textConnectingProgress.setText(
                    String.format("Looking for device: %s...", formatMacForDisplay(targetProxyMac)));
        }

        // -----------------------------------------------------------------------
        // Observe selected device (spinner) from SharedViewModel
        // Enables auto-click when a specific device filter is selected
        // -----------------------------------------------------------------------
        mSharedViewModel.getSelectedDevice().observe(this, device -> {
            mCurrentDeviceFilter = (device != null && !device.equals("All Device")) ? device : "";

            // Enable auto-click ONLY when a specific device is selected (not "All Device")
            mAutoClickEnabled = device != null
                    && !device.equals("All Device")
                    && !device.isEmpty();

            Log.d(TAG, "Device filter changed: '" + mCurrentDeviceFilter
                    + "' | autoClick=" + mAutoClickEnabled);

            applyFilterToAdapter();

            // Try immediately in case device is already in the scan list
            if (mAutoClickEnabled) {
                tryAutoClickTargetDevice();
            }
        });

        // Observe signal threshold from SharedViewModel — Default or 100% only
        mSharedViewModel.getSignalThreshold().observe(this, threshold -> {
            mCurrentSignalFilter = threshold != null ? threshold : DevicesAdapter.SIGNAL_DEFAULT;
            Log.d(TAG, "Signal filter changed: " + mCurrentSignalFilter + "%");
            applyFilterToAdapter();
        });

        // -----------------------------------------------------------------------
        // Re-apply filters when new scan results arrive
        // Also triggers auto-click check on every new result
        // -----------------------------------------------------------------------
        mViewModel.getScannerRepository().getScannerResults().observe(this, scannerLiveData -> {
            applyFilterToAdapter();

            // Auto-click if a specific device filter is active and device is found
            if (mAutoClickEnabled) {
                tryAutoClickTargetDevice();
            }
        });
    }

    // -----------------------------------------------------------------------
    // Auto-click logic
    // Scans current results for the filtered device and auto-triggers onItemClick
    // -----------------------------------------------------------------------

    /**
     * Searches current BLE scan results for a device matching {@code mCurrentDeviceFilter}.
     * If found, automatically triggers {@link #onItemClick(ExtendedBluetoothDevice)}
     * exactly as if the user had tapped the device row manually.
     *
     * Guards:
     *  - Only runs when {@code mAutoClickEnabled} is true
     *  - Skipped if a reconnect or proxy connect is already in progress
     *  - Resets {@code mAutoClickEnabled} to false after firing to prevent repeated clicks
     */
    private void tryAutoClickTargetDevice() {
        if (!mAutoClickEnabled) return;
        if (mReconnectLaunched || mProxyConnected) return;
        if (mCurrentDeviceFilter == null || mCurrentDeviceFilter.isEmpty()) return;

        ScannerLiveData results = mViewModel.getScannerRepository().getScannerResults();
        if (results == null || results.getDevices() == null) return;

        for (ExtendedBluetoothDevice device : results.getDevices()) {
            String name = device.getName();
            if (name != null
                    && name.toLowerCase().contains(mCurrentDeviceFilter.toLowerCase())) {

                Log.i(TAG, "Auto-click triggered for device: "
                        + name + " [" + device.getAddress() + "]");

                // Disable auto-click before calling onItemClick to prevent re-entry
                mAutoClickEnabled = false;

                // Reuse exact same logic as a manual tap
                onItemClick(device);
                return;
            }
        }

        Log.d(TAG, "Auto-click: '" + mCurrentDeviceFilter + "' not found yet, waiting for next scan result...");
    }

    // -----------------------------------------------------------------------
    // Filter — apply device type (spinner) AND signal to adapter
    // -----------------------------------------------------------------------

    private void applyFilterToAdapter() {
        if (!mScanWithProxyService || mSilentConnect || mShouldAutoConnectAfterProvisioning) return;
        if (adapter == null) return;

        adapter.applyFilters(mCurrentDeviceFilter, mCurrentSignalFilter);

        if (!mSilentConnect && !mShouldAutoConnectAfterProvisioning) {
            binding.noDevices.getRoot().setVisibility(
                    adapter.isEmpty() ? View.VISIBLE : View.GONE);
        }
    }

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    @Override
    protected void onStart() {
        super.onStart();

        if (mProxyConnected || mReconnectLaunched) {
            Log.d(TAG, "onStart: reconnect in progress or connected — skipping scan");
            return;
        }

        if (mViewModel.getBleMeshManager().isConnected()) {
            Log.d(TAG, "onStart: BLE already connected — skipping scan");
            return;
        }

        mScanStartTime = System.currentTimeMillis();
        mViewModel.getScannerRepository().getScannerState().startScanning();
    }

    @Override
    protected void onStop() {
        super.onStop();

        if (mProxyConnected || mReconnectLaunched) {
            Log.d(TAG, "onStop: reconnect in progress — not stopping scan");
            return;
        }

        stopScan();
        stopAutoConnectLoop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopAutoConnectLoop();
        if (mAutoConnectHandler != null) {
            mAutoConnectHandler.removeCallbacksAndMessages(null);
        }
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull final MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            onBackPressed();
            return true;
        }
        return false;
    }

    // -----------------------------------------------------------------------
    // Device click — manual OR auto-triggered
    // -----------------------------------------------------------------------

    @Override
    public void onItemClick(final ExtendedBluetoothDevice device) {
        if (mViewModel.getBleMeshManager().isConnected())
            mViewModel.disconnect();

        if (mScanWithProxyService) {
            final Intent intent = new Intent(this, ProvisioningActivity.class);
            intent.putExtra(Utils.EXTRA_DEVICE, device);
            provisioner.launch(intent);
        } else {
            final Intent intent = new Intent(this, ReconnectActivity.class);
            intent.putExtra(Utils.EXTRA_DEVICE, device);
            intent.putExtra(Utils.EXTRA_SILENT_CONNECT, false);
            reconnect.launch(intent);
        }
    }

    // -----------------------------------------------------------------------
    // Permissions
    // -----------------------------------------------------------------------

    @Override
    public void onRequestPermissionsResult(final int requestCode,
                                           @NonNull final String[] permissions,
                                           @NonNull final int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_ACCESS_FINE_LOCATION
                || requestCode == REQUEST_ACCESS_BLUETOOTH_PERMISSION) {
            mViewModel.getScannerRepository().getScannerState().startScanning();
        }
    }

    private void onEnableLocationClicked() {
        startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
    }

    private void onEnableBluetoothClicked() {
        enableBluetooth.launch(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
    }

    private void onGrantLocationPermissionClicked() {
        Utils.markLocationPermissionRequested(this);
        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                REQUEST_ACCESS_FINE_LOCATION);
    }

    private void onGrantBluetoothPermissionClicked() {
        if (Utils.isSorAbove()) {
            Utils.markBluetoothPermissionsRequested(this);
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.BLUETOOTH_SCAN,
                            Manifest.permission.BLUETOOTH_CONNECT},
                    REQUEST_ACCESS_BLUETOOTH_PERMISSION);
        }
    }

    private void onPermissionSettingsClicked() {
        final Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.fromParts("package", getPackageName(), null));
        startActivity(intent);
    }

    // -----------------------------------------------------------------------
    // Scan observer
    // -----------------------------------------------------------------------

    private void startScan(final ScannerStateLiveData state) {
        if (mProxyConnected || mReconnectLaunched) {
            Log.d(TAG, "startScan() blocked — reconnect launched or proxy connected");
            return;
        }

        if (!mScanWithProxyService && (mSilentConnect || mShouldAutoConnectAfterProvisioning)
                && targetProxyMac != null) {
            showConnectingUI();
            updateProgressText();
        }

        if (!Utils.isBluetoothScanAndConnectPermissionsGranted(this)) {
            if (!mSilentConnect && !mShouldAutoConnectAfterProvisioning) {
                binding.noBluetoothPermissions.getRoot().setVisibility(View.VISIBLE);
                binding.bluetoothOff.getRoot().setVisibility(View.GONE);
                binding.stateScanning.setVisibility(View.INVISIBLE);
                binding.noDevices.getRoot().setVisibility(View.GONE);
            }
            return;
        } else {
            binding.noBluetoothPermissions.getRoot().setVisibility(View.GONE);
        }

        if (!Utils.isLocationPermissionsGranted(this)) {
            if (!mSilentConnect && !mShouldAutoConnectAfterProvisioning) {
                binding.noLocationPermission.getRoot().setVisibility(View.VISIBLE);
                binding.bluetoothOff.getRoot().setVisibility(View.GONE);
                binding.stateScanning.setVisibility(View.INVISIBLE);
                binding.noDevices.getRoot().setVisibility(View.GONE);
            }
            return;
        } else {
            binding.noLocationPermission.getRoot().setVisibility(View.GONE);
        }

        if (!state.isBluetoothEnabled()) {
            binding.bluetoothOff.getRoot().setVisibility(View.VISIBLE);
            binding.stateScanning.setVisibility(View.INVISIBLE);
            binding.noDevices.getRoot().setVisibility(View.GONE);
            binding.connectivityProgressContainer.setVisibility(View.GONE);

            if (mAutoConnectStarted) {
                stopAutoConnectLoop();
            }

            Log.d(TAG, "Bluetooth is off - showing Bluetooth off UI");
            return;
        } else {
            binding.bluetoothOff.getRoot().setVisibility(View.GONE);
        }

        final UUID scanUuid;
        if (mShouldAutoConnectAfterProvisioning) {
            scanUuid = BleMeshManager.MESH_PROXY_UUID;
        } else {
            scanUuid = mScanWithProxyService
                    ? BleMeshManager.MESH_PROVISIONING_UUID
                    : BleMeshManager.MESH_PROXY_UUID;
        }

        if (!state.isScanning()) {
            mViewModel.getScannerRepository().startScan(scanUuid);
            if (!mSilentConnect && !mShouldAutoConnectAfterProvisioning) {
                binding.stateScanning.setVisibility(View.VISIBLE);
            }
        }

        if (!mSilentConnect && !mShouldAutoConnectAfterProvisioning) {
            binding.noDevices.getRoot().setVisibility(
                    (adapter != null && adapter.isEmpty()) ? View.VISIBLE : View.GONE);
        }

        if (!mScanWithProxyService && (mSilentConnect || mShouldAutoConnectAfterProvisioning)
                && targetProxyMac != null && !mAutoConnectStarted) {
            Log.d(TAG, "Starting auto-connect loop for: " + targetProxyMac);
            if (state.isBluetoothEnabled()) {
                startAutoConnectLoop();
            } else {
                Log.d(TAG, "Bluetooth is off - not starting auto-connect");
            }
        }
    }

    // -----------------------------------------------------------------------
    // Auto-connect after provisioning
    // -----------------------------------------------------------------------

    private void startAutoConnectAfterProvisioning() {
        if (mProvisionedDeviceMac == null) return;

        targetProxyMac = mProvisionedDeviceMac;
        Log.d(TAG, "Target MAC: " + targetProxyMac);
        stopScan();

        new Handler().postDelayed(() -> runOnUiThread(() -> {
            if (mReconnectLaunched || mProxyConnected) return;

            Log.d(TAG, "Starting PROXY scan after delay");
            mViewModel.getScannerRepository().startScan(BleMeshManager.MESH_PROXY_UUID);
            if (!mAutoConnectStarted) {
                startAutoConnectLoop();
            }
        }), AUTO_CONNECT_AFTER_PROVISIONING_DELAY);
    }

    // -----------------------------------------------------------------------
    // Auto-connect loop
    // -----------------------------------------------------------------------

    private void startAutoConnectLoop() {
        if (mAutoConnectStarted) return;

        mAutoConnectStarted = true;
        mScanStartTime      = System.currentTimeMillis();
        Log.d(TAG, "Auto-connect loop started");

        mAutoConnectRunnable = new Runnable() {
            @Override
            public void run() {
                if (mProxyConnected || mReconnectLaunched) {
                    Log.d(TAG, "Loop stopped — reconnect launched or connected");
                    stopAutoConnectLoop();
                    return;
                }

                long elapsed = System.currentTimeMillis() - mScanStartTime;
                Log.d(TAG, "Loop check: elapsed " + elapsed + "ms");

                if (elapsed > TARGET_CONNECT_TIMEOUT_MS) {
                    Log.e(TAG, "Timeout!");
                    if (mShouldAutoConnectAfterProvisioning) {
                        runOnUiThread(() -> {
                            binding.textConnectingProgress.setText(
                                    "Failed to connect after provisioning.\nPlease try manual connection.");
                            new Handler().postDelayed(() -> {
                                setResult(Activity.RESULT_CANCELED);
                                finish();
                            }, 3000);
                        });
                    } else {
                        showTimeoutMessage();
                    }
                    stopAutoConnectLoop();
                    return;
                }

                ExtendedBluetoothDevice targetDevice = findTargetDevice();

                if (targetDevice != null) {
                    Log.i(TAG, "✅ Target found: " + targetDevice.getAddress());

                    mReconnectLaunched  = true;
                    mAutoConnectStarted = false;

                    stopScan();
                    stopAutoConnectLoop();

                    final Intent intent = new Intent(ScannerActivity.this, ReconnectActivity.class);
                    intent.putExtra(Utils.EXTRA_DEVICE, targetDevice);
                    intent.putExtra(Utils.EXTRA_SILENT_CONNECT, true);
                    if (mIsNewlyProvisioned || mShouldAutoConnectAfterProvisioning) {
                        intent.putExtra(Utils.EXTRA_NEWLY_PROVISIONED_NODE, true);
                    }
                    intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
                    reconnect.launch(intent);

                } else {
                    updateProgressText();
                    mAutoConnectHandler.postDelayed(this, AUTO_CONNECT_RETRY_DELAY_MS);
                }
            }
        };

        mAutoConnectHandler.post(mAutoConnectRunnable);
    }

    private void stopAutoConnectLoop() {
        mAutoConnectStarted = false;
        if (mAutoConnectRunnable != null) {
            mAutoConnectHandler.removeCallbacks(mAutoConnectRunnable);
            mAutoConnectRunnable = null;
        }
        Log.d(TAG, "Auto-connect loop stopped");
    }

    private ExtendedBluetoothDevice findTargetDevice() {
        final ScannerLiveData resultsLiveData =
                mViewModel.getScannerRepository().getScannerResults();
        if (resultsLiveData == null || resultsLiveData.getDevices() == null) return null;

        for (ExtendedBluetoothDevice device : resultsLiveData.getDevices()) {
            if (device.getAddress() != null
                    && device.getAddress().equalsIgnoreCase(targetProxyMac)) {
                return device;
            }
        }
        return null;
    }

    // -----------------------------------------------------------------------
    // UI helpers
    // -----------------------------------------------------------------------

    private void updateProgressText() {
        if (targetProxyMac == null || binding.textConnectingProgress == null) return;
        long   elapsed = (System.currentTimeMillis() - mScanStartTime) / 1000;
        String mac     = formatMacForDisplay(targetProxyMac);
        String text    = mShouldAutoConnectAfterProvisioning
                ? String.format("Provisioned device: %s\nConnecting... (%d seconds)", mac, elapsed)
                : String.format("Looking for device: %s\nElapsed: %d seconds...", mac, elapsed);
        binding.textConnectingProgress.setText(text);
    }

    private void showTimeoutMessage() {
        runOnUiThread(() -> {
            binding.textConnectingProgress.setText(
                    String.format("Device %s not found after %d seconds.\nShowing available devices...",
                            formatMacForDisplay(targetProxyMac), TARGET_CONNECT_TIMEOUT_MS / 1000));
            new Handler().postDelayed(this::showScannerUI, 2000);
        });
    }

    private String formatMacForDisplay(String mac) {
        if (mac == null) return "Unknown";
        return mac.toUpperCase().replaceAll("(.{2})", "$1:").substring(0, 17);
    }

    private void stopScan() {
        mViewModel.getScannerRepository().stopScan();
    }

    private void showConnectingUI() {
        binding.appbarLayout.setVisibility(View.VISIBLE);
        binding.recyclerViewBleDevices.setVisibility(View.GONE);
        binding.noDevices.getRoot().setVisibility(View.GONE);
        binding.bluetoothOff.getRoot().setVisibility(View.GONE);
        binding.noLocationPermission.getRoot().setVisibility(View.GONE);
        binding.noBluetoothPermissions.getRoot().setVisibility(View.GONE);
        binding.stateScanning.setVisibility(View.GONE);
        binding.connectivityProgressContainer.setVisibility(View.VISIBLE);
    }

    private void showScannerUI() {
        binding.appbarLayout.setVisibility(View.VISIBLE);
        binding.recyclerViewBleDevices.setVisibility(View.VISIBLE);
        binding.connectivityProgressContainer.setVisibility(View.GONE);
        binding.stateScanning.setVisibility(View.VISIBLE);

        targetProxyMac                      = null;
        mShouldAutoConnectAfterProvisioning = false;
        mReconnectLaunched                  = false;
        stopAutoConnectLoop();

        if (adapter != null) {
            adapter.applyFilters(mCurrentDeviceFilter, mCurrentSignalFilter);
        }
    }

    private void setResultIntent(final Intent data) {
        data.putExtra(Utils.EXTRA_NEWLY_PROVISIONED_NODE, mIsNewlyProvisioned);
        setResult(Activity.RESULT_OK, data);
        finish();
    }
}