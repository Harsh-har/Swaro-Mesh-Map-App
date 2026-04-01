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
import android.os.Handler;
import android.os.Looper;
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

    /**
     * Delay before auto-firing setPublication() after address is resolved.
     */
    private static final int AUTO_PUBLISH_DELAY_MS   = 600;

    /**
     * Delay between STEP-1 and STEP-2 publish PDUs.
     * Gives STEP-1 acknowledgement time to arrive before paired PDU is sent.
     */
    private static final int PAIRED_PUBLISH_DELAY_MS = 1500;

    private ActivityPublicationSettingsBinding binding;
    private SharedViewModel      mSharedViewModel;
    private PublicationViewModel mPublicationViewModel;

    // ── Auto-assign state ─────────────────────────────────────────────────────
    private int     mCurrentServerElementId = -1;
    private boolean mAutoPublishPending      = false;
    private final Handler mAutoPublishHandler = new Handler(Looper.getMainLooper());

    // ── Bidirectional publish state ───────────────────────────────────────────
    // mPairedNodeUnicast    = the node that STEP-2 PDU will be sent TO
    // mPairedElementAddress = the element on that node whose publication we set
    // mPairedPublishTarget  = the address that paired element will publish TO
    private boolean mBidirectionalPending  = false;
    private int     mPairedNodeUnicast     = -1;
    private int     mPairedElementAddress  = -1;
    private int     mPairedPublishTarget   = -1;
    private int     mPairedAppKeyIndex     = -1;

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
            Log.e(TAG, "No model selected — finishing");
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

            // ── Step 1: auto-assign publish address ───────────────────────────
            boolean autoAssigned = tryAutoAssignPublishAddress();
            if (!autoAssigned) {
                Log.w(TAG, "Auto-assignment failed — trying fallback");
                autoAssignFallback();
            }

            // ── Step 2: fire setPublication() automatically if address resolved
            if (mAutoPublishPending) {
                Log.d(TAG, "Scheduling auto setPublication in " + AUTO_PUBLISH_DELAY_MS + "ms");
                mAutoPublishHandler.postDelayed(() -> {
                    Log.d(TAG, "Auto-publish firing now");
                    setPublication();
                }, AUTO_PUBLISH_DELAY_MS);
            }
        }

        updatePublicationValues();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mAutoPublishHandler.removeCallbacksAndMessages(null);
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
            mAutoPublishHandler.removeCallbacksAndMessages(null);
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
    // AUTO-ASSIGNMENT LOGIC
    // =========================================================================

    private boolean tryAutoAssignPublishAddress() {
        final ProvisionedMeshNode node = mPublicationViewModel.getSelectedMeshNode().getValue();
        if (node == null) {
            Log.w(TAG, "tryAutoAssignPublishAddress: node is null");
            return false;
        }

        if (isServerNode(node)) {
            Log.d(TAG, "Node is SERVER → assign Client element[N] as publish address");
            return autoAssignForServerNode(node);
        } else if (isClientNode(node)) {
            Log.d(TAG, "Node is CLIENT → assign Server unicast address as publish address");
            return autoAssignForClientNode(node);
        }

        Log.w(TAG, "tryAutoAssignPublishAddress: node is neither server nor client");
        return false;
    }

    // ── Node type helpers ─────────────────────────────────────────────────────

    private boolean isServerNode(ProvisionedMeshNode node) {
        for (Element element : node.getElements().values())
            for (MeshModel model : element.getMeshModels().values())
                if (model.getModelId() == 0x1000) return true;
        return false;
    }

    private boolean isClientNode(ProvisionedMeshNode node) {
        for (Element element : node.getElements().values())
            for (MeshModel model : element.getMeshModels().values())
                if (model.getModelId() == 0x1001) return true;
        return false;
    }

    // =========================================================================
    // SERVER PATH
    // Server (0x1000) → publishes TO → Client element[N]
    // =========================================================================

    private boolean autoAssignForServerNode(ProvisionedMeshNode serverNode) {
        String serverSvgDeviceId = resolveServerSvgDeviceId(serverNode);
        if (serverSvgDeviceId == null) {
            Log.e(TAG, "autoAssignForServerNode: SVG device ID not found");
            return false;
        }

        int serverElementId = mSharedViewModel.getElementIdAsInt(serverSvgDeviceId);
        if (serverElementId == -1) {
            Log.e(TAG, "autoAssignForServerNode: no element ID for server="
                    + serverSvgDeviceId);
            return false;
        }

        mCurrentServerElementId = serverElementId;
        Log.d(TAG, "autoAssignForServerNode: svgId=" + serverSvgDeviceId
                + " elementId=" + serverElementId);

        // Primary: SharedPreferences lookup
        int publishAddress = mSharedViewModel.autoMapServerToClientPublishAddress(
                serverSvgDeviceId, serverElementId);
        if (publishAddress != -1) {
            applyPublishAddress(publishAddress,
                    "SERVER[" + serverElementId + "] → client prefs");
            return true;
        }

        // Fallback: direct node scan
        publishAddress = findClientAddressByElementId(serverElementId);
        if (publishAddress != -1) {
            applyPublishAddress(publishAddress,
                    "SERVER[" + serverElementId + "] → client scan");
            return true;
        }

        Log.w(TAG, "autoAssignForServerNode: no client address found for element="
                + serverElementId);
        return false;
    }

    // =========================================================================
    // CLIENT PATH
    // Client element[N] (0x1001) → publishes TO → Server unicast address
    // =========================================================================

    private boolean autoAssignForClientNode(ProvisionedMeshNode clientNode) {
        String serverSvgDeviceId = mSharedViewModel.getServerSvgDeviceId();
        if (serverSvgDeviceId == null) {
            Log.w(TAG, "autoAssignForClientNode: no server SVG device ID in SharedViewModel"
                    + " — trying SharedPreferences fallback");
            serverSvgDeviceId = resolveServerSvgDeviceIdFromPrefs();
        }

        if (serverSvgDeviceId == null) {
            Log.e(TAG, "autoAssignForClientNode: server SVG device ID not found anywhere");
            return false;
        }

        Log.d(TAG, "autoAssignForClientNode: paired server SVG ID = " + serverSvgDeviceId);

        // Primary: ClientElementStore
        int serverUnicast = ClientElementStore.getServerUnicastAddress(serverSvgDeviceId);
        if (serverUnicast != -1) {
            applyPublishAddress(serverUnicast,
                    "CLIENT → server unicast from store [" + serverSvgDeviceId + "]");
            return true;
        }

        // Fallback: scan
        serverUnicast = findServerUnicastByName(serverSvgDeviceId, clientNode);
        if (serverUnicast != -1) {
            ClientElementStore.saveServerUnicastAddress(serverSvgDeviceId, serverUnicast);
            applyPublishAddress(serverUnicast,
                    "CLIENT → server unicast from scan [" + serverSvgDeviceId + "]");
            return true;
        }

        Log.e(TAG, "autoAssignForClientNode: server node not found for id="
                + serverSvgDeviceId);
        return false;
    }

    private int findServerUnicastByName(String serverSvgDeviceId,
                                        ProvisionedMeshNode excludeNode) {
        List<ProvisionedMeshNode> allNodes = mSharedViewModel.getAllProvisionedNodes();
        if (allNodes == null) {
            Log.w(TAG, "findServerUnicastByName: no provisioned nodes");
            return -1;
        }

        final String normalizedTarget = normalizeId(serverSvgDeviceId);

        for (ProvisionedMeshNode node : allNodes) {
            if (excludeNode != null
                    && node.getUnicastAddress() == excludeNode.getUnicastAddress()) continue;
            final String nodeName = normalizeId(node.getNodeName());
            if (normalizedTarget.equals(nodeName) && isServerNode(node)) {
                Log.d(TAG, "findServerUnicastByName: FOUND server='"
                        + node.getNodeName() + "' unicast=0x"
                        + String.format("%04X", node.getUnicastAddress()));
                return node.getUnicastAddress();
            }
        }

        // Fallback: first server node
        for (ProvisionedMeshNode node : allNodes) {
            if (excludeNode != null
                    && node.getUnicastAddress() == excludeNode.getUnicastAddress()) continue;
            if (isServerNode(node)) {
                Log.w(TAG, "findServerUnicastByName: name match failed — using first server"
                        + " unicast=0x" + String.format("%04X", node.getUnicastAddress()));
                return node.getUnicastAddress();
            }
        }

        return -1;
    }

    // ── SVG device ID resolution ──────────────────────────────────────────────

    private String resolveServerSvgDeviceId(ProvisionedMeshNode node) {
        String id = mSharedViewModel.getSelectedSvgDeviceId();
        if (id != null && !id.isEmpty()) {
            Log.d(TAG, "resolveServerSvgDeviceId: SharedViewModel → " + id);
            return id;
        }
        id = resolveServerSvgDeviceIdFromPrefs();
        if (id != null) return id;

        if (node != null && node.getNodeName() != null && !node.getNodeName().isEmpty()) {
            Log.d(TAG, "resolveServerSvgDeviceId: node name → " + node.getNodeName());
            return node.getNodeName();
        }

        Log.e(TAG, "resolveServerSvgDeviceId: all sources failed");
        return null;
    }

    private String resolveServerSvgDeviceIdFromPrefs() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String id = prefs.getString("server_svg_device_id", null);
        if (id != null && !id.isEmpty()) {
            Log.d(TAG, "resolveServerSvgDeviceId: SharedPreferences → " + id);
            return id;
        }
        return null;
    }

    // ── Direct client scan ────────────────────────────────────────────────────

    private int findClientAddressByElementId(int targetElementId) {
        List<ProvisionedMeshNode> allNodes = mSharedViewModel.getAllProvisionedNodes();
        if (allNodes == null) {
            Log.w(TAG, "findClientAddressByElementId: no nodes");
            return -1;
        }

        final ProvisionedMeshNode currentNode =
                mPublicationViewModel.getSelectedMeshNode().getValue();

        for (ProvisionedMeshNode node : allNodes) {
            if (currentNode != null &&
                    node.getUnicastAddress() == currentNode.getUnicastAddress()) continue;
            if (!isClientNode(node)) continue;

            String deviceId = normalizeId(node.getNodeName());

            // Primary: ClientElementStore
            int address = ClientElementStore.get(deviceId, targetElementId);
            if (address != -1) {
                Log.d(TAG, "✅ findClientAddress STORE"
                        + " client=" + deviceId + " element=" + targetElementId
                        + " addr=0x" + String.format("%04X", address));
                return address;
            }

            // Fallback: unicast offset arithmetic
            int base = node.getUnicastAddress();
            for (Element e : node.getElements().values()) {
                int offset = e.getElementAddress() - base;
                if (offset == targetElementId) {
                    address = e.getElementAddress();
                    Log.d(TAG, "⚠️ findClientAddress FALLBACK"
                            + " client=" + deviceId + " element=" + targetElementId
                            + " addr=0x" + String.format("%04X", address));
                    return address;
                }
            }
        }

        Log.e(TAG, "❌ findClientAddressByElementId: NOT FOUND element=" + targetElementId);
        return -1;
    }

    private String normalizeId(String id) {
        return id == null ? null : id.trim().toLowerCase();
    }

    // ── Last-resort fallback ──────────────────────────────────────────────────

    private void autoAssignFallback() {
        final ProvisionedMeshNode currentNode =
                mPublicationViewModel.getSelectedMeshNode().getValue();
        if (currentNode == null) return;

        if (isServerNode(currentNode)) {
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
                    applyPublishAddress(sortedElements.get(0).getElementAddress(),
                            "FALLBACK SERVER — first element of first client");
                    return;
                }
            }
            Log.w(TAG, "autoAssignFallback: no client found for server");

        } else if (isClientNode(currentNode)) {
            List<ProvisionedMeshNode> allNodes = mSharedViewModel.getAllProvisionedNodes();
            if (allNodes == null) return;

            for (ProvisionedMeshNode node : allNodes) {
                if (node.getUnicastAddress() == currentNode.getUnicastAddress()) continue;
                if (!isServerNode(node)) continue;

                applyPublishAddress(node.getUnicastAddress(),
                        "FALLBACK CLIENT — unicast of first server");
                return;
            }
            Log.w(TAG, "autoAssignFallback: no server found for client");
        }
    }

    // ── Core apply method ─────────────────────────────────────────────────────

    private void applyPublishAddress(final int address, final String reason) {
        mPublicationViewModel.setLabelUUID(null);
        mPublicationViewModel.setPublishAddress(address);
        binding.publishAddress.setText(MeshAddress.formatAddress(address, true));
        mAutoPublishPending = true;
        Log.d(TAG, "✅ applyPublishAddress: 0x"
                + String.format("%04X", address) + " reason=" + reason);
    }

    // =========================================================================
    // setPublication — STEP-1
    // =========================================================================

    private void setPublication() {
        if (!checkConnectivity(binding.container)) {
            Log.w(TAG, "setPublication: no connectivity — skip");
            return;
        }

        final ProvisionedMeshNode node =
                mPublicationViewModel.getSelectedMeshNode().getValue();
        if (node == null) {
            Log.e(TAG, "setPublication: node is null");
            return;
        }

        final MeshMessage configModelPublicationSet = mPublicationViewModel.createMessage();
        if (configModelPublicationSet == null) {
            Log.e(TAG, "setPublication: createMessage() returned null"
                    + " — appKey or address may be invalid");
            return;
        }

        try {
            mPublicationViewModel.getMeshManagerApi().createMeshPdu(
                    node.getUnicastAddress(), configModelPublicationSet);

            Log.d(TAG, "✅ setPublication [STEP-1]: PDU sent"
                    + " node=0x" + String.format("%04X", node.getUnicastAddress())
                    + " publishAddr=0x"
                    + String.format("%04X", mPublicationViewModel.getPublishAddress()));

        } catch (IllegalArgumentException ex) {
            Log.e(TAG, "setPublication: createMeshPdu failed: " + ex.getMessage());
            DialogFragmentError.newInstance(
                            getString(R.string.title_error), ex.getMessage())
                    .show(getSupportFragmentManager(), null);
            return;
        }

        // ── Prepare STEP-2 ────────────────────────────────────────────────────
        preparePairedNodePublish(node);

        if (mBidirectionalPending
                && mPairedNodeUnicast != -1
                && mPairedElementAddress != -1
                && mPairedPublishTarget != -1) {
            Log.d(TAG, "Scheduling STEP-2 paired publish in "
                    + PAIRED_PUBLISH_DELAY_MS + "ms"
                    + " pairedNode=0x" + String.format("%04X", mPairedNodeUnicast)
                    + " pairedElement=0x" + String.format("%04X", mPairedElementAddress)
                    + " publishTo=0x" + String.format("%04X", mPairedPublishTarget));
            mAutoPublishHandler.postDelayed(
                    this::sendPairedNodePublish, PAIRED_PUBLISH_DELAY_MS);
        } else {
            Log.w(TAG, "setPublication: bidirectional not ready — finishing without STEP-2");
            setResult(Activity.RESULT_OK, new Intent());
            finish();
        }
    }

    // =========================================================================
    // preparePairedNodePublish
    // =========================================================================

    private void preparePairedNodePublish(@NonNull ProvisionedMeshNode currentNode) {
        mBidirectionalPending = false;
        mPairedNodeUnicast    = -1;
        mPairedElementAddress = -1;
        mPairedPublishTarget  = -1;
        mPairedAppKeyIndex    = mPublicationViewModel.getAppKeyIndex();

        if (isServerNode(currentNode)) {
            // ── SERVER path ───────────────────────────────────────────────────
            // STEP-1: Server → Client element[N]
            // STEP-2: Client element[N] → Server unicast

            List<ProvisionedMeshNode> allNodes = mSharedViewModel.getAllProvisionedNodes();
            if (allNodes == null) return;

            for (ProvisionedMeshNode node : allNodes) {
                if (node.getUnicastAddress() == currentNode.getUnicastAddress()) continue;
                if (!isClientNode(node)) continue;

                // Find client element[N] matching this server's element ID
                int clientElementAddr = -1;
                if (mCurrentServerElementId != -1) {
                    clientElementAddr = ClientElementStore.get(
                            normalizeId(node.getNodeName()), mCurrentServerElementId);
                }

                // Fallback: first element of client node
                if (clientElementAddr == -1) {
                    List<Element> sorted = new ArrayList<>(node.getElements().values());
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        sorted.sort((a, b) ->
                                Integer.compare(a.getElementAddress(), b.getElementAddress()));
                    }
                    if (!sorted.isEmpty())
                        clientElementAddr = sorted.get(0).getElementAddress();
                }

                if (clientElementAddr != -1) {
                    mPairedNodeUnicast    = node.getUnicastAddress();        // client node
                    mPairedElementAddress = clientElementAddr;               // client element[N]
                    mPairedPublishTarget  = currentNode.getUnicastAddress(); // → server unicast
                    mBidirectionalPending = true;

                    Log.d(TAG, "preparePairedNodePublish SERVER:"
                            + " clientNode=0x"
                            + String.format("%04X", mPairedNodeUnicast)
                            + " clientElement=0x"
                            + String.format("%04X", mPairedElementAddress)
                            + " → publishTo server=0x"
                            + String.format("%04X", mPairedPublishTarget));
                }
                break; // Only 1 client node
            }

        } else if (isClientNode(currentNode)) {
            // ── CLIENT path ───────────────────────────────────────────────────
            // STEP-1: Client element[N] → Server unicast
            // STEP-2: Server → Client element[N]

            String serverSvgId = mSharedViewModel.getServerSvgDeviceId();
            if (serverSvgId == null) serverSvgId = resolveServerSvgDeviceIdFromPrefs();

            List<ProvisionedMeshNode> allNodes = mSharedViewModel.getAllProvisionedNodes();
            if (allNodes == null) return;

            for (ProvisionedMeshNode node : allNodes) {
                if (node.getUnicastAddress() == currentNode.getUnicastAddress()) continue;
                if (!isServerNode(node)) continue;

                if (serverSvgId != null
                        && !normalizeId(serverSvgId).equals(
                        normalizeId(node.getNodeName()))) continue;

                int serverElementAddr = resolveServerElementAddress(node);

                mPairedNodeUnicast    = node.getUnicastAddress();                    // server node
                mPairedElementAddress = serverElementAddr;                           // server element
                mPairedPublishTarget  = mPublicationViewModel.getPublishAddress();   // → client element[N]
                mBidirectionalPending = true;

                Log.d(TAG, "preparePairedNodePublish CLIENT:"
                        + " serverNode=0x" + String.format("%04X", mPairedNodeUnicast)
                        + " serverElement=0x" + String.format("%04X", mPairedElementAddress)
                        + " → publishTo clientElement=0x"
                        + String.format("%04X", mPairedPublishTarget));
                break;
            }
        }
    }

    // =========================================================================
    // resolveServerElementAddress
    // =========================================================================

    private int resolveServerElementAddress(@NonNull ProvisionedMeshNode serverNode) {
        final String serverSvgId = normalizeId(serverNode.getNodeName());
        if (serverSvgId == null) return serverNode.getUnicastAddress();

        int elementId = mSharedViewModel.getElementIdAsInt(serverSvgId);

        List<Element> sorted = new ArrayList<>(serverNode.getElements().values());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            sorted.sort((a, b) ->
                    Integer.compare(a.getElementAddress(), b.getElementAddress()));
        }

        // elementId is 1-based
        if (elementId > 0 && elementId <= sorted.size()) {
            int addr = sorted.get(elementId - 1).getElementAddress();
            Log.d(TAG, "resolveServerElementAddress: elementId=" + elementId
                    + " addr=0x" + String.format("%04X", addr));
            return addr;
        }

        // Fallback: primary element
        int addr = sorted.isEmpty()
                ? serverNode.getUnicastAddress()
                : sorted.get(0).getElementAddress();
        Log.w(TAG, "resolveServerElementAddress: fallback addr=0x"
                + String.format("%04X", addr));
        return addr;
    }

    // =========================================================================
    // sendPairedNodePublish — STEP-2
    // =========================================================================

    private void sendPairedNodePublish() {
        if (mPairedNodeUnicast == -1
                || mPairedElementAddress == -1
                || mPairedPublishTarget == -1) {
            Log.e(TAG, "sendPairedNodePublish: invalid state — skip");
            setResult(Activity.RESULT_OK, new Intent());
            finish();
            return;
        }

        try {
            // ── 1. Find the exact Element object on the paired node ───────────
            Element pairedElement = findElementByAddress(
                    mPairedNodeUnicast, mPairedElementAddress);

            if (pairedElement == null) {
                Log.e(TAG, "sendPairedNodePublish: paired element not found"
                        + " node=0x" + String.format("%04X", mPairedNodeUnicast)
                        + " element=0x" + String.format("%04X", mPairedElementAddress));
                setResult(Activity.RESULT_OK, new Intent());
                finish();
                return;
            }

            // ── 2. Find the correct model on that element ─────────────────────
            // SERVER path → paired is CLIENT → model 0x1001
            // CLIENT path → paired is SERVER → model 0x1000
            final ProvisionedMeshNode currentNode =
                    mPublicationViewModel.getSelectedMeshNode().getValue();
            int targetModelId = (currentNode != null && isServerNode(currentNode))
                    ? 0x1001
                    : 0x1000;

            MeshModel pairedModel = pairedElement.getMeshModels().get(targetModelId);
            if (pairedModel == null) {
                // Fallback: first non-config model
                for (MeshModel m : pairedElement.getMeshModels().values()) {
                    if (m.getModelId() != 0x0000 && m.getModelId() != 0x0001) {
                        pairedModel = m;
                        break;
                    }
                }
            }

            if (pairedModel == null) {
                Log.e(TAG, "sendPairedNodePublish: no model found on paired element");
                setResult(Activity.RESULT_OK, new Intent());
                finish();
                return;
            }

            // ── 3. Save current selected element + model ──────────────────────
            // Use mPublicationViewModel which extends BaseViewModel
            // BaseViewModel has setSelectedElement() / setSelectedModel()
            // which delegate to NrfMeshRepository
            final Element   savedElement = mPublicationViewModel.getSelectedElement().getValue();
            final MeshModel savedModel   = mPublicationViewModel.getSelectedModel().getValue();

            // ── 4. Override to paired element + model so createMessage() works ─
            mPublicationViewModel.setSelectedElement(pairedElement);
            mPublicationViewModel.setSelectedModel(pairedModel);

            // ── 5. Set publish target address ─────────────────────────────────
            final int savedPublishAddress = mPublicationViewModel.getPublishAddress();
            mPublicationViewModel.setPublishAddress(mPairedPublishTarget);

            // ── 6. Build the PDU ──────────────────────────────────────────────
            final MeshMessage pairedMsg = mPublicationViewModel.createMessage();

            // ── 7. Restore everything ─────────────────────────────────────────
            mPublicationViewModel.setPublishAddress(savedPublishAddress);
            if (savedElement != null)
                mPublicationViewModel.setSelectedElement(savedElement);
            if (savedModel != null)
                mPublicationViewModel.setSelectedModel(savedModel);

            if (pairedMsg == null) {
                Log.e(TAG, "sendPairedNodePublish: createMessage() null — skip STEP-2");
                setResult(Activity.RESULT_OK, new Intent());
                finish();
                return;
            }

            // ── 8. Send PDU to paired node ────────────────────────────────────
            mPublicationViewModel.getMeshManagerApi()
                    .createMeshPdu(mPairedNodeUnicast, pairedMsg);

            Log.d(TAG, "✅ sendPairedNodePublish [STEP-2]: PDU sent"
                    + " node=0x" + String.format("%04X", mPairedNodeUnicast)
                    + " element=0x" + String.format("%04X", pairedElement.getElementAddress())
                    + " model=0x" + String.format("%04X", pairedModel.getModelId())
                    + " publishTo=0x" + String.format("%04X", mPairedPublishTarget));

        } catch (IllegalArgumentException ex) {
            Log.e(TAG, "sendPairedNodePublish: failed: " + ex.getMessage());
        }

        setResult(Activity.RESULT_OK, new Intent());
        finish();
    }

    // =========================================================================
    // findElementByAddress
    // =========================================================================

    @Nullable
    private Element findElementByAddress(int nodeUnicast, int elementAddress) {
        List<ProvisionedMeshNode> allNodes = mSharedViewModel.getAllProvisionedNodes();
        if (allNodes == null) return null;

        for (ProvisionedMeshNode node : allNodes) {
            if (node.getUnicastAddress() != nodeUnicast) continue;
            for (Element element : node.getElements().values()) {
                if (element.getElementAddress() == elementAddress) {
                    return element;
                }
            }
        }

        Log.e(TAG, "findElementByAddress: not found"
                + " node=0x" + String.format("%04X", nodeUnicast)
                + " element=0x" + String.format("%04X", elementAddress));
        return null;
    }

    // =========================================================================
    // GroupCallbacks + DestinationAddressCallbacks
    // =========================================================================

    @Override
    public Group createGroup(@NonNull final String name) {
        final MeshNetwork network =
                mPublicationViewModel.getNetworkLiveData().getMeshNetwork();
        return network != null
                ? network.createGroup(network.getSelectedProvisioner(), name) : null;
    }

    @Override
    public Group createGroup(@NonNull final UUID uuid, final String name) {
        final MeshNetwork network =
                mPublicationViewModel.getNetworkLiveData().getMeshNetwork();
        return network != null ? network.createGroup(uuid, null, name) : null;
    }

    @Override
    public boolean onGroupAdded(@NonNull final Group group) {
        final MeshNetwork network =
                mPublicationViewModel.getNetworkLiveData().getMeshNetwork();
        if (network != null && network.addGroup(group)) {
            onDestinationAddressSet(group);
            return true;
        }
        return false;
    }

    @Override
    public boolean onGroupAdded(@NonNull final String name, final int address) {
        final MeshNetwork network =
                mPublicationViewModel.getNetworkLiveData().getMeshNetwork();
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
        binding.publishAddress.setText(
                MeshAddress.formatAddress(group.getAddress(), true));
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

        final MeshNetwork network =
                mPublicationViewModel.getNetworkLiveData().getMeshNetwork();
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
        binding.intervalStepsSlider.setValue(
                mPublicationViewModel.getRetransmissionInterval());
    }

    private void updateTtlUi() {
        final int ttl = mPublicationViewModel.getPublishTtl();
        binding.publicationTtl.setText(isDefaultPublishTtl(ttl)
                ? getString(R.string.uses_default_ttl)
                : String.valueOf(ttl));
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
    // BaseActivity overrides
    // =========================================================================

    @Override protected void updateClickableViews() {}
    @Override protected void showProgressBar()       {}
    @Override protected void hideProgressBar()       {}
    @Override protected void enableClickableViews()  {}
    @Override protected void disableClickableViews() {}
    @Override protected void updateMeshMessage(final MeshMessage meshMessage) {}
}