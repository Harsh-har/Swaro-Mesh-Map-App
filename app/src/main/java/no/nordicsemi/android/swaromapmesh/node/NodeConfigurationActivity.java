package no.nordicsemi.android.swaromapmesh.node;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dagger.hilt.android.AndroidEntryPoint;
import no.nordicsemi.android.swaromapmesh.ApplicationKey;
import no.nordicsemi.android.swaromapmesh.DeviceDetailActivity;
import no.nordicsemi.android.swaromapmesh.MeshNetwork;
import no.nordicsemi.android.swaromapmesh.NetworkKey;
import no.nordicsemi.android.swaromapmesh.R;
import no.nordicsemi.android.swaromapmesh.databinding.ActivityNodeConfigurationBinding;
import no.nordicsemi.android.swaromapmesh.databinding.LayoutContainerBinding;
import no.nordicsemi.android.swaromapmesh.dialog.DialogFragmentConfigurationComplete;
import no.nordicsemi.android.swaromapmesh.dialog.DialogFragmentError;
import no.nordicsemi.android.swaromapmesh.dialog.DialogFragmentProxySet;
import no.nordicsemi.android.swaromapmesh.keys.AddAppKeysActivity;
import no.nordicsemi.android.swaromapmesh.keys.AddNetKeysActivity;
import no.nordicsemi.android.swaromapmesh.models.SigModelParser;
import no.nordicsemi.android.swaromapmesh.node.adapter.ElementAdapter;
import no.nordicsemi.android.swaromapmesh.node.dialog.DialogFragmentElementName;
import no.nordicsemi.android.swaromapmesh.node.dialog.DialogFragmentNodeName;
import no.nordicsemi.android.swaromapmesh.node.dialog.DialogFragmentResetNode;
import no.nordicsemi.android.swaromapmesh.provisioners.dialogs.DialogFragmentTtl;
import no.nordicsemi.android.swaromapmesh.transport.ConfigAppKeyAdd;
import no.nordicsemi.android.swaromapmesh.transport.ConfigAppKeyStatus;
import no.nordicsemi.android.swaromapmesh.transport.ConfigCompositionDataGet;
import no.nordicsemi.android.swaromapmesh.transport.ConfigCompositionDataStatus;
import no.nordicsemi.android.swaromapmesh.transport.ConfigDefaultTtlGet;
import no.nordicsemi.android.swaromapmesh.transport.ConfigDefaultTtlSet;
import no.nordicsemi.android.swaromapmesh.transport.ConfigDefaultTtlStatus;
import no.nordicsemi.android.swaromapmesh.transport.ConfigGattProxySet;
import no.nordicsemi.android.swaromapmesh.transport.ConfigGattProxyStatus;
import no.nordicsemi.android.swaromapmesh.transport.ConfigNodeReset;
import no.nordicsemi.android.swaromapmesh.transport.ConfigNodeResetStatus;
import no.nordicsemi.android.swaromapmesh.transport.Element;
import no.nordicsemi.android.swaromapmesh.transport.MeshMessage;
import no.nordicsemi.android.swaromapmesh.transport.MeshModel;
import no.nordicsemi.android.swaromapmesh.transport.ProvisionedMeshNode;
import no.nordicsemi.android.swaromapmesh.transport.ProxyConfigFilterStatus;
import no.nordicsemi.android.swaromapmesh.utils.Utils;
import no.nordicsemi.android.swaromapmesh.viewmodels.BaseActivity;
import no.nordicsemi.android.swaromapmesh.viewmodels.NodeConfigurationViewModel;
import no.nordicsemi.android.swaromapmesh.viewmodels.SharedViewModel;

@AndroidEntryPoint
public class NodeConfigurationActivity extends BaseActivity implements
        DialogFragmentNodeName.DialogFragmentNodeNameListener,
        DialogFragmentElementName.DialogFragmentElementNameListener,
        DialogFragmentTtl.DialogFragmentTtlListener,
        DialogFragmentProxySet.DialogFragmentProxySetListener,
        ElementAdapter.OnItemClickListener,
        DialogFragmentResetNode.DialogFragmentNodeResetListener,
        DialogFragmentConfigurationComplete.ConfigurationCompleteListener {

    private static final String PROGRESS_BAR_STATE    = "PROGRESS_BAR_STATE";
    private static final String PROXY_STATE            = "PROXY_STATE";
    private static final String REQUESTED_PROXY_STATE  = "REQUESTED_PROXY_STATE";
    private static final String TAG                    = "NodeConfigurationActivity";

    private ActivityNodeConfigurationBinding binding;
    private SharedViewModel mSharedViewModel;
    private boolean mRequestedState      = true;
    private boolean mCompositionRequested = false;
    private boolean mAppKeyBindRequested  = false;

    // SVG device ID passed in from the previous screen
    private String mSvgDeviceId = null;

    // =========================================================================
    // onCreate
    // =========================================================================
    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityNodeConfigurationBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        mViewModel      = new ViewModelProvider(this).get(NodeConfigurationViewModel.class);
        mSharedViewModel = new ViewModelProvider(this).get(SharedViewModel.class);
        initialize();

        // ── Read SVG device ID from Intent ────────────────────────────────────
        mSvgDeviceId = getIntent().getStringExtra(Utils.EXTRA_SVG_DEVICE_ID);
        if (mSvgDeviceId != null) {
            mSharedViewModel.setSelectedSvgDeviceId(mSvgDeviceId);
            String deviceName = getIntent().getStringExtra(DeviceDetailActivity.EXTRA_DEVICE_NAME);
            if (deviceName != null) {
                Log.d(TAG, "Device name from intent: " + deviceName);
            }
        }

        if (savedInstanceState != null) {
            if (savedInstanceState.getBoolean(PROGRESS_BAR_STATE)) {
                binding.configurationProgressBar.setVisibility(View.VISIBLE);
                disableClickableViews();
            } else {
                binding.configurationProgressBar.setVisibility(View.INVISIBLE);
                enableClickableViews();
            }
            mRequestedState = savedInstanceState.getBoolean(PROXY_STATE, true);
        }

        ProvisionedMeshNode selectedNode = resolveSelectedNode();

        // ── Toolbar ───────────────────────────────────────────────────────────
        setSupportActionBar(binding.toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.title_node_configuration);
            if (selectedNode != null) {
                getSupportActionBar().setSubtitle(selectedNode.getNodeName());
            }
        }

        setupNodeNameCard();
        setupNetKeysCard();
        setupAppKeysCard();
        setupTtlCard();
        setupElementsRecycler();
        setupActionButtons();
        observeSelectedNode();

        Log.d(TAG, "NodeConfigurationActivity created"
                + " node=" + (selectedNode != null ? selectedNode.getNodeName() : "null")
                + " svgDeviceId=" + mSvgDeviceId);
    }

    // =========================================================================
    // Node resolution
    // =========================================================================

    private ProvisionedMeshNode resolveSelectedNode() {
        ProvisionedMeshNode selectedNode = mViewModel.getSelectedMeshNode().getValue();

        if (selectedNode == null) {
            selectedNode = mSharedViewModel.getSelectedMeshNode().getValue();
            if (selectedNode != null) {
                mViewModel.setSelectedMeshNode(selectedNode);
                Log.d(TAG, "Loaded node from SharedViewModel: " + selectedNode.getNodeName());
            } else {
                if (mSvgDeviceId != null) {
                    MeshNetwork network = mViewModel.getNetworkLiveData().getMeshNetwork();
                    if (network != null && network.getNodes() != null) {
                        for (ProvisionedMeshNode node : network.getNodes()) {
                            if (mSvgDeviceId.equalsIgnoreCase(node.getNodeName())) {
                                selectedNode = node;
                                mViewModel.setSelectedMeshNode(selectedNode);
                                mSharedViewModel.setSelectedMeshNode(selectedNode);
                                Log.d(TAG, "Found node matching SVG ID: " + node.getNodeName());
                                break;
                            }
                        }
                    }
                }
                if (selectedNode == null) {
                    Log.e(TAG, "No selected mesh node — finishing");
                    finish();
                    return null;
                }
            }
        }
        return selectedNode;
    }

    // =========================================================================
    // Card / view setup
    // =========================================================================

    private void setupNodeNameCard() {
        final LayoutContainerBinding containerNodeName = binding.containerNodeName;
        containerNodeName.image.setBackground(
                ContextCompat.getDrawable(this, R.drawable.ic_label));
        containerNodeName.title.setText(R.string.title_node_name);
        final TextView nodeNameView = containerNodeName.text;
        nodeNameView.setVisibility(View.VISIBLE);
        containerNodeName.getRoot().setOnClickListener(v -> {
            final DialogFragmentNodeName fragment =
                    DialogFragmentNodeName.newInstance(nodeNameView.getText().toString());
            fragment.show(getSupportFragmentManager(), null);
        });
    }

    private void setupNetKeysCard() {
        binding.containerNetKeys.image.setBackground(
                ContextCompat.getDrawable(this, R.drawable.ic_vpn_key_24dp));
        binding.containerNetKeys.title.setText(R.string.title_net_keys);
        binding.containerNetKeys.text.setVisibility(View.VISIBLE);
        binding.containerNetKeys.getRoot().setOnClickListener(v ->
                startActivity(new Intent(this, AddNetKeysActivity.class)));
    }

    private void setupAppKeysCard() {
        binding.containerAppKeys.image.setBackground(
                ContextCompat.getDrawable(this, R.drawable.ic_vpn_key_24dp));
        binding.containerAppKeys.title.setText(R.string.title_app_keys);
        binding.containerAppKeys.text.setVisibility(View.VISIBLE);
        binding.containerAppKeys.getRoot().setOnClickListener(v ->
                startActivity(new Intent(this, AddAppKeysActivity.class)));
    }

    private void setupTtlCard() {
        binding.containerTtl.image.setBackground(
                ContextCompat.getDrawable(this, R.drawable.ic_numeric));
        binding.containerTtl.title.setText(R.string.title_ttl);
        binding.containerTtl.text.setVisibility(View.VISIBLE);
    }

    private void setupElementsRecycler() {
        binding.recyclerViewElements.setLayoutManager(new LinearLayoutManager(this));
        final ElementAdapter adapter = new ElementAdapter();
        adapter.setOnItemClickListener(this);
        binding.recyclerViewElements.setAdapter(adapter);
    }

    private void setupActionButtons() {
        final Button actionDetails = findViewById(R.id.action_show_details);
        actionDetails.setOnClickListener(v ->
                startActivity(new Intent(NodeConfigurationActivity.this,
                        NodeDetailsActivity.class)));

        binding.actionGetCompositionData.setOnClickListener(v -> {
            if (!checkConnectivity(binding.container)) return;
            sendMessage(new ConfigCompositionDataGet());
        });

        binding.actionGetDefaultTtl.setOnClickListener(v -> {
            if (!checkConnectivity(binding.container)) return;
            sendMessage(new ConfigDefaultTtlGet());
        });

        binding.actionSetDefaultTtl.setOnClickListener(v -> {
            final ProvisionedMeshNode node = mViewModel.getSelectedMeshNode().getValue();
            if (node != null) {
                DialogFragmentTtl fragmentTtl = DialogFragmentTtl.newInstance(
                        node.getTtl() == null ? -1 : node.getTtl());
                fragmentTtl.show(getSupportFragmentManager(), null);
            }
        });

        binding.actionResetNode.setOnClickListener(v -> {
            if (!checkConnectivity(binding.container)) return;
            final DialogFragmentResetNode resetNodeFragment =
                    DialogFragmentResetNode.newInstance(
                            getString(R.string.title_reset_node),
                            getString(R.string.reset_node_rationale_summary));
            resetNodeFragment.show(getSupportFragmentManager(), null);
        });
    }

    // =========================================================================
    // Observation
    // =========================================================================

    private void observeSelectedNode() {
        mViewModel.getSelectedMeshNode().observe(this, meshNode -> {
            if (meshNode == null) {
                Log.w(TAG, "Selected mesh node became null");
                finish();
                return;
            }

            final ElementAdapter adapter =
                    (ElementAdapter) binding.recyclerViewElements.getAdapter();
            if (adapter != null) adapter.update(meshNode);

            if (getSupportActionBar() != null)
                getSupportActionBar().setSubtitle(meshNode.getNodeName());

            binding.containerNodeName.text.setText(meshNode.getNodeName());
            updateClickableViews();
            updateCompositionDataUi(meshNode);

            final TextView netKeySummary = binding.containerNetKeys.text;
            netKeySummary.setText(meshNode.getAddedNetKeys().isEmpty()
                    ? getString(R.string.no_app_keys_added)
                    : String.valueOf(meshNode.getAddedNetKeys().size()));

            final TextView appKeySummary = binding.containerAppKeys.text;
            appKeySummary.setText(meshNode.getAddedAppKeys().isEmpty()
                    ? getString(R.string.no_app_keys_added)
                    : String.valueOf(meshNode.getAddedAppKeys().size()));

            final TextView ttlSummary = binding.containerTtl.text;
            ttlSummary.setText(meshNode.getTtl() != null
                    ? String.valueOf(meshNode.getTtl())
                    : getString(R.string.unknown));

            Log.d(TAG, "Node loaded: " + meshNode.getNodeName()
                    + " address=0x" + String.format("%04X", meshNode.getUnicastAddress()));
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
    }

    // =========================================================================
    // Auto-fetch composition data
    // =========================================================================

    private void autoFetchCompositionData() {
        if (mCompositionRequested) return;
        final ProvisionedMeshNode node = mViewModel.getSelectedMeshNode().getValue();
        if (node == null) return;
        boolean hasModels = false;
        for (Element e : node.getElements().values()) {
            if (e != null && e.getMeshModels() != null && !e.getMeshModels().isEmpty()) {
                hasModels = true;
                break;
            }
        }
        if (hasModels) return;
        if (!checkConnectivity(binding.container)) return;
        mCompositionRequested = true;
        sendMessage(new ConfigCompositionDataGet());
    }

    // =========================================================================
    // Auto-bind default AppKey
    // =========================================================================

    private void autoBindDefaultAppKey() {
        if (mAppKeyBindRequested) return;
        final ProvisionedMeshNode node = mViewModel.getSelectedMeshNode().getValue();
        if (node == null) return;
        if (mSharedViewModel.isDefaultAppKeyBound(node)) {
            mSharedViewModel.setAutoAppKeyDone(node.getUnicastAddress());
            return;
        }
        final ApplicationKey defaultKey = mSharedViewModel.getDefaultAppKey();
        if (defaultKey == null) return;
        if (node.getAddedNetKeys().isEmpty()) return;
        final int netKeyIndex = node.getAddedNetKeys().get(0).getIndex();
        final MeshNetwork network = mViewModel.getNetworkLiveData().getMeshNetwork();
        if (network == null) return;
        NetworkKey netKey = null;
        for (NetworkKey k : network.getNetKeys()) {
            if (k.getKeyIndex() == netKeyIndex) { netKey = k; break; }
        }
        if (netKey == null) return;
        if (!checkConnectivity(binding.container)) return;
        mAppKeyBindRequested = true;
        Log.d("AUTO_APP_KEY", "Sending ConfigAppKeyAdd appKey index=" + defaultKey.getKeyIndex());
        sendMessage(new ConfigAppKeyAdd(netKey, defaultKey));
    }

    // =========================================================================
    // ✅ Client address saving
    //
    // Called after CompositionDataStatus is received (UI path).
    // NrfMeshRepository also saves these automatically after auto-bind completes,
    // so this provides a redundant/UI-layer save for cases where the user opens
    // NodeConfigurationActivity on an already-provisioned client node.
    // =========================================================================

    private void handleClientAddressSaving(ProvisionedMeshNode node) {
        if (node == null) return;

        // Check if this is a Generic On Off Client
        boolean isClient = false;
        for (Element element : node.getElements().values()) {
            for (MeshModel model : element.getMeshModels().values()) {
                if (model.getModelId() == 0x1001) { // Generic On Off Client
                    isClient = true;
                    break;
                }
            }
            if (isClient) break;
        }

        if (!isClient) {
            Log.d(TAG, "handleClientAddressSaving: not a client node — skip");
            return;
        }

        // Resolve SVG device ID
        String svgDeviceId = (mSvgDeviceId != null)
                ? mSvgDeviceId
                : mSharedViewModel.getSelectedSvgDeviceId();

        if (svgDeviceId == null || svgDeviceId.isEmpty()) {
            // Last resort: use node name directly (node name should already equal the SVG ID)
            svgDeviceId = node.getNodeName();
            Log.d(TAG, "handleClientAddressSaving: using node name as svgDeviceId=" + svgDeviceId);
        }

        // Sort elements by unicast address ascending
        List<Element> sortedElements = new ArrayList<>(node.getElements().values());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            sortedElements.sort((a, b) ->
                    Integer.compare(a.getElementAddress(), b.getElementAddress()));
        }

        // Build index (1-based) → address map, up to 40 elements
        Map<Integer, Integer> elementAddresses = new HashMap<>();
        for (int i = 0; i < sortedElements.size() && i < 40; i++) {
            int elementIndex   = i + 1;
            int elementAddress = sortedElements.get(i).getElementAddress();
            elementAddresses.put(elementIndex, elementAddress);
            Log.d(TAG, "  client element " + elementIndex
                    + " address=0x" + String.format("%04X", elementAddress));
        }

        mSharedViewModel.saveAllClientElementAddresses(svgDeviceId, elementAddresses);
        Log.d(TAG, "✅ handleClientAddressSaving: saved "
                + elementAddresses.size() + " elements for svgDeviceId=" + svgDeviceId);
    }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    @Override
    protected void onStart() {
        super.onStart();
        mViewModel.setActivityVisible(true);
    }

    @Override
    protected void onStop() {
        super.onStop();
        mViewModel.setActivityVisible(false);
        if (isFinishing()) mHandler.removeCallbacksAndMessages(null);
    }

    @Override
    protected void onSaveInstanceState(@NonNull final Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(PROGRESS_BAR_STATE,
                binding.configurationProgressBar.getVisibility() == View.VISIBLE);
        outState.putBoolean(REQUESTED_PROXY_STATE, mRequestedState);
    }

    // =========================================================================
    // Mesh message handling
    // =========================================================================

    @Override
    protected void updateMeshMessage(final MeshMessage meshMessage) {
        if (meshMessage instanceof ProxyConfigFilterStatus) {
            hideProgressBar();
        }

        if (meshMessage instanceof ConfigCompositionDataStatus) {
            hideProgressBar();

            // ── Save client element addresses when composition data arrives ───
            // This covers the UI path (user opened NodeConfigurationActivity
            // on an already-provisioned client).  The automatic path is in
            // NrfMeshRepository.onAllModelsBindComplete().
            ProvisionedMeshNode node = mViewModel.getSelectedMeshNode().getValue();
            if (node != null) {
                handleClientAddressSaving(node);
            }

        } else if (meshMessage instanceof ConfigAppKeyStatus) {
            final ConfigAppKeyStatus status = (ConfigAppKeyStatus) meshMessage;
            final ProvisionedMeshNode node = mViewModel.getSelectedMeshNode().getValue();
            if (node != null) {
                if (status.isSuccessful()) {
                    Log.d("AUTO_APP_KEY", "AppKey bound successfully.");
                    mSharedViewModel.setAutoAppKeyDone(node.getUnicastAddress());
                } else {
                    Log.w("AUTO_APP_KEY", "AppKey bind failed: " + status.getStatusCode());
                    mAppKeyBindRequested = false;
                }
            }
            hideProgressBar();

        } else if (meshMessage instanceof ConfigDefaultTtlStatus) {
            hideProgressBar();

        } else if (meshMessage instanceof ConfigNodeResetStatus) {
            hideProgressBar();
            finish();

        } else if (meshMessage instanceof ConfigGattProxyStatus) {
            hideProgressBar();
        }
    }

    // =========================================================================
    // Element / Model clicks
    // =========================================================================

    @Override
    public void onElementClicked(@NonNull final Element element) {
        int elementAddress = element.getElementAddress();
        mViewModel.setSelectedElement(element);
        mViewModel.setSelectedElementAddress(elementAddress);
        Log.d("ELEMENT_CLICKED",
                "name=" + element.getName()
                        + " address=0x" + String.format("%04X", elementAddress));
    }

    @Override
    public void onModelClicked(@NonNull final ProvisionedMeshNode meshNode,
                               @NonNull final Element element,
                               @NonNull final MeshModel model) {
        mViewModel.setSelectedElement(element);
        mViewModel.setSelectedElementAddress(element.getElementAddress());
        mViewModel.setSelectedModel(model);

        // Forward svgDeviceId to PublicationSettingsActivity via SharedViewModel
        if (mSvgDeviceId != null) {
            mSharedViewModel.setSelectedSvgDeviceId(mSvgDeviceId);
            Log.d(TAG, "Stored svgDeviceId=" + mSvgDeviceId
                    + " in SharedViewModel for PublicationSettingsActivity");
        }

        mViewModel.navigateToModelActivity(this, model);
    }

    // =========================================================================
    // Dialog callbacks
    // =========================================================================

    @Override public void onNodeReset() { sendMessage(new ConfigNodeReset()); }
    @Override public void onConfigurationCompleted() {}

    @Override
    public boolean onNodeNameUpdated(@NonNull final String nodeName) {
        final MeshNetwork network = mViewModel.getNetworkLiveData().getMeshNetwork();
        final ProvisionedMeshNode node = mViewModel.getSelectedMeshNode().getValue();
        if (node != null) {
            node.setNodeName(nodeName);
            return network.updateNodeName(node, nodeName);
        }
        return false;
    }

    @Override
    public boolean onElementNameUpdated(final int address, @NonNull final String name) {
        return mViewModel.getNetworkLiveData().getMeshNetwork()
                .updateElementName(address, name);
    }

    @Override
    public boolean setDefaultTtl(final int ttl) {
        sendMessage(new ConfigDefaultTtlSet(ttl));
        return true;
    }

    @Override
    public void onProxySet(final int state) {
        sendMessage(new ConfigGattProxySet(state));
    }

    // =========================================================================
    // UI helpers
    // =========================================================================

    @Override
    protected void showProgressBar() {
        mHandler.postDelayed(mRunnableOperationTimeout, Utils.MESSAGE_TIME_OUT);
        disableClickableViews();
        binding.configurationProgressBar.setVisibility(View.VISIBLE);
    }

    @Override
    protected void hideProgressBar() {
        enableClickableViews();
        binding.configurationProgressBar.setVisibility(View.INVISIBLE);
        mHandler.removeCallbacks(mRunnableOperationTimeout);
    }

    @Override
    protected void enableClickableViews() {
        binding.actionGetCompositionData.setEnabled(true);
        binding.actionGetDefaultTtl.setEnabled(true);
        binding.actionSetDefaultTtl.setEnabled(true);
        binding.actionResetNode.setEnabled(true);
    }

    @Override
    protected void disableClickableViews() {
        binding.actionGetCompositionData.setEnabled(false);
        binding.actionGetDefaultTtl.setEnabled(false);
        binding.actionSetDefaultTtl.setEnabled(false);
        binding.actionResetNode.setEnabled(false);
    }

    @Override
    protected void updateClickableViews() {
        final ProvisionedMeshNode meshNode = mViewModel.getSelectedMeshNode().getValue();
        if (meshNode != null && meshNode.isConfigured()
                && !mViewModel.isModelExists(SigModelParser.CONFIGURATION_SERVER)) {
            disableClickableViews();
        }
    }

    private void updateCompositionDataUi(final ProvisionedMeshNode meshNode) {
        for (Element e : meshNode.getElements().values()) {
            if (!e.getMeshModels().isEmpty()) {
                binding.compositionActionContainer.setVisibility(View.GONE);
                binding.noElements.setVisibility(View.INVISIBLE);
                binding.recyclerViewElements.setVisibility(View.VISIBLE);
            } else {
                binding.noElements.setVisibility(View.VISIBLE);
                binding.compositionActionContainer.setVisibility(View.VISIBLE);
                binding.recyclerViewElements.setVisibility(View.INVISIBLE);
            }
            break;
        }
    }

    private void sendMessage(final MeshMessage meshMessage) {
        try {
            if (!checkConnectivity(binding.container)) return;
            final ProvisionedMeshNode node = mViewModel.getSelectedMeshNode().getValue();
            if (node != null) {
                mViewModel.getMeshManagerApi().createMeshPdu(node.getUnicastAddress(), meshMessage);
                showProgressBar();
            }
        } catch (IllegalArgumentException ex) {
            hideProgressBar();
            final DialogFragmentError message = DialogFragmentError.newInstance(
                    getString(R.string.title_error),
                    ex.getMessage() == null
                            ? getString(R.string.unknwon_error) : ex.getMessage());
            message.show(getSupportFragmentManager(), null);
        }
    }
}