package no.nordicsemi.android.swaromapmesh.node;

import static no.nordicsemi.android.swaromapmesh.utils.MeshParserUtils.USE_DEFAULT_TTL;
import static no.nordicsemi.android.swaromapmesh.utils.MeshParserUtils.isDefaultPublishTtl;
import static no.nordicsemi.android.swaromapmesh.utils.Utils.RESULT_KEY;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.ViewModelProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import dagger.hilt.android.AndroidEntryPoint;
import no.nordicsemi.android.swaromapmesh.ApplicationKey;
import no.nordicsemi.android.swaromapmesh.Group;
import no.nordicsemi.android.swaromapmesh.GroupCallbacks;
import no.nordicsemi.android.swaromapmesh.MeshNetwork;
import no.nordicsemi.android.swaromapmesh.R;
import no.nordicsemi.android.swaromapmesh.databinding.ActivityPublicationSettingsBinding;
import no.nordicsemi.android.swaromapmesh.dialog.DialogFragmentError;
import no.nordicsemi.android.swaromapmesh.keys.AppKeysActivity;
import no.nordicsemi.android.swaromapmesh.node.dialog.DestinationAddressCallbacks;
import no.nordicsemi.android.swaromapmesh.node.dialog.DialogFragmentPublishAddress;
import no.nordicsemi.android.swaromapmesh.node.dialog.DialogFragmentPublishTtl;
import no.nordicsemi.android.swaromapmesh.node.dialog.DialogFragmentTtl;
import no.nordicsemi.android.swaromapmesh.transport.Element;
import no.nordicsemi.android.swaromapmesh.transport.MeshMessage;
import no.nordicsemi.android.swaromapmesh.transport.MeshModel;
import no.nordicsemi.android.swaromapmesh.transport.ProvisionedMeshNode;
import no.nordicsemi.android.swaromapmesh.transport.PublicationSettings;
import no.nordicsemi.android.swaromapmesh.utils.MeshAddress;
import no.nordicsemi.android.swaromapmesh.utils.Utils;
import no.nordicsemi.android.swaromapmesh.viewmodels.BaseActivity;
import no.nordicsemi.android.swaromapmesh.viewmodels.ClientElementStore;
import no.nordicsemi.android.swaromapmesh.viewmodels.PublicationViewModel;
import no.nordicsemi.android.swaromapmesh.viewmodels.SharedViewModel;

@AndroidEntryPoint
public class PublicationSettingsActivity extends BaseActivity implements
        GroupCallbacks, DestinationAddressCallbacks,
        DialogFragmentTtl.DialogFragmentTtlListener {

    private static final String TAG        = "PublicationSettings";
    private static final String PREFS_NAME = "mesh_prefs";

    private static final int MIN_PUBLICATION_INTERVAL = 0;
    private static final int MAX_PUBLICATION_INTERVAL = 234;

    private ActivityPublicationSettingsBinding binding;
    private SharedViewModel    mSharedViewModel;
    private PublicationViewModel mPublicationViewModel;

    // Resolved server element ID for this session
    private int mCurrentServerElementId = -1;

    private final ActivityResultLauncher<Intent> appKeySelector =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                            final ApplicationKey appKey =
                                    result.getData().getParcelableExtra(RESULT_KEY);
                            if (appKey != null) {
                                mPublicationViewModel.setAppKeyIndex(appKey.getKeyIndex());
                                binding.appKey.setText(getString(
                                        R.string.app_key_index, appKey.getKeyIndex()));
                            }
                        }
                    });

    // =========================================================================
    // onCreate
    // =========================================================================
    @Override
    protected void onCreate(@Nullable final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityPublicationSettingsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        mPublicationViewModel = new ViewModelProvider(this).get(PublicationViewModel.class);
        mViewModel            = mPublicationViewModel;
        mSharedViewModel      = new ViewModelProvider(this).get(SharedViewModel.class);
        initialize();

        final MeshModel meshModel = mPublicationViewModel.getSelectedModel().getValue();
        if (meshModel == null) {
            Log.e(TAG, "No model selected - finishing");
            finish();
            return;
        }

        setSupportActionBar(binding.toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setHomeAsUpIndicator(R.drawable.ic_close);
            getSupportActionBar().setTitle(R.string.title_publication_settings);
        }

        setupScrollListener();
        setupClickListeners(meshModel);
        setupSliders();

        if (savedInstanceState == null) {
            mPublicationViewModel.setPublicationValues(
                    meshModel.getPublicationSettings(),
                    meshModel.getBoundAppKeyIndexes());

            // ── Auto-assign publish address ───────────────────────────────────
            boolean autoAssigned = tryAutoAssignPublishAddress();
            if (!autoAssigned) {
                Log.w(TAG, "Auto-assignment failed — trying fallback");
                autoAssignFallback();
            }
        }

        updatePublicationValues();
    }

    // =========================================================================
    // UI setup
    // =========================================================================

    private void setupScrollListener() {
        binding.scrollView.getViewTreeObserver().addOnScrollChangedListener(() -> {
            if (binding.scrollView.getScrollY() == 0) binding.fabApply.extend();
            else binding.fabApply.shrink();
        });
    }

    private void setupClickListeners(MeshModel meshModel) {
        binding.containerPublishAddress.setOnClickListener(v -> {
            List<Group> groups = mPublicationViewModel
                    .getNetworkLiveData().getMeshNetwork().getGroups();
            DialogFragmentPublishAddress.newInstance(
                            meshModel.getPublicationSettings(), new ArrayList<>(groups))
                    .show(getSupportFragmentManager(), null);
        });

        binding.containerAppKeyIndex.setOnClickListener(v -> {
            final Intent intent = new Intent(this, AppKeysActivity.class);
            intent.putExtra(Utils.EXTRA_DATA, Utils.PUBLICATION_APP_KEY);
            appKeySelector.launch(intent);
        });

        binding.friendshipCredentialFlag.setOnCheckedChangeListener(
                (buttonView, isChecked) ->
                        mPublicationViewModel.setFriendshipCredentialsFlag(isChecked));

        binding.containerPublicationTtl.setOnClickListener(v -> {
            if (meshModel.getPublicationSettings() != null) {
                DialogFragmentPublishTtl.newInstance(
                                meshModel.getPublicationSettings().getPublishTtl())
                        .show(getSupportFragmentManager(), null);
            } else {
                DialogFragmentPublishTtl.newInstance(USE_DEFAULT_TTL)
                        .show(getSupportFragmentManager(), null);
            }
        });

        binding.fabApply.setOnClickListener(v -> {
            if (!checkConnectivity(binding.container)) return;
            setPublication();
        });
    }

    private void setupSliders() {
        binding.publishIntervalSlider.setValueFrom(MIN_PUBLICATION_INTERVAL);
        binding.publishIntervalSlider.setValueTo(MAX_PUBLICATION_INTERVAL);
        binding.publishIntervalSlider.addOnChangeListener((slider, value, fromUser) -> {
            final int resource = mPublicationViewModel
                    .getPublicationPeriodResolutionResource((int) value);
            if (value == 0) binding.pubInterval.setText(resource);
            else binding.pubInterval.setText(
                    getString(resource, mPublicationViewModel.getPublishPeriod()));
        });

        binding.intervalStepsSlider.setValueFrom(
                PublicationSettings.MIN_PUBLICATION_RETRANSMIT_COUNT);
        binding.retransmissionSlider.setValueTo(
                PublicationSettings.MAX_PUBLICATION_RETRANSMIT_COUNT);
        binding.retransmissionSlider.setStepSize(1);
        binding.retransmissionSlider.addOnChangeListener((slider, progress, fromUser) -> {
            mPublicationViewModel.setRetransmitCount((int) progress);
            if (progress == 0) {
                binding.retransmitCount.setText(R.string.disabled);
                binding.retransmitInterval.setText(R.string.disabled);
                binding.intervalStepsSlider.setEnabled(false);
            } else {
                if (!binding.intervalStepsSlider.isEnabled())
                    binding.intervalStepsSlider.setEnabled(true);
                binding.retransmitInterval.setText(getString(
                        R.string.time_ms,
                        mPublicationViewModel.getRetransmissionInterval()));
                binding.retransmitCount.setText(getResources().getQuantityString(
                        R.plurals.retransmit_count, (int) progress, (int) progress));
            }
        });

        binding.intervalStepsSlider.setValueFrom(
                PublicationSettings.getMinRetransmissionInterval());
        binding.intervalStepsSlider.setValueTo(
                PublicationSettings.getMaxRetransmissionInterval());
        binding.intervalStepsSlider.setStepSize(50);
        binding.intervalStepsSlider.addOnChangeListener((slider, value, fromUser) -> {
            binding.retransmitInterval.setText(getString(R.string.time_ms, (int) value));
            mPublicationViewModel.setRetransmitIntervalSteps((int) value);
        });
    }

    // =========================================================================
    // ✅ MAIN AUTO-ASSIGNMENT LOGIC
    //
    // Strategy:
    //   1. Determine if the current node is a Server (has 0x1000) or Client (has 0x1001).
    //   2. Server path:
    //        a. Get server's SVG device ID from SharedViewModel / SharedPreferences / node name.
    //        b. Get server's element index N (saved by your SVG parsing code via saveElementId()).
    //        c. Look up the address stored for Client element N
    //           (saved by NrfMeshRepository.onAllModelsBindComplete() after Client bind).
    //        d. Set that address as the publish address.
    //   3. Client path: find the server's element N and use that address.
    // =========================================================================

    private boolean tryAutoAssignPublishAddress() {
        final ProvisionedMeshNode node = mPublicationViewModel.getSelectedMeshNode().getValue();
        if (node == null) {
            Log.w(TAG, "tryAutoAssignPublishAddress: node is null");
            return false;
        }

        boolean isServer = isServerNode(node);
        if (isServer) {
            Log.d(TAG, "Node is SERVER — auto-assigning from client addresses");
            return autoAssignForServerNode(node);
        } else {
            Log.d(TAG, "Node is CLIENT — auto-assigning from server element");
            return autoAssignForClientNode(node);
        }
    }

    // ── Node type detection ───────────────────────────────────────────────────

    private boolean isServerNode(ProvisionedMeshNode node) {
        for (Element element : node.getElements().values())
            for (MeshModel model : element.getMeshModels().values())
                if (model.getModelId() == 0x1000) return true; // Generic On Off Server
        return false;
    }

    private boolean isClientNode(ProvisionedMeshNode node) {
        for (Element element : node.getElements().values())
            for (MeshModel model : element.getMeshModels().values())
                if (model.getModelId() == 0x1001) return true; // Generic On Off Client
        return false;
    }

    // ── Server path ───────────────────────────────────────────────────────────

    /**
     * Auto-assign publish address for a SERVER node.
     *
     * Finds the server's SVG element ID (e.g. 2), then looks up the address
     * of Client element 2 (stored when Client was provisioned and auto-bound).
     *
     * Example:
     *   Server SVG element ID = 2
     *   Client element 2 address = 0x0004
     *   → publish address on this server model = 0x0004
     */
    private boolean autoAssignForServerNode(ProvisionedMeshNode serverNode) {
        // ── Step 1: resolve SVG device ID ─────────────────────────────────────
        String serverSvgDeviceId = resolveServerSvgDeviceId(serverNode);
        if (serverSvgDeviceId == null) {
            Log.e(TAG, "autoAssignForServerNode: could not resolve SVG device ID");
            return false;
        }

        // ── Step 2: get server's element index from prefs ─────────────────────
        int serverElementId = mSharedViewModel.getElementIdAsInt(serverSvgDeviceId);
        if (serverElementId == -1) {
            Log.e(TAG, "autoAssignForServerNode: no element ID stored for server="
                    + serverSvgDeviceId
                    + " — ensure saveElementId() is called during provisioning");
            return false;
        }

        mCurrentServerElementId = serverElementId;
        Log.d(TAG, "autoAssignForServerNode: svgId=" + serverSvgDeviceId
                + " elementId=" + serverElementId);

        // ── Step 3: look up matching client element address ───────────────────
        // autoMapServerToClientPublishAddress reads from SharedPreferences keys
        // written by NrfMeshRepository.onAllModelsBindComplete() (auto path)
        // and by SharedViewModel.saveAllClientElementAddresses() (UI path).
        int publishAddress = mSharedViewModel.autoMapServerToClientPublishAddress(
                serverSvgDeviceId, serverElementId);

        if (publishAddress != -1) {
            applyPublishAddress(publishAddress,
                    "SERVER element " + serverElementId + " → client addr (prefs)");
            return true;
        }

        // ── Step 4: direct scan fallback ──────────────────────────────────────
        publishAddress = findClientAddressByElementId(serverElementId);
        if (publishAddress != -1) {
            applyPublishAddress(publishAddress,
                    "SERVER element " + serverElementId + " → client addr (direct scan)");
            return true;
        }

        Log.w(TAG, "autoAssignForServerNode: no client address found for element="
                + serverElementId
                + " — provision Client first so its element addresses are saved");
        return false;
    }

    // ── Client path ───────────────────────────────────────────────────────────

    /**
     * Auto-assign publish address for a CLIENT node.
     * Finds the server's element index and returns the matching client element address.
     */
    private boolean autoAssignForClientNode(ProvisionedMeshNode clientNode) {
        String serverSvgDeviceId = mSharedViewModel.getServerSvgDeviceId();
        if (serverSvgDeviceId == null) {
            Log.w(TAG, "autoAssignForClientNode: no server SVG device ID stored");
            return false;
        }

        int serverElementId = mSharedViewModel.getElementIdAsInt(serverSvgDeviceId);
        if (serverElementId == -1) {
            Log.w(TAG, "autoAssignForClientNode: no element ID for server: " + serverSvgDeviceId);
            return false;
        }

        List<Element> sortedElements = new ArrayList<>(clientNode.getElements().values());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            sortedElements.sort((a, b) ->
                    Integer.compare(a.getElementAddress(), b.getElementAddress()));
        }

        if (serverElementId >= 1 && serverElementId <= sortedElements.size()) {
            int address = sortedElements.get(serverElementId - 1).getElementAddress();
            applyPublishAddress(address,
                    "CLIENT element " + serverElementId + " address");
            return true;
        }

        Log.w(TAG, "autoAssignForClientNode: serverElementId=" + serverElementId
                + " out of range (client has " + sortedElements.size() + " elements)");
        return false;
    }

    // ── SVG device ID resolution ──────────────────────────────────────────────

    /**
     * Try multiple sources to get the server node's SVG device ID.
     *
     * Priority:
     *  1. SharedViewModel (set by NodeConfigurationActivity.onModelClicked)
     *  2. SharedPreferences "server_svg_device_id"
     *  3. Node name itself (if it matches an SVG ID pattern)
     */
    private String resolveServerSvgDeviceId(ProvisionedMeshNode node) {
        // Source 1: SharedViewModel
        String id = mSharedViewModel.getSelectedSvgDeviceId();
        if (id != null && !id.isEmpty()) {
            Log.d(TAG, "resolveServerSvgDeviceId: from SharedViewModel: " + id);
            return id;
        }

        // Source 2: SharedPreferences
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        id = prefs.getString("server_svg_device_id", null);
        if (id != null && !id.isEmpty()) {
            Log.d(TAG, "resolveServerSvgDeviceId: from SharedPreferences: " + id);
            return id;
        }

        // Source 3: Node name
        if (node != null && node.getNodeName() != null && !node.getNodeName().isEmpty()) {
            Log.d(TAG, "resolveServerSvgDeviceId: using node name: " + node.getNodeName());
            return node.getNodeName();
        }

        Log.e(TAG, "resolveServerSvgDeviceId: could not resolve from any source");
        return null;
    }

    // ── Direct scan fallback ──────────────────────────────────────────────────

    /**
     * Scan all provisioned client nodes to find one that has a stored element address
     * at the given index.  Used when the SharedPreferences lookup fails (e.g. the
     * mapping was not yet saved at the time of the lookup).
     *
     * @param targetElementId 1-based element index from SVG
     * @return Client element unicast address, or -1 if not found
     */
    private int findClientAddressByElementId(int targetElementId) {

        List<ProvisionedMeshNode> allNodes = mSharedViewModel.getAllProvisionedNodes();
        if (allNodes == null) {
            Log.w(TAG, "findClientAddressByElementId: no provisioned nodes");
            return -1;
        }

        final ProvisionedMeshNode currentNode =
                mPublicationViewModel.getSelectedMeshNode().getValue();

        for (ProvisionedMeshNode node : allNodes) {

            // Skip current node (server)
            if (currentNode != null &&
                    node.getUnicastAddress() == currentNode.getUnicastAddress()) {
                continue;
            }

            if (!isClientNode(node)) continue;

            String deviceId = normalizeId(node.getNodeName());

            // ── ✅ STEP 1: Try from ClientElementStore (PRIMARY SOURCE) ───────────
            int address = ClientElementStore.get(deviceId, targetElementId);

            if (address != -1) {
                Log.d(TAG, "✅ findClientAddress: STORE"
                        + " client=" + deviceId
                        + " element=" + targetElementId
                        + " address=0x" + String.format("%04X", address));
                return address;
            }

            // ── ⚠️ STEP 2: SAFE FALLBACK (address-based, NOT list index) ─────────
            int base = node.getUnicastAddress();

            for (Element e : node.getElements().values()) {

                int index = e.getElementAddress() - base - 1;

                if (index == targetElementId) {
                    address = e.getElementAddress();

                    Log.d(TAG, "⚠️ findClientAddress: FALLBACK (safe)"
                            + " client=" + deviceId
                            + " element=" + targetElementId
                            + " address=0x" + String.format("%04X", address));

                    return address;
                }
            }
        }

        Log.e(TAG, "❌ findClientAddressByElementId: NOT FOUND for element="
                + targetElementId);

        return -1;
    }

    private String normalizeId(String id) {
        return id == null ? null : id.trim().toLowerCase();
    }
    // ── Last-resort fallback ──────────────────────────────────────────────────

    /**
     * Last resort: pick the first element of the first available client node.
     * Used only when all other resolution methods have failed.
     */
    private void autoAssignFallback() {
        final ProvisionedMeshNode currentNode =
                mPublicationViewModel.getSelectedMeshNode().getValue();
        if (currentNode == null) return;

        if (!isServerNode(currentNode)) return;

        List<ProvisionedMeshNode> allNodes = mSharedViewModel.getAllProvisionedNodes();
        if (allNodes == null) return;

        for (ProvisionedMeshNode node : allNodes) {
            if (node.getUnicastAddress() == currentNode.getUnicastAddress()) continue;
            if (!isClientNode(node)) continue;

            List<Element> sortedElements = new ArrayList<>(node.getElements().values());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                sortedElements.sort((a, b) ->
                        Integer.compare(a.getElementAddress(), b.getElementAddress()));
            }

            if (!sortedElements.isEmpty()) {
                int address = sortedElements.get(0).getElementAddress();
                applyPublishAddress(address,
                        "FALLBACK — first element of first client node");
                return;
            }
        }

        Log.w(TAG, "autoAssignFallback: no suitable publish address found");
    }

    // ── Apply ─────────────────────────────────────────────────────────────────

    private void applyPublishAddress(final int address, final String reason) {
        mPublicationViewModel.setLabelUUID(null);
        mPublicationViewModel.setPublishAddress(address);
        binding.publishAddress.setText(MeshAddress.formatAddress(address, true));
        Log.d(TAG, "✅ applyPublishAddress: 0x"
                + String.format("%04X", address) + " reason=" + reason);
    }

    // =========================================================================
    // GroupCallbacks + DestinationAddressCallbacks
    // =========================================================================

    @Override
    public Group createGroup(@NonNull final String name) {
        final MeshNetwork network = mPublicationViewModel.getNetworkLiveData().getMeshNetwork();
        return network != null
                ? network.createGroup(network.getSelectedProvisioner(), name) : null;
    }

    @Override
    public Group createGroup(@NonNull final UUID uuid, final String name) {
        final MeshNetwork network = mPublicationViewModel.getNetworkLiveData().getMeshNetwork();
        return network != null ? network.createGroup(uuid, null, name) : null;
    }

    @Override
    public boolean onGroupAdded(@NonNull final Group group) {
        final MeshNetwork network = mPublicationViewModel.getNetworkLiveData().getMeshNetwork();
        if (network != null && network.addGroup(group)) {
            onDestinationAddressSet(group);
            return true;
        }
        return false;
    }

    @Override
    public boolean onGroupAdded(@NonNull final String name, final int address) {
        final MeshNetwork network = mPublicationViewModel.getNetworkLiveData().getMeshNetwork();
        if (network != null) {
            final Group group = network.createGroup(
                    network.getSelectedProvisioner(), address, name);
            if (network.addGroup(group)) {
                onDestinationAddressSet(group);
                return true;
            }
        }
        return false;
    }

    @Override
    public void onDestinationAddressSet(final int address) {
        mPublicationViewModel.setLabelUUID(null);
        mPublicationViewModel.setPublishAddress(address);
        binding.publishAddress.setText(MeshAddress.formatAddress(address, true));
    }

    @Override
    public void onDestinationAddressSet(@NonNull final Group group) {
        mPublicationViewModel.setLabelUUID(group.getAddressLabel());
        mPublicationViewModel.setPublishAddress(group.getAddress());
        binding.publishAddress.setText(MeshAddress.formatAddress(group.getAddress(), true));
    }

    @Override
    public void setPublishTtl(final int ttl) {
        mPublicationViewModel.setPublishTtl(ttl);
        updateTtlUi();
    }

    // =========================================================================
    // UI updates
    // =========================================================================

    private void updatePublicationValues() {
        updateUi();
        updateTtlUi();
    }

    private void updateUi() {
        final UUID labelUUID = mPublicationViewModel.getLabelUUID();
        if (labelUUID != null) {
            binding.publishAddress.setText(labelUUID.toString().toUpperCase(Locale.US));
        } else if (MeshAddress.isValidUnassignedAddress(
                mPublicationViewModel.getPublishAddress())) {
            binding.publishAddress.setText(R.string.not_assigned);
        } else {
            binding.publishAddress.setText(MeshAddress.formatAddress(
                    mPublicationViewModel.getPublishAddress(), true));
        }

        final MeshNetwork network = mPublicationViewModel.getNetworkLiveData().getMeshNetwork();
        if (network != null) {
            final ApplicationKey key =
                    network.getAppKey(mPublicationViewModel.getAppKeyIndex());
            binding.appKey.setText(key != null
                    ? getString(R.string.key_name_and_index,
                    key.getName(), mPublicationViewModel.getAppKeyIndex())
                    : getString(R.string.unavailable));
        } else {
            binding.appKey.setText(getString(R.string.unavailable));
        }

        binding.friendshipCredentialFlag.setChecked(
                mPublicationViewModel.getFriendshipCredentialsFlag());
        updatePublishPeriodUi();
        binding.retransmissionSlider.setValue(mPublicationViewModel.getRetransmitCount());
        binding.intervalStepsSlider.setValue(mPublicationViewModel.getRetransmissionInterval());
    }

    private void updateTtlUi() {
        final int ttl = mPublicationViewModel.getPublishTtl();
        binding.publicationTtl.setText(isDefaultPublishTtl(ttl)
                ? getString(R.string.uses_default_ttl)
                : String.valueOf(ttl));
    }

    private void setPublication() {
        final ProvisionedMeshNode node = mPublicationViewModel.getSelectedMeshNode().getValue();
        if (node != null) {
            final MeshMessage configModelPublicationSet =
                    mPublicationViewModel.createMessage();
            if (configModelPublicationSet != null) {
                try {
                    mPublicationViewModel.getMeshManagerApi().createMeshPdu(
                            node.getUnicastAddress(), configModelPublicationSet);
                    Log.d(TAG, "Publication set sent to node: 0x"
                            + String.format("%04X", node.getUnicastAddress()));
                } catch (IllegalArgumentException ex) {
                    DialogFragmentError.newInstance(
                                    getString(R.string.title_error), ex.getMessage())
                            .show(getSupportFragmentManager(), null);
                    return;
                }
            }
        }
        setResult(Activity.RESULT_OK, new Intent());
        finish();
    }

    private void updatePublishPeriodUi() {
        final int sliderValue;
        final int stringResource;
        switch (mPublicationViewModel.getPublicationResolution()) {
            default:
            case 0:
                sliderValue    = mPublicationViewModel.getPublicationSteps();
                stringResource = R.string.time_ms;
                break;
            case 1:
                sliderValue    = 57 + mPublicationViewModel.getPublicationSteps();
                stringResource = R.string.time_s;
                break;
            case 2:
                sliderValue    = 114 + mPublicationViewModel.getPublicationSteps();
                stringResource = R.string.time_s;
                break;
            case 3:
                sliderValue    = 171 + mPublicationViewModel.getPublicationSteps();
                stringResource = R.string.time_m;
                break;
        }
        binding.publishIntervalSlider.setValue(sliderValue);
        final int period = mPublicationViewModel.getPublishPeriod();
        binding.pubInterval.setText(sliderValue == 0
                ? getString(R.string.disabled)
                : getString(stringResource, period));
    }

    // =========================================================================
    // BaseActivity overrides (publication settings manages its own progress)
    // =========================================================================

    @Override protected void updateClickableViews() {}
    @Override protected void showProgressBar()       {}
    @Override protected void hideProgressBar()       {}
    @Override protected void enableClickableViews()  {}
    @Override protected void disableClickableViews() {}
    @Override protected void updateMeshMessage(final MeshMessage meshMessage) {}
}