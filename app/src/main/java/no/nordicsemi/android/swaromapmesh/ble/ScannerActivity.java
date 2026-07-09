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
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import dagger.hilt.android.AndroidEntryPoint;
import no.nordicsemi.android.swaromapmesh.DeviceDetailActivity;
import no.nordicsemi.android.swaromapmesh.ProvisioningActivity;
import no.nordicsemi.android.swaromapmesh.R;
import no.nordicsemi.android.swaromapmesh.adapter.ExtendedBluetoothDevice;
import no.nordicsemi.android.swaromapmesh.ble.adapter.DevicesAdapter;
import no.nordicsemi.android.swaromapmesh.databinding.ActivityScannerBinding;
import no.nordicsemi.android.swaromapmesh.transport.ProvisionedMeshNode;
import no.nordicsemi.android.swaromapmesh.utils.Utils;
import no.nordicsemi.android.swaromapmesh.viewmodels.ScannerLiveData;
import no.nordicsemi.android.swaromapmesh.viewmodels.ScannerStateLiveData;
import no.nordicsemi.android.swaromapmesh.viewmodels.ScannerViewModel;
import no.nordicsemi.android.swaromapmesh.viewmodels.SharedViewModel;

@AndroidEntryPoint
public class ScannerActivity extends AppCompatActivity implements
        DevicesAdapter.OnItemClickListener {

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

                        Log.d(TAG, "Provisioning complete for MAC: " + mProvisionedDeviceMac +
                                " with SVG ID: " + mSvgDeviceId);

                        showConnectingUI();
                        binding.textConnectingProgress.setText("Provisioning complete!\nConnecting to proxy...");

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

                    final Intent returnIntent = new Intent();
                    returnIntent.putExtra(Utils.EXTRA_NEWLY_PROVISIONED_NODE, mIsNewlyProvisioned);
                    returnIntent.putExtra(Utils.PROVISIONING_COMPLETED, true);
                    if (mSvgDeviceId != null) {
                        returnIntent.putExtra(Utils.EXTRA_SVG_DEVICE_ID, mSvgDeviceId);
                    }
                    if (mReceiveId != null) {
                        returnIntent.putExtra(DeviceDetailActivity.EXTRA_RECEIVE_ID, mReceiveId);
                    }

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

        if (getIntent() != null) {
            mSvgDeviceId = getIntent().getStringExtra(Utils.EXTRA_SVG_DEVICE_ID);
            mReceiveId   = getIntent().getStringExtra(DeviceDetailActivity.EXTRA_RECEIVE_ID);

            mScanWithProxyService = getIntent().getBooleanExtra(
                    Utils.EXTRA_DATA_PROVISIONING_SERVICE, true);
            mSilentConnect        = getIntent().getBooleanExtra(
                    Utils.EXTRA_SILENT_CONNECT, false);

            String autoFilterDevice = getIntent().getStringExtra(
                    DeviceDetailActivity.EXTRA_AUTO_FILTER_DEVICE);
            if (autoFilterDevice != null && !autoFilterDevice.isEmpty()) {
                mSharedViewModel.setSelectedDevice(autoFilterDevice);
                mSharedViewModel.setDeviceNameFilter("");
            } else if (savedInstanceState == null) {
                mSharedViewModel.setSelectedDevice("All Device");
                mSharedViewModel.setSignalThreshold(DevicesAdapter.SIGNAL_DEFAULT);
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

                    showConnectingUI();
                    binding.textConnectingProgress.setText("Connecting to proxy...");

                    startAutoConnectAfterProvisioning();
                }
            } else {
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

        final RecyclerView recyclerViewDevices = binding.recyclerViewBleDevices;
        recyclerViewDevices.setLayoutManager(new LinearLayoutManager(this));

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
            applyFilterToAdapter();
        });

        mSharedViewModel.getSignalThreshold().observe(this, threshold -> {
            mCurrentSignalFilter = threshold != null
                    ? threshold : DevicesAdapter.SIGNAL_DEFAULT;
            applyFilterToAdapter();
        });

        mViewModel.getScannerRepository().getScannerResults().observe(this, scannerLiveData -> {
            applyFilterToAdapter();
        });
    }

    private void applyFilterToAdapter() {
        if (mSilentConnect || mShouldAutoConnectAfterProvisioning) return;
        if (adapter == null) return;
        adapter.applyFilters(mCurrentDeviceFilter, mCurrentSignalFilter);
        if (!mSilentConnect && !mShouldAutoConnectAfterProvisioning) {
            binding.noDevices.getRoot().setVisibility(
                    adapter.isEmpty() ? View.VISIBLE : View.GONE);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (mProxyConnected || mReconnectLaunched) {
            return;
        }
        if (mViewModel.getBleMeshManager().isConnected()) {
            return;
        }
        mScanStartTime = System.currentTimeMillis();
        mViewModel.getScannerRepository().getScannerState().startScanning();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (mProxyConnected || mReconnectLaunched) {
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
        }
        else {
            final Intent intent = new Intent(this, ReconnectActivity.class);
            intent.putExtra(Utils.EXTRA_DEVICE, device);
            intent.putExtra(Utils.EXTRA_SILENT_CONNECT, false);
            reconnect.launch(intent);
        }
    }

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

    private void startScan(final ScannerStateLiveData state) {
        if (mProxyConnected || mReconnectLaunched) {
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

        if (!mScanWithProxyService
                && (mSilentConnect || mShouldAutoConnectAfterProvisioning)
                && targetProxyMac != null
                && !mAutoConnectStarted) {
            if (state.isBluetoothEnabled()) {
                startAutoConnectLoop();
            }
        }
    }

    private void startAutoConnectAfterProvisioning() {
        if (mProvisionedDeviceMac == null) return;
        targetProxyMac = mProvisionedDeviceMac;
        stopScan();

        new Handler().postDelayed(() -> runOnUiThread(() -> {
            if (mReconnectLaunched || mProxyConnected) return;
            mViewModel.getScannerRepository().startScan(BleMeshManager.MESH_PROXY_UUID);
            if (!mAutoConnectStarted) startAutoConnectLoop();
        }), AUTO_CONNECT_AFTER_PROVISIONING_DELAY);
    }

    private void startAutoConnectLoop() {
        if (mAutoConnectStarted) return;

        mAutoConnectStarted = true;
        mScanStartTime      = System.currentTimeMillis();

        mAutoConnectRunnable = new Runnable() {
            @Override
            public void run() {
                if (mProxyConnected || mReconnectLaunched) {
                    stopAutoConnectLoop();
                    return;
                }

                long elapsed = System.currentTimeMillis() - mScanStartTime;

                if (elapsed > TARGET_CONNECT_TIMEOUT_MS) {
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
                    mReconnectLaunched  = true;
                    mAutoConnectStarted = false;

                    stopScan();
                    stopAutoConnectLoop();

                    binding.connectivityProgressContainer.setVisibility(View.GONE);

                    if (mIsNewlyProvisioned || mShouldAutoConnectAfterProvisioning) {
                        ProvisionedMeshNode provNode =
                                mViewModel.getMeshRepository().getLastProvisionedNode();

                        if (provNode != null) {
                            mViewModel.getMeshRepository()
                                    .markSetupRequired(provNode.getUnicastAddress());
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
        mSilentConnect                      = false;

        stopAutoConnectLoop();
        if (adapter != null) {
            applyFilterToAdapter();
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
