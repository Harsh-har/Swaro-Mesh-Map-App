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
import no.nordicsemi.android.swaromapmesh.DeviceDetailActivity;
import no.nordicsemi.android.swaromapmesh.ProvisioningActivity;
import no.nordicsemi.android.swaromapmesh.R;
import no.nordicsemi.android.swaromapmesh.adapter.ExtendedBluetoothDevice;
import no.nordicsemi.android.swaromapmesh.ble.adapter.DevicesAdapter;
import no.nordicsemi.android.swaromapmesh.databinding.ActivityScannerBinding;
import no.nordicsemi.android.swaromapmesh.swajaui.Svg_Operations.SvgDeviceMapper;
import no.nordicsemi.android.swaromapmesh.transport.ProvisionedMeshNode;
import no.nordicsemi.android.swaromapmesh.utils.Utils;
import no.nordicsemi.android.swaromapmesh.viewmodels.ScannerLiveData;
import no.nordicsemi.android.swaromapmesh.viewmodels.ScannerStateLiveData;
import no.nordicsemi.android.swaromapmesh.viewmodels.ScannerViewModel;
import no.nordicsemi.android.swaromapmesh.viewmodels.SharedViewModel;

@AndroidEntryPoint
public class ScannerActivity extends AppCompatActivity implements DevicesAdapter.OnItemClickListener {

    private static final String TAG = "ScannerActivity";
    private static final int    REQUEST_ACCESS_FINE_LOCATION        = 1022;
    private static final int    REQUEST_ACCESS_BLUETOOTH_PERMISSION = 1023;
    private static final long   AUTO_CONNECT_RETRY_DELAY_MS           = 1000;
    private static final long   TARGET_CONNECT_TIMEOUT_MS             = 10000;
    private static final long   AUTO_CONNECT_AFTER_PROVISIONING_DELAY = 2000;

    private ActivityScannerBinding binding;
    private ScannerViewModel       mViewModel;
    private SharedViewModel        mSharedViewModel;
    private DevicesAdapter         adapter;

    private String  mCurrentDeviceFilter = "";
    private int     mCurrentSignalFilter = DevicesAdapter.SIGNAL_DEFAULT;

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

    private boolean mAutoClickEnabled = false;

    // Member variables — survive across all launcher callbacks
    private String mSvgDeviceId = null;
    private String mReceiveId   = null;

    // -----------------------------------------------------------------------
    // Activity Result Launchers
    // -----------------------------------------------------------------------

    private final ActivityResultLauncher<Intent> provisioner =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    mIsNewlyProvisioned = true;

                    final Intent data = result.getData();

                    // Capture svgDeviceId from ProvisioningActivity result
                    String svgFromResult = data.getStringExtra(Utils.EXTRA_SVG_DEVICE_ID);
                    if (svgFromResult != null) {
                        mSvgDeviceId = svgFromResult;
                        Log.d(TAG, "provisioner result — mSvgDeviceId=" + mSvgDeviceId);
                    }

                    ExtendedBluetoothDevice provisionedDevice =
                            data.getParcelableExtra(Utils.EXTRA_DEVICE);
                    boolean autoConnectAfterProvisioning =
                            data.getBooleanExtra(Utils.EXTRA_AUTO_CONNECT_AFTER_PROVISIONING, false);

                    if (autoConnectAfterProvisioning && provisionedDevice != null) {
                        mProvisionedDeviceMac               = provisionedDevice.getAddress();
                        mShouldAutoConnectAfterProvisioning = true;
                        mIsNewlyProvisioned                 = true;

                        Log.d(TAG, "Provisioning complete for MAC: " + mProvisionedDeviceMac
                                + " with SVG ID: " + mSvgDeviceId);

                        showConnectingUI();
                        binding.textConnectingProgress.setText(
                                "Provisioning complete!\nConnecting to proxy...");

                        startAutoConnectAfterProvisioning();

                    } else {
                        setResultIntent(data);
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

                    // Build return intent with all required fields
                    final Intent returnIntent = new Intent();
                    returnIntent.putExtra(Utils.EXTRA_NEWLY_PROVISIONED_NODE, mIsNewlyProvisioned);
                    returnIntent.putExtra(Utils.PROVISIONING_COMPLETED, true);
                    if (mSvgDeviceId != null) {
                        returnIntent.putExtra(Utils.EXTRA_SVG_DEVICE_ID, mSvgDeviceId);
                    }
                    if (mReceiveId != null) {
                        returnIntent.putExtra(DeviceDetailActivity.EXTRA_RECEIVE_ID, mReceiveId);
                    }

                    // Copy device from ReconnectActivity result if present
                    final Intent reconnectData = result.getData();
                    if (reconnectData != null) {
                        ExtendedBluetoothDevice reconnectDevice =
                                reconnectData.getParcelableExtra(Utils.EXTRA_DEVICE);
                        returnIntent.putExtra(Utils.EXTRA_DEVICE, reconnectDevice);
                    }

                    setResult(Activity.RESULT_OK, returnIntent);
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
        toolbar.setTitle(null);
        setSupportActionBar(toolbar);

        // Read extras from incoming intent
        if (getIntent() != null) {
            mSvgDeviceId = getIntent().getStringExtra(Utils.EXTRA_SVG_DEVICE_ID);
            mReceiveId   = getIntent().getStringExtra(DeviceDetailActivity.EXTRA_RECEIVE_ID);

            Log.d(TAG, "onCreate — mSvgDeviceId from intent: " + mSvgDeviceId);

            mScanWithProxyService = getIntent().getBooleanExtra(
                    Utils.EXTRA_DATA_PROVISIONING_SERVICE, true);
            mSilentConnect        = getIntent().getBooleanExtra(
                    Utils.EXTRA_SILENT_CONNECT, false);

            // AUTO FILTER — set device name filter for auto-click
            String autoFilterDevice = getIntent().getStringExtra(
                    DeviceDetailActivity.EXTRA_AUTO_FILTER_DEVICE);
            if (autoFilterDevice != null && !autoFilterDevice.isEmpty()) {
                mSharedViewModel.setSelectedDevice(autoFilterDevice);
                mSharedViewModel.setDeviceNameFilter("");
            }

            boolean autoConnectAfterProvisioning =
                    getIntent().getBooleanExtra(
                            Utils.EXTRA_AUTO_CONNECT_AFTER_PROVISIONING, false);

            if (autoConnectAfterProvisioning) {
                String deviceMac = getIntent().getStringExtra(Utils.EXTRA_TARGET_PROXY_MAC);
                if (deviceMac != null) {
                    mProvisionedDeviceMac               = deviceMac;
                    mShouldAutoConnectAfterProvisioning = true;
                    mSilentConnect                      = true;
                    mScanWithProxyService               = false;

                    Log.d(TAG, "Auto-connect from intent for MAC: " + deviceMac);

                    showConnectingUI();
                    binding.textConnectingProgress.setText("Connecting to proxy...");

                    startAutoConnectAfterProvisioning();
                }
            } else {
                // Normal scanner mode — title, subtitle, back arrow
                if (getSupportActionBar() != null) {
                    getSupportActionBar().setTitle(R.string.title_scanner);
                    getSupportActionBar().setDisplayHomeAsUpEnabled(true);
                    getSupportActionBar().setSubtitle(mScanWithProxyService
                            ? R.string.sub_title_scanning_nodes
                            : R.string.sub_title_scanning_proxy_node);
                }
            }
        }

        if (!mScanWithProxyService && mViewModel.getBleMeshManager().isConnected()) {
            setResult(Activity.RESULT_OK);
            finish();
            overridePendingTransition(0, 0);
            return;
        }

        // RecyclerView setup
        final RecyclerView recyclerViewDevices = binding.recyclerViewBleDevices;
        recyclerViewDevices.setLayoutManager(new LinearLayoutManager(this));
        recyclerViewDevices.addItemDecoration(
                new DividerItemDecoration(recyclerViewDevices.getContext(),
                        DividerItemDecoration.VERTICAL));

        final SimpleItemAnimator itemAnimator =
                (SimpleItemAnimator) recyclerViewDevices.getItemAnimator();
        if (itemAnimator != null) itemAnimator.setSupportsChangeAnimations(false);

        adapter = new DevicesAdapter(this,
                mViewModel.getScannerRepository().getScannerResults());
        adapter.setOnItemClickListener(this);
        recyclerViewDevices.setAdapter(adapter);

        binding.noDevices.actionEnableLocation.setOnClickListener(
                v -> onEnableLocationClicked());
        binding.bluetoothOff.actionEnableBluetooth.setOnClickListener(
                v -> onEnableBluetoothClicked());
        binding.noLocationPermission.actionGrantLocationPermission.setOnClickListener(
                v -> onGrantLocationPermissionClicked());
        binding.noLocationPermission.actionPermissionSettings.setOnClickListener(
                v -> onPermissionSettingsClicked());
        binding.noBluetoothPermissions.actionGrantBluetoothPermission.setOnClickListener(
                v -> onGrantBluetoothPermissionClicked());

        mViewModel.getScannerRepository().getScannerState().observe(this, this::startScan);

        targetProxyMac      = getIntent().getStringExtra(Utils.EXTRA_TARGET_PROXY_MAC);
        mAutoConnectHandler = new Handler();

        if (targetProxyMac != null && !mShouldAutoConnectAfterProvisioning) {
            binding.textConnectingProgress.setText(
                    String.format("Looking for device: %s...",
                            formatMacForDisplay(targetProxyMac)));
        }

        mSharedViewModel.getSelectedDevice().observe(this, device -> {
            mCurrentDeviceFilter = (device != null
                    && !device.equals("All Device")) ? device : "";
            mAutoClickEnabled    = device != null
                    && !device.equals("All Device")
                    && !device.isEmpty();
            Log.d(TAG, "Device filter: '" + mCurrentDeviceFilter
                    + "' autoClick=" + mAutoClickEnabled);
            applyFilterToAdapter();
            if (mAutoClickEnabled) tryAutoClickTargetDevice();
        });

        mSharedViewModel.getSignalThreshold().observe(this, threshold -> {
            mCurrentSignalFilter = threshold != null
                    ? threshold : DevicesAdapter.SIGNAL_DEFAULT;
            Log.d(TAG, "Signal filter: " + mCurrentSignalFilter);
            applyFilterToAdapter();
        });

        mViewModel.getScannerRepository().getScannerResults().observe(this, scannerLiveData -> {
            applyFilterToAdapter();
            if (mAutoClickEnabled) tryAutoClickTargetDevice();
        });
    }

    // -----------------------------------------------------------------------
    // Auto-click — with loose matching (SW-CLF01 → also matches CLF01)
    // -----------------------------------------------------------------------

    private void tryAutoClickTargetDevice() {
        if (!mAutoClickEnabled) return;
        if (mReconnectLaunched || mProxyConnected) return;
        if (mCurrentDeviceFilter == null || mCurrentDeviceFilter.isEmpty()) return;

        ScannerLiveData results =
                mViewModel.getScannerRepository().getScannerResults();
        if (results == null || results.getDevices() == null) {
            Log.d(TAG, "Auto-click: scan results null");
            return;
        }

        // Log all scanned devices for debugging
        Log.d(TAG, "Auto-click: total devices in scan = " + results.getDevices().size());
        for (ExtendedBluetoothDevice d : results.getDevices()) {
            Log.d(TAG, "  Scanned: name='" + d.getName()
                    + "' addr=" + d.getAddress()
                    + " rssi=" + d.getRssi());
        }

        // Build match patterns using SvgDeviceMapper for smarter matching
        String bleFilter = SvgDeviceMapper.getBleFilterName(mCurrentDeviceFilter);
        String filterLower = (bleFilter != null) ? bleFilter.toLowerCase() : mCurrentDeviceFilter.toLowerCase();

        // Also strip SW- for even looser matching
        String filterShort = filterLower
                .replace("sw-", "")
                .replace("sw_", "");

        Log.d(TAG, "Auto-click: filter='" + mCurrentDeviceFilter
                + "' resolvedTo='" + bleFilter + "' using='" + filterLower + "'");

        for (ExtendedBluetoothDevice device : results.getDevices()) {
            String name = device.getName();
            if (name == null) continue;
            String nameLower = name.toLowerCase();

            boolean matched = nameLower.contains(filterLower) || nameLower.contains(filterShort);

            if (!matched) continue;

            // RSSI filter check
            if (mCurrentSignalFilter != DevicesAdapter.SIGNAL_DEFAULT
                    && device.getRssi() < mCurrentSignalFilter) {
                Log.d(TAG, "Auto-click skipped — RSSI=" + device.getRssi()
                        + " below threshold=" + mCurrentSignalFilter);
                continue;
            }

            Log.i(TAG, "✅ Auto-click matched: '" + name
                    + "' filter='" + mCurrentDeviceFilter + "'");
            mAutoClickEnabled = false;
            onItemClick(device);
            return;
        }

        Log.d(TAG, "Auto-click: '" + mCurrentDeviceFilter + "' not found yet...");
    }

    // -----------------------------------------------------------------------
    // Filter
    // -----------------------------------------------------------------------

    private void applyFilterToAdapter() {
        if (!mScanWithProxyService || mSilentConnect
                || mShouldAutoConnectAfterProvisioning) return;
        if (adapter == null) return;

        String filter = SvgDeviceMapper.getBleFilterName(mCurrentDeviceFilter);
        if (filter == null) filter = mCurrentDeviceFilter;

        adapter.applyFilters(filter, mCurrentSignalFilter);
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
            Log.d(TAG, "onStart: reconnect in progress — skipping scan");
            return;
        }
        if (mViewModel.getBleMeshManager().isConnected()) {
            Log.d(TAG, "onStart: already connected — skipping scan");
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
            if (mSvgDeviceId != null) {
                intent.putExtra(Utils.EXTRA_SVG_DEVICE_ID, mSvgDeviceId);
            }
            if (mReceiveId != null) {
                intent.putExtra(DeviceDetailActivity.EXTRA_RECEIVE_ID, mReceiveId);
            }
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
            Log.d(TAG, "startScan blocked — reconnect in progress");
            return;
        }

        if (!mScanWithProxyService
                && (mSilentConnect || mShouldAutoConnectAfterProvisioning)
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
            if (mAutoConnectStarted) stopAutoConnectLoop();
            Log.d(TAG, "Bluetooth off");
            return;
        } else {
            binding.bluetoothOff.getRoot().setVisibility(View.GONE);
        }

        if (!state.isScanning()) {
            if (mShouldAutoConnectAfterProvisioning) {
                // Post-provisioning: scan only for proxy
                mViewModel.getScannerRepository().startScan(BleMeshManager.MESH_PROXY_UUID);

            } else if (mScanWithProxyService) {
                // ── PROVISIONING MODE: scan BOTH uuids ────────────────────
                // Device may be unprovisioned (0x1827) OR already provisioned (0x1828).
                // Dual scan ensures auto-click finds it either way.
                mViewModel.getScannerRepository().startScanDual(
                        BleMeshManager.MESH_PROVISIONING_UUID,
                        BleMeshManager.MESH_PROXY_UUID);

            } else {
                // Proxy-only mode (reconnect flow)
                mViewModel.getScannerRepository().startScan(BleMeshManager.MESH_PROXY_UUID);
            }

            if (!mSilentConnect && !mShouldAutoConnectAfterProvisioning) {
                binding.stateScanning.setVisibility(View.VISIBLE);
            }
        }

        if (!mSilentConnect && !mShouldAutoConnectAfterProvisioning) {
            binding.noDevices.getRoot().setVisibility(
                    (adapter != null && adapter.isEmpty()) ? View.VISIBLE : View.GONE);
        }

        if (!mScanWithProxyService
                && (mSilentConnect || mShouldAutoConnectAfterProvisioning)
                && targetProxyMac != null
                && !mAutoConnectStarted) {
            Log.d(TAG, "Starting auto-connect loop for: " + targetProxyMac);
            if (state.isBluetoothEnabled()) {
                startAutoConnectLoop();
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
            if (!mAutoConnectStarted) startAutoConnectLoop();
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
                                    "Failed to connect after provisioning.\n"
                                            + "Please try manual connection.");
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

                    if (mIsNewlyProvisioned || mShouldAutoConnectAfterProvisioning) {
                        ProvisionedMeshNode provNode =
                                mViewModel.getMeshRepository().getLastProvisionedNode();

                        if (provNode != null) {
                            mViewModel.getMeshRepository()
                                    .markSetupRequired(provNode.getUnicastAddress());
                            Log.d(TAG, "✅ markSetupRequired called for node=0x"
                                    + Integer.toHexString(provNode.getUnicastAddress()));
                        } else {
                            Log.e(TAG, "❌ markSetupRequired: getLastProvisionedNode() returned null");
                        }
                    }

                    final Intent intent = new Intent(
                            ScannerActivity.this,
                            ReconnectActivity.class);
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
        binding.textConnectingProgress.setVisibility(View.VISIBLE);
        long   elapsed = (System.currentTimeMillis() - mScanStartTime) / 1000;
        String mac     = formatMacForDisplay(targetProxyMac);
        String text = mShouldAutoConnectAfterProvisioning
                ? String.format("Connecting to proxy... (%ds)", elapsed)
                : String.format("Looking for device... (%ds)", elapsed);
        binding.textConnectingProgress.setText(text);
    }

    private void showTimeoutMessage() {
        runOnUiThread(() -> {
            binding.textConnectingProgress.setText(
                    String.format("Device %s not found after %ds.\nShowing available devices...",
                            formatMacForDisplay(targetProxyMac),
                            TARGET_CONNECT_TIMEOUT_MS / 1000));
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
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(null);
            getSupportActionBar().setSubtitle(null);
            getSupportActionBar().setDisplayHomeAsUpEnabled(false);
        }
        binding.recyclerViewBleDevices.setVisibility(View.GONE);
        binding.noDevices.getRoot().setVisibility(View.GONE);
        binding.bluetoothOff.getRoot().setVisibility(View.GONE);
        binding.noLocationPermission.getRoot().setVisibility(View.GONE);
        binding.noBluetoothPermissions.getRoot().setVisibility(View.GONE);
        binding.stateScanning.setVisibility(View.GONE);
        binding.connectivityProgressContainer.setVisibility(View.VISIBLE);
        binding.textConnectingProgress.setVisibility(View.VISIBLE);
        binding.textConnectingProgress.setText("Connecting to proxy...");
    }

    private void showScannerUI() {
        binding.appbarLayout.setVisibility(View.VISIBLE);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.title_scanner);
            getSupportActionBar().setSubtitle(mScanWithProxyService
                    ? R.string.sub_title_scanning_nodes
                    : R.string.sub_title_scanning_proxy_node);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
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
        if (mSvgDeviceId != null) {
            data.putExtra(Utils.EXTRA_SVG_DEVICE_ID, mSvgDeviceId);
        }
        if (mReceiveId != null) {
            data.putExtra(DeviceDetailActivity.EXTRA_RECEIVE_ID, mReceiveId);
        }
        setResult(Activity.RESULT_OK, data);
        finish();
    }
}