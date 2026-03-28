package no.nordicsemi.android.swaromapmesh;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.snackbar.Snackbar;

import java.util.Locale;

import dagger.hilt.android.AndroidEntryPoint;
import no.nordicsemi.android.swaromapmesh.adapter.ExtendedBluetoothDevice;
import no.nordicsemi.android.swaromapmesh.adapter.ProvisioningProgressAdapter;
import no.nordicsemi.android.swaromapmesh.databinding.ActivityMeshProvisionerBinding;
import no.nordicsemi.android.swaromapmesh.dialog.DialogFragmentAuthenticationInput;
import no.nordicsemi.android.swaromapmesh.dialog.DialogFragmentConfigurationComplete;
import no.nordicsemi.android.swaromapmesh.dialog.DialogFragmentProvisioningFailedError;
import no.nordicsemi.android.swaromapmesh.dialog.DialogFragmentSelectOOBType;
import no.nordicsemi.android.swaromapmesh.dialog.DialogFragmentUnicastAddress;
import no.nordicsemi.android.swaromapmesh.keys.AppKeysActivity;
import no.nordicsemi.android.swaromapmesh.node.dialog.DialogFragmentNodeName;
import no.nordicsemi.android.swaromapmesh.provisionerstates.ProvisioningCapabilities;
import no.nordicsemi.android.swaromapmesh.provisionerstates.ProvisioningFailedState;
import no.nordicsemi.android.swaromapmesh.provisionerstates.UnprovisionedMeshNode;
import no.nordicsemi.android.swaromapmesh.utils.AuthenticationOOBMethods;
import no.nordicsemi.android.swaromapmesh.utils.InputOOBAction;
import no.nordicsemi.android.swaromapmesh.utils.MeshParserUtils;
import no.nordicsemi.android.swaromapmesh.utils.OutputOOBAction;
import no.nordicsemi.android.swaromapmesh.utils.ProvisionerStates;
import no.nordicsemi.android.swaromapmesh.utils.StaticOOBType;
import no.nordicsemi.android.swaromapmesh.utils.Utils;
import no.nordicsemi.android.swaromapmesh.viewmodels.ProvisionerProgress;
import no.nordicsemi.android.swaromapmesh.viewmodels.ProvisioningViewModel;

import static no.nordicsemi.android.swaromapmesh.utils.Utils.RESULT_KEY;

@AndroidEntryPoint
public class ProvisioningActivity extends AppCompatActivity implements
        DialogFragmentOobPublicKey.DialogFragmentOobPublicKeysListener,
        DialogFragmentSelectOOBType.DialogFragmentSelectOOBTypeListener,
        DialogFragmentAuthenticationInput.ProvisionerInputFragmentListener,
        DialogFragmentNodeName.DialogFragmentNodeNameListener,
        DialogFragmentUnicastAddress.DialogFragmentUnicastAddressListener,
        DialogFragmentProvisioningFailedError.DialogFragmentProvisioningFailedErrorListener,
        DialogFragmentConfigurationComplete.ConfigurationCompleteListener {

    private static final String DIALOG_FRAGMENT_PROVISIONING_FAILED = "DIALOG_FRAGMENT_PROVISIONING_FAILED";
    private static final String DIALOG_FRAGMENT_AUTH_INPUT_TAG = "DIALOG_FRAGMENT_AUTH_INPUT_TAG";
    private static final String DIALOG_FRAGMENT_CONFIGURATION_STATUS = "DIALOG_FRAGMENT_CONFIGURATION_STATUS";
    private static final String TAG = "ProvisioningActivity";

    private ActivityMeshProvisionerBinding binding;
    private ProvisioningViewModel mViewModel;
    private ExtendedBluetoothDevice mDevice;
    private boolean isFirstDevice = true;

    // ✅ Store SVG device ID from intent
    private String mSvgDeviceId = null;

    private final ActivityResultLauncher<Intent> appKeySelector = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
        if (result.getResultCode() == RESULT_OK && result.getData() != null) {
            final ApplicationKey appKey = result.getData().getParcelableExtra(RESULT_KEY);
            if (appKey != null) {
                mViewModel.getNetworkLiveData().setSelectedAppKey(appKey);
            }
        }
    });

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMeshProvisionerBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        mViewModel = new ViewModelProvider(this).get(ProvisioningViewModel.class);

        final Intent intent = getIntent();
        mDevice = intent.getParcelableExtra(Utils.EXTRA_DEVICE);

        // ✅ Get SVG device ID from intent
        mSvgDeviceId = intent.getStringExtra(Utils.EXTRA_SVG_DEVICE_ID);
        Log.d(TAG, "onCreate — SVG Device ID: " + mSvgDeviceId);

        if (mDevice == null) {
            finish();
            return;
        }

        final String deviceName = mDevice.getName() != null ? mDevice.getName() : getString(R.string.unknown_device);
        final String deviceAddress = mDevice.getAddress() != null ? mDevice.getAddress() : getString(R.string.unknown_address);

        setSupportActionBar(binding.toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(deviceName);
            getSupportActionBar().setSubtitle(deviceAddress);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        if (savedInstanceState == null) {
            mViewModel.connect(this, mDevice, false);
            mViewModel.setDeviceMacAddress(mDevice.getAddress());
            Log.d(TAG, "MAC Address stored: " + mDevice.getAddress());
        }

        binding.containerName.image
                .setBackground(ContextCompat.getDrawable(this, R.drawable.ic_label_outline));
        binding.containerName.title.setText(R.string.summary_name);
        binding.containerName.text.setVisibility(View.VISIBLE);
        binding.containerName.getRoot().setOnClickListener(v -> {
            final DialogFragmentNodeName dialogFragmentNodeName = DialogFragmentNodeName.newInstance(deviceName);
            dialogFragmentNodeName.show(getSupportFragmentManager(), null);
        });

        binding.containerUnicast.image
                .setBackground(ContextCompat.getDrawable(this, R.drawable.ic_lan_24dp));
        binding.containerUnicast.title.setText(R.string.title_unicast_address);
        binding.containerUnicast.text.setVisibility(View.VISIBLE);
        binding.containerUnicast.getRoot().setOnClickListener(v -> {
            final UnprovisionedMeshNode node = mViewModel.getUnprovisionedMeshNode().getValue();
            if (node != null && node.getProvisioningCapabilities() != null) {
                final int elementCount = node.getProvisioningCapabilities().getNumberOfElements();
                final DialogFragmentUnicastAddress dialogFragmentFlags = DialogFragmentUnicastAddress.
                        newInstance(mViewModel.getNetworkLiveData().getMeshNetwork().getUnicastAddress(), elementCount);
                dialogFragmentFlags.show(getSupportFragmentManager(), null);
            }
        });

        binding.containerAppKeys.image
                .setBackground(ContextCompat.getDrawable(this, R.drawable.ic_vpn_key_24dp));
        binding.containerAppKeys.title.setText(R.string.title_app_keys);
        binding.containerAppKeys.text.setVisibility(View.VISIBLE);
        binding.containerAppKeys.getRoot().setOnClickListener(v -> {
            final Intent manageAppKeys = new Intent(ProvisioningActivity.this, AppKeysActivity.class);
            manageAppKeys.putExtra(Utils.EXTRA_DATA, Utils.ADD_APP_KEY);
            appKeySelector.launch(manageAppKeys);
        });

        mViewModel.getConnectionState().observe(this, binding.connectionState::setText);

        mViewModel.isConnected().observe(this, connected -> {
            final boolean isComplete = mViewModel.isProvisioningComplete();
            if (isComplete) {
                return;
            }

            if (connected != null && !connected)
                finish();
        });

        mViewModel.isDeviceReady().observe(this, deviceReady -> {
            if (mViewModel.getBleMeshManager().isDeviceReady()) {
                binding.connectivityProgressContainer.setVisibility(View.GONE);
                final boolean isComplete = mViewModel.isProvisioningComplete();
                if (isComplete) {
                    binding.provisioningProgressBar.setVisibility(View.VISIBLE);
                    binding.infoProvisioningStatusContainer.getRoot().setVisibility(View.VISIBLE);
                    setupProvisionerStateObservers();
                    return;
                }
                binding.dataContainer.setVisibility(View.VISIBLE);
            }
        });

        mViewModel.isReconnecting().observe(this, isReconnecting -> {
            if (isReconnecting != null && isReconnecting) {
                mViewModel.getUnprovisionedMeshNode().removeObservers(this);
                binding.infoProvisioningStatusContainer.getRoot().setVisibility(View.GONE);
                binding.dataContainer.setVisibility(View.GONE);
                binding.provisioningProgressBar.setVisibility(View.GONE);
                binding.connectivityProgressContainer.setVisibility(View.VISIBLE);
            } else {
                setResultIntent();
            }
        });

        mViewModel.getNetworkLiveData().observe(this, meshNetworkLiveData -> {
            binding.containerName.text.setText(meshNetworkLiveData.getNodeName());
            final ApplicationKey applicationKey = meshNetworkLiveData.getSelectedAppKey();
            if (applicationKey != null) {
                binding.containerAppKeys.text.setText(MeshParserUtils.bytesToHex(applicationKey.getKey(), false));
            } else {
                binding.containerAppKeys.text.setText(getString(R.string.no_app_keys));
            }
            binding.containerUnicast.text.setText(getString(R.string.hex_format,
                    String.format(Locale.US, "%04X", meshNetworkLiveData.getMeshNetwork().getUnicastAddress())));
        });

        mViewModel.getUnprovisionedMeshNode().observe(this, meshNode -> {
            if (meshNode != null) {
                final ProvisioningCapabilities capabilities = meshNode.getProvisioningCapabilities();
                if (capabilities != null) {
                    binding.provisioningProgressBar.setVisibility(View.INVISIBLE);
                    binding.actionProvisionDevice.setText(R.string.provision_action);
                    binding.containerUnicast.getRoot().setVisibility(View.VISIBLE);
                    final MeshNetwork network = mViewModel.getNetworkLiveData().getMeshNetwork();
                    if (network != null) {
                        try {
                            final int elementCount = capabilities.getNumberOfElements();
                            final Provisioner provisioner = network.getSelectedProvisioner();

                            // Log network state
                            Log.d(TAG, "=== Network State ===");
                            Log.d(TAG, "Number of nodes in network: " + network.getNodes().size());

                            // ALWAYS TRY TO SET TO 0x0005 FIRST
                            Log.d(TAG, "Attempting to set unicast address to 0x0005");

                            try {
                                // Directly assign 0x0005
                                network.assignUnicastAddress(0x0005);
                                Log.d(TAG, "SUCCESS: Assigned unicast address 0x0005");
                                isFirstDevice = true;
                            } catch (IllegalArgumentException e) {
                                // If 0x0005 is taken, find next available starting from 0x0005
                                Log.w(TAG, "Could not assign 0x0005: " + e.getMessage());
                                Log.d(TAG, "Searching for next available address starting from 0x0005");

                                int unicastAddress = 0x0005;
                                boolean found = false;
                                int maxAttempts = 100; // Prevent infinite loop
                                int attempts = 0;

                                while (!found && attempts < maxAttempts && unicastAddress < 0x7FFF) {
                                    try {
                                        network.assignUnicastAddress(unicastAddress);
                                        Log.d(TAG, "Found available address: " + String.format("0x%04X", unicastAddress));
                                        found = true;
                                        isFirstDevice = (unicastAddress == 0x0005);
                                    } catch (IllegalArgumentException ex) {
                                        // Address is taken, try next
                                        unicastAddress += elementCount;
                                        attempts++;
                                    }
                                }

                                if (!found) {
                                    // Fallback to nextAvailable method
                                    Log.w(TAG, "Could not find address sequentially, using nextAvailableUnicastAddress");
                                    unicastAddress = network.nextAvailableUnicastAddress(elementCount, provisioner);
                                    network.assignUnicastAddress(unicastAddress);
                                    Log.d(TAG, "Assigned via nextAvailable: " + String.format("0x%04X", unicastAddress));
                                    isFirstDevice = false;
                                }
                            }

                        } catch (IllegalArgumentException ex) {
                            binding.actionProvisionDevice.setEnabled(false);
                            mViewModel.displaySnackBar(this, binding.coordinator,
                                    ex.getMessage() == null ? getString(R.string.unknown_error) : ex.getMessage(),
                                    Snackbar.LENGTH_LONG);
                            Log.e(TAG, "Error assigning unicast address: " + ex.getMessage());
                        }
                    }

                    if (meshNode.getMacAddress() == null || meshNode.getMacAddress().isEmpty()) {
                        meshNode.setMacAddress(mDevice.getAddress());
                        Log.d(TAG, "MAC address set in UnprovisionedMeshNode: " + mDevice.getAddress());
                    }
                }
            }
        });

        binding.actionProvisionDevice.setOnClickListener(v -> {
            Log.d(TAG, "CLICK: Provision button pressed");

            final UnprovisionedMeshNode node = mViewModel.getUnprovisionedMeshNode().getValue();

            Log.d(TAG, "STEP 1: Getting UnprovisionedMeshNode");

            if (node == null) {
                Log.d(TAG, "STEP 1 RESULT: Node is NULL → going for IDENTIFY");

                mDevice.setName(mViewModel.getNetworkLiveData().getNodeName());
                Log.d(TAG, "STEP 2: Device name set: " + mDevice.getName());

                mViewModel.getNrfMeshRepository().identifyNode(mDevice);
                Log.d(TAG, "STEP 3: identifyNode() called");

                return;
            }

            Log.d(TAG, "STEP 1 RESULT: Node is NOT NULL → proceed to provisioning");

            try {
                Log.d(TAG, "STEP 2: Checking MAC Address");

                if (node.getMacAddress() == null || node.getMacAddress().isEmpty()) {
                    Log.d(TAG, "MAC is NULL or EMPTY → setting MAC");

                    node.setMacAddress(mDevice.getAddress());
                    Log.d(TAG, "STEP 2 RESULT: MAC set: " + mDevice.getAddress());
                } else {
                    Log.d(TAG, "STEP 2 RESULT: MAC already exists: " + node.getMacAddress());
                }

                Log.d(TAG, "STEP 3: Setting Node Name");
                node.setNodeName(mViewModel.getNetworkLiveData().getNodeName());
                Log.d(TAG, "STEP 3 RESULT: Node name set: " + node.getNodeName());

                // Mesh Network check
                Log.d(TAG, "STEP 4: Fetching MeshNetwork");
                final MeshNetwork network = mViewModel.getNetworkLiveData().getMeshNetwork();

                if (network != null) {
                    Log.d(TAG, "STEP 4 RESULT: MeshNetwork NOT NULL");

                    int currentAddress = network.getUnicastAddress();
                    Log.d(TAG, "STEP 5: Current Unicast Address: " +
                            String.format("0x%04X", currentAddress));

                    if (currentAddress != 0x0005) {
                        Log.d(TAG, "STEP 6: Trying to force Unicast Address → 0x0005");

                        try {
                            network.assignUnicastAddress(0x0005);
                            Log.d(TAG, "STEP 6 SUCCESS: Forced to 0x0005");
                        } catch (IllegalArgumentException e) {
                            Log.e(TAG, "STEP 6 FAILED: Cannot assign 0x0005 → " + e.getMessage());
                        }
                    } else {
                        Log.d(TAG, "STEP 6 SKIPPED: Already 0x0005");
                    }

                } else {
                    Log.e(TAG, "STEP 4 ERROR: MeshNetwork is NULL");
                }

                Log.d(TAG, "STEP 7: Setting up observers");
                setupProvisionerStateObservers();
                Log.d(TAG, "STEP 7 DONE");

                Log.d(TAG, "STEP 8: Showing Progress Bar");
                binding.provisioningProgressBar.setVisibility(View.VISIBLE);

                Log.d(TAG, "STEP 9: Starting Provisioning");
                mViewModel.getMeshManagerApi().startProvisioning(node);
                Log.d(TAG, "STEP 9 DONE: startProvisioning() called");

                if (network != null) {
                    Log.d(TAG, "FINAL: Provisioning started with address: " +
                            String.format("0x%04X", network.getUnicastAddress()));
                }

            } catch (IllegalArgumentException ex) {
                Log.e(TAG, "ERROR: Exception while provisioning → " + ex.getMessage());

                mViewModel.displaySnackBar(
                        this,
                        binding.coordinator,
                        ex.getMessage() == null
                                ? getString(R.string.unknown_error)
                                : ex.getMessage(),
                        Snackbar.LENGTH_LONG
                );
            }

            Log.d(TAG, "END: Click handler finished");
        });

        if (savedInstanceState == null) {
            mViewModel.getNetworkLiveData().resetSelectedAppKey();
        }
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            onBackPressed();
            return true;
        }
        return false;
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
        disconnect();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        disconnect();
    }

    @Override
    public void onPinInputComplete(final String pin) {
        mViewModel.getMeshManagerApi().setProvisioningAuthentication(pin);
    }

    @Override
    public void onPinInputCanceled() {
        final String message = getString(R.string.provisioning_cancelled);
        final Snackbar snackbar = Snackbar.make(binding.coordinator, message, Snackbar.LENGTH_LONG);
        snackbar.show();
        disconnect();
    }

    @Override
    public boolean onNodeNameUpdated(@NonNull final String nodeName) {
        mViewModel.getNetworkLiveData().setNodeName(nodeName);
        return true;
    }

    @Override
    public boolean setUnicastAddress(final int unicastAddress) {
        final MeshNetwork network = mViewModel.getMeshManagerApi().getMeshNetwork();
        if (network != null) {
            try {
                boolean result = network.assignUnicastAddress(unicastAddress);
                if (result) {
                    Log.d(TAG, "Unicast address manually set to: " + String.format("0x%04X", unicastAddress));
                }
                return result;
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "Error setting unicast address: " + e.getMessage());
                return false;
            }
        }
        return false;
    }

    @Override
    public int getNextUnicastAddress(final int elementCount) {
        final MeshNetwork network = mViewModel.getNetworkLiveData().getMeshNetwork();

        Log.d(TAG, "getNextUnicastAddress called - Returning 0x0005 (validation will happen in assign)");
        return 0x0005;
    }

    @Override
    public void onProvisioningFailed() {
        disconnect();
        setResult(Activity.RESULT_CANCELED);
        finish();
    }

    @Override
    public void onPublicKeyDialogCancelled() {
        disconnect();
        finish();
    }

    private void disconnect() {
        mViewModel.getUnprovisionedMeshNode().removeObservers(this);
        mViewModel.disconnect();
    }

    public void setupProvisionerStateObservers() {
        binding.infoProvisioningStatusContainer.getRoot().setVisibility(View.VISIBLE);

        final RecyclerView recyclerView = binding.infoProvisioningStatusContainer.recyclerViewProvisioningProgress;
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        final ProvisioningProgressAdapter adapter = new ProvisioningProgressAdapter(mViewModel.getProvisioningStatus());
        recyclerView.setAdapter(adapter);

        mViewModel.getProvisioningStatus().observe(this, provisioningStateLiveData -> {

            if (provisioningStateLiveData == null) return;

            final ProvisionerProgress provisionerProgress = provisioningStateLiveData.getProvisionerProgress();

            if (provisionerProgress == null) return;

            final ProvisionerStates state = provisionerProgress.getState();

            switch (state) {

                case PROVISIONING_COMPLETE:
                    Log.d(TAG, "✅ Provisioning Completed");

                    runOnUiThread(() -> {
                        binding.provisioningProgressBar.setVisibility(View.GONE);

                        // 🔥 STOP OBSERVER (IMPORTANT)
                        mViewModel.getProvisioningStatus().removeObservers(this);

                        // 🔥 Direct back (no UI)
                        setResultIntent();

                    });
                    break;

                case PROVISIONING_FAILED:
                    Log.e(TAG, "❌ Provisioning Failed");

                    runOnUiThread(() -> {
                        binding.provisioningProgressBar.setVisibility(View.GONE);

                        // optional: enable retry button
                        binding.actionProvisionDevice.setEnabled(true);

                        mViewModel.getProvisioningStatus().removeObservers(this);
                    });
                    break;

                default:
                    // ❌ Ignore all other states (no UI, no dialog)
                    break;
            }

            // 👇 Always hide extra UI
            binding.dataContainer.setVisibility(View.GONE);
        });
    }

    @Override
    public void onConfigurationCompleted() {
        setResultIntent();
    }

    private void setResultIntent() {
        final Intent returnIntent = new Intent();

        // ✅ Pass SVG device ID back up the chain
        if (mSvgDeviceId != null) {
            returnIntent.putExtra(Utils.EXTRA_SVG_DEVICE_ID, mSvgDeviceId);
            Log.d(TAG, "Returning svgDeviceId: " + mSvgDeviceId);
        }

        returnIntent.putExtra(Utils.EXTRA_DEVICE, mDevice);
        returnIntent.putExtra(Utils.EXTRA_TARGET_PROXY_MAC, mDevice.getAddress());
        returnIntent.putExtra(Utils.EXTRA_AUTO_CONNECT_AFTER_PROVISIONING, true);

        if (mViewModel.isProvisioningComplete()) {
            returnIntent.putExtra(Utils.PROVISIONING_COMPLETED, true);
            returnIntent.putExtra(Utils.EXTRA_NEWLY_PROVISIONED_NODE, true);

            final ProvisionerProgress progress =
                    mViewModel.getProvisioningStatus().getProvisionerProgress();
            if (progress != null
                    && progress.getState() == ProvisionerStates.PROVISIONER_UNASSIGNED) {
                returnIntent.putExtra(Utils.PROVISIONER_UNASSIGNED, true);
            } else {
                if (mViewModel.isCompositionDataStatusReceived()) {
                    returnIntent.putExtra(Utils.COMPOSITION_DATA_COMPLETED, true);
                    if (mViewModel.isDefaultTtlReceived()) {
                        returnIntent.putExtra(Utils.DEFAULT_GET_COMPLETED, true);
                        if (mViewModel.getNetworkLiveData().getMeshNetwork()
                                .getAppKeys().isEmpty() || mViewModel.isAppKeyAddCompleted()) {
                            returnIntent.putExtra(Utils.APP_KEY_ADD_COMPLETED, true);
                        }
                    }
                }
            }

            MeshNetwork network = mViewModel.getNetworkLiveData().getMeshNetwork();
            if (network != null) {
                Log.d(TAG, "PROVISIONING COMPLETED unicast: " +
                        String.format("0x%04X", network.getUnicastAddress()));
            }
        }

        setResult(Activity.RESULT_OK, returnIntent);
        finish();
    }

    @Override
    public void onPublicKeyAdded(@Nullable final byte[] publicKey) {
        final UnprovisionedMeshNode node = mViewModel.getUnprovisionedMeshNode().getValue();
        if (node != null) {
            node.setProvisioneePublicKeyXY(publicKey);

            if (node.getMacAddress() == null || node.getMacAddress().isEmpty()) {
                node.setMacAddress(mDevice.getAddress());
                Log.d(TAG, "MAC address set in onPublicKeyAdded: " + mDevice.getAddress());
            }

            if (node.getProvisioningCapabilities().getAvailableOOBTypes().size() == 1 &&
                    node.getProvisioningCapabilities().getAvailableOOBTypes().get(0) == AuthenticationOOBMethods.NO_OOB_AUTHENTICATION) {
                onNoOOBSelected();
            } else {
                final DialogFragmentSelectOOBType fragmentSelectOOBType = DialogFragmentSelectOOBType.newInstance(node.getProvisioningCapabilities());
                fragmentSelectOOBType.show(getSupportFragmentManager(), null);
            }
        }
    }

    @Override
    public void onNoOOBSelected() {
        final UnprovisionedMeshNode node = mViewModel.getUnprovisionedMeshNode().getValue();
        if (node != null) {
            try {
                if (node.getMacAddress() == null || node.getMacAddress().isEmpty()) {
                    node.setMacAddress(mDevice.getAddress());
                }

                node.setNodeName(mViewModel.getNetworkLiveData().getNodeName());

                // Ensure unicast address is set correctly
                final MeshNetwork network = mViewModel.getNetworkLiveData().getMeshNetwork();
                if (network != null && network.getUnicastAddress() != 0x0005) {
                    try {
                        network.assignUnicastAddress(0x0005);
                        Log.d(TAG, "onNoOOBSelected: Set unicast address to 0x0005");
                    } catch (IllegalArgumentException e) {
                        Log.w(TAG, "onNoOOBSelected: Could not set address to 0x0005");
                    }
                }

                setupProvisionerStateObservers();
                binding.provisioningProgressBar.setVisibility(View.VISIBLE);
                mViewModel.getMeshManagerApi().startProvisioning(node);
            } catch (IllegalArgumentException ex) {
                mViewModel.displaySnackBar(this, binding.coordinator,
                        ex.getMessage() == null ? getString(R.string.unknown_error) : ex.getMessage(),
                        Snackbar.LENGTH_LONG);
            }
        }
    }

    @Override
    public void onStaticOOBSelected(final StaticOOBType staticOOBType) {
        final UnprovisionedMeshNode node = mViewModel.getUnprovisionedMeshNode().getValue();
        if (node != null) {
            try {
                if (node.getMacAddress() == null || node.getMacAddress().isEmpty()) {
                    node.setMacAddress(mDevice.getAddress());
                }

                node.setNodeName(mViewModel.getNetworkLiveData().getNodeName());

                // Ensure unicast address is set correctly
                final MeshNetwork network = mViewModel.getNetworkLiveData().getMeshNetwork();
                if (network != null && network.getUnicastAddress() != 0x0005) {
                    try {
                        network.assignUnicastAddress(0x0005);
                        Log.d(TAG, "onStaticOOBSelected: Set unicast address to 0x0005");
                    } catch (IllegalArgumentException e) {
                        Log.w(TAG, "onStaticOOBSelected: Could not set address to 0x0005");
                    }
                }

                setupProvisionerStateObservers();
                binding.provisioningProgressBar.setVisibility(View.VISIBLE);
                mViewModel.getMeshManagerApi().startProvisioningWithStaticOOB(node);
            } catch (IllegalArgumentException ex) {
                mViewModel.displaySnackBar(this, binding.coordinator,
                        ex.getMessage() == null ? getString(R.string.unknown_error) : ex.getMessage(),
                        Snackbar.LENGTH_LONG);
            }
        }
    }

    @Override
    public void onOutputOOBActionSelected(final OutputOOBAction action) {
        final UnprovisionedMeshNode node = mViewModel.getUnprovisionedMeshNode().getValue();
        if (node != null) {
            try {
                if (node.getMacAddress() == null || node.getMacAddress().isEmpty()) {
                    node.setMacAddress(mDevice.getAddress());
                }

                node.setNodeName(mViewModel.getNetworkLiveData().getNodeName());

                // Ensure unicast address is set correctly
                final MeshNetwork network = mViewModel.getNetworkLiveData().getMeshNetwork();
                if (network != null && network.getUnicastAddress() != 0x0005) {
                    try {
                        network.assignUnicastAddress(0x0005);
                        Log.d(TAG, "onOutputOOBActionSelected: Set unicast address to 0x0005");
                    } catch (IllegalArgumentException e) {
                        Log.w(TAG, "onOutputOOBActionSelected: Could not set address to 0x0005");
                    }
                }

                setupProvisionerStateObservers();
                binding.provisioningProgressBar.setVisibility(View.VISIBLE);
                mViewModel.getMeshManagerApi().startProvisioningWithOutputOOB(node, action);
            } catch (IllegalArgumentException ex) {
                mViewModel.displaySnackBar(this, binding.coordinator,
                        ex.getMessage() == null ? getString(R.string.unknown_error) : ex.getMessage(),
                        Snackbar.LENGTH_LONG);
            }
        }
    }

    @Override
    public void onInputOOBActionSelected(final InputOOBAction action) {
        final UnprovisionedMeshNode node = mViewModel.getUnprovisionedMeshNode().getValue();
        if (node != null) {
            try {
                if (node.getMacAddress() == null || node.getMacAddress().isEmpty()) {
                    node.setMacAddress(mDevice.getAddress());
                }

                node.setNodeName(mViewModel.getNetworkLiveData().getNodeName());

                // Ensure unicast address is set correctly
                final MeshNetwork network = mViewModel.getNetworkLiveData().getMeshNetwork();
                if (network != null && network.getUnicastAddress() != 0x0005) {
                    try {
                        network.assignUnicastAddress(0x0005);
                        Log.d(TAG, "onInputOOBActionSelected: Set unicast address to 0x0005");
                    } catch (IllegalArgumentException e) {
                        Log.w(TAG, "onInputOOBActionSelected: Could not set address to 0x0005");
                    }
                }

                setupProvisionerStateObservers();
                binding.provisioningProgressBar.setVisibility(View.VISIBLE);
                mViewModel.getMeshManagerApi().startProvisioningWithInputOOB(node, action);
            } catch (IllegalArgumentException ex) {
                mViewModel.displaySnackBar(this, binding.coordinator,
                        ex.getMessage() == null ? getString(R.string.unknown_error) : ex.getMessage(),
                        Snackbar.LENGTH_LONG);
            }
        }
    }
}