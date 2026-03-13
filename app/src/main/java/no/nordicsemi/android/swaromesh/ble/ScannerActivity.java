package no.nordicsemi.android.swaromesh.ble;

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

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import dagger.hilt.android.AndroidEntryPoint;
import no.nordicsemi.android.swaromesh.ProvisioningActivity;
import no.nordicsemi.android.swaromesh.R;
import no.nordicsemi.android.swaromesh.adapter.ExtendedBluetoothDevice;
import no.nordicsemi.android.swaromesh.ble.adapter.DevicesAdapter;
import no.nordicsemi.android.swaromesh.databinding.ActivityScannerBinding;
import no.nordicsemi.android.swaromesh.utils.Utils;
import no.nordicsemi.android.swaromesh.viewmodels.ScannerLiveData;
import no.nordicsemi.android.swaromesh.viewmodels.ScannerStateLiveData;
import no.nordicsemi.android.swaromesh.viewmodels.ScannerViewModel;
import no.nordicsemi.android.swaromesh.viewmodels.SharedViewModel;

@AndroidEntryPoint
public class ScannerActivity extends AppCompatActivity implements DevicesAdapter.OnItemClickListener {

    private static final String TAG = "ScannerActivity";
    private static final int    REQUEST_ACCESS_FINE_LOCATION          = 1022;
    private static final int    REQUEST_ACCESS_BLUETOOTH_PERMISSION   = 1023;
    private static final long   TARGET_CONNECT_TIMEOUT_MS             = 20000;
    private static final long   AUTO_CONNECT_AFTER_PROVISIONING_DELAY = 2000;

    private ActivityScannerBinding binding;
    private ScannerViewModel       mViewModel;
    private SharedViewModel        mSharedViewModel;
    private DevicesAdapter         adapter;

    // Active filters
    private String mCurrentDeviceFilter = "";
    private int    mCurrentSignalFilter = DevicesAdapter.SIGNAL_DEFAULT;

    private boolean mScanWithProxyService = true;
    private boolean mSilentConnect        = false;
    private boolean mAutoConnectStarted   = false;
    private boolean mIsNewlyProvisioned   = false;
    private String  targetProxyMac;

    private boolean mShouldAutoConnectAfterProvisioning = false;
    private String  mProvisionedDeviceMac               = null;

    // ✅ ROOT FIX: prevent double-launch and scan restart
    private boolean mReconnectLaunched = false;
    private boolean mProxyConnected    = false;

    private Handler  mAutoConnectHandler;
    private long     mScanStartTime;

    // ✅ AutoProxyConnectManager — handles instant RSSI-based connect
    private AutoProxyConnectManager mAutoProxyConnectManager;

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
                            startAutoConnectWithManager();
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

        // ✅ Init AutoProxyConnectManager
        mAutoProxyConnectManager = new AutoProxyConnectManager(this);

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

        targetProxyMac    = getIntent().getStringExtra(Utils.EXTRA_TARGET_PROXY_MAC);
        mAutoConnectHandler = new Handler();

        if (targetProxyMac != null && !mShouldAutoConnectAfterProvisioning) {
            binding.textConnectingProgress.setText(
                    String.format("Looking for device: %s...", formatMacForDisplay(targetProxyMac)));
        }

        // Observe name/spinner filter from SharedViewModel
        mSharedViewModel.getDeviceNameFilter().observe(this, filter -> {
            mCurrentDeviceFilter = filter != null ? filter : "";
            Log.d(TAG, "Name filter changed: '" + mCurrentDeviceFilter + "'");
            applyFilterToAdapter();
        });

        // Observe signal threshold from SharedViewModel
        mSharedViewModel.getSignalThreshold().observe(this, threshold -> {
            mCurrentSignalFilter = threshold != null ? threshold : DevicesAdapter.SIGNAL_DEFAULT;
            Log.d(TAG, "Signal filter changed: " + mCurrentSignalFilter + "%");
            applyFilterToAdapter();
        });

        // Re-apply filters when new scan results arrive
        mViewModel.getScannerRepository().getScannerResults().observe(this, scannerLiveData -> {
            applyFilterToAdapter();
        });
    }

    // -----------------------------------------------------------------------
    // Filter
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
        stopAutoConnect();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopAutoConnect();
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
    // Device click
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
            intent.putExtra(Utils.EXTRA_SILENT_CONNECT, true);
            intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
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
                stopAutoConnect();
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

        // ✅ Start AutoProxyConnectManager instead of old polling loop
        if (!mScanWithProxyService && (mSilentConnect || mShouldAutoConnectAfterProvisioning)
                && targetProxyMac != null && !mAutoConnectStarted) {
            if (state.isBluetoothEnabled()) {
                Log.d(TAG, "Starting AutoProxyConnectManager for: " + targetProxyMac);
                startAutoConnectWithManager();
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
                startAutoConnectWithManager();
            }
        }), AUTO_CONNECT_AFTER_PROVISIONING_DELAY);
    }

    // -----------------------------------------------------------------------
    // ✅ NEW: AutoProxyConnectManager-based connect
    // -----------------------------------------------------------------------

    /**
     * Uses AutoProxyConnectManager to scan for proxy devices.
     * Instantly connects to the first device with RSSI > -85 dBm.
     * Falls back to best RSSI after scan window if no instant match found.
     *
     * If targetProxyMac is set, only that MAC is accepted.
     * Otherwise any provisioned proxy is accepted.
     */
    private void startAutoConnectWithManager() {
        if (mAutoConnectStarted) return;
        if (mReconnectLaunched || mProxyConnected) return;

        mAutoConnectStarted = true;
        mScanStartTime      = System.currentTimeMillis();

        // Build known MACs set — if we have a target, restrict to only that
        final Set<String> knownMacs = new HashSet<>();
        if (targetProxyMac != null) {
            knownMacs.add(targetProxyMac.toUpperCase());
        }
        // Pass null for knownMacs if set is empty (accept any proxy)
        final Set<String> macs = knownMacs.isEmpty() ? null : knownMacs;

        Log.d(TAG, "⚡ AutoProxyConnectManager started. Target: "
                + (targetProxyMac != null ? targetProxyMac : "any"));

        mAutoProxyConnectManager.findBestProxy(macs, TARGET_CONNECT_TIMEOUT_MS, mac -> {
            // Called on main thread
            mAutoConnectStarted = false;

            if (mReconnectLaunched || mProxyConnected) {
                Log.d(TAG, "Ignoring proxy result — already connected/launched");
                return;
            }

            if (mac == null) {
                // Nothing found in scan window
                Log.e(TAG, "No proxy found within timeout");
                handleAutoConnectTimeout();
                return;
            }

            Log.i(TAG, "✅ Best proxy MAC: " + mac + " — launching ReconnectActivity");

            // Find ExtendedBluetoothDevice from scan results for this MAC
            launchReconnectForMac(mac, 0);
        });
    }

    private void stopAutoConnect() {
        mAutoConnectStarted = false;
        if (mAutoProxyConnectManager != null) {
            mAutoProxyConnectManager.stop();
        }
        Log.d(TAG, "AutoProxyConnectManager stopped");
    }

    private void handleAutoConnectTimeout() {
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
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Try to find device in ViewModel results and launch ReconnectActivity.
     * If not found yet, retry up to 5 times with 300ms delay (device may not
     * have appeared in ViewModel scan results yet even though BLE saw it).
     */
    private void launchReconnectForMac(String mac, int attempt) {
        if (mReconnectLaunched || mProxyConnected) return;

        ExtendedBluetoothDevice targetDevice = findDeviceByMac(mac);

        if (targetDevice != null) {
            Log.i(TAG, "✅ Launching ReconnectActivity for: " + mac);
            mReconnectLaunched = true;
            stopScan();

            final Intent intent = new Intent(ScannerActivity.this, ReconnectActivity.class);
            intent.putExtra(Utils.EXTRA_DEVICE, targetDevice);
            intent.putExtra(Utils.EXTRA_SILENT_CONNECT, true);
            if (mIsNewlyProvisioned || mShouldAutoConnectAfterProvisioning) {
                intent.putExtra(Utils.EXTRA_NEWLY_PROVISIONED_NODE, true);
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
            reconnect.launch(intent);

        } else if (attempt < 5) {
            // Device not in ViewModel yet — wait 300ms and retry
            Log.w(TAG, "Device " + mac + " not in scan results yet, retry " + (attempt + 1) + "/5");
            mAutoConnectHandler.postDelayed(() -> launchReconnectForMac(mac, attempt + 1), 300);

        } else {
            // Gave up — treat as timeout
            Log.e(TAG, "Device " + mac + " never appeared in ViewModel results after retries");
            mReconnectLaunched = false;
            handleAutoConnectTimeout();
        }
    }

    /**
     * Find device in ViewModel scan results by MAC address.
     */
    private ExtendedBluetoothDevice findDeviceByMac(String mac) {
        if (mac == null) return null;
        final ScannerLiveData resultsLiveData =
                mViewModel.getScannerRepository().getScannerResults();
        if (resultsLiveData == null || resultsLiveData.getDevices() == null) return null;

        for (ExtendedBluetoothDevice device : resultsLiveData.getDevices()) {
            if (device.getAddress() != null
                    && device.getAddress().equalsIgnoreCase(mac)) {
                return device;
            }
        }
        return null;
    }

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
        stopAutoConnect();

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