package no.nordicsemi.android.swaromapmesh.node;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;

import dagger.hilt.android.AndroidEntryPoint;
import no.nordicsemi.android.swaromapmesh.ApplicationKey;
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

    private static final String PROGRESS_BAR_STATE   = "PROGRESS_BAR_STATE";
    private static final String PROXY_STATE           = "PROXY_STATE";
    private static final String REQUESTED_PROXY_STATE = "REQUESTED_PROXY_STATE";

    private ActivityNodeConfigurationBinding binding;
    private SharedViewModel mSharedViewModel;           // ✅ typed reference for AppKey methods
    private boolean mRequestedState       = true;
    private boolean mCompositionRequested = false;
    private boolean mAppKeyBindRequested  = false;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityNodeConfigurationBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        mViewModel = new ViewModelProvider(this).get(NodeConfigurationViewModel.class);
        mSharedViewModel = new ViewModelProvider(this).get(SharedViewModel.class); // ✅
        initialize();

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

        if (mViewModel.getSelectedMeshNode().getValue() == null) {
            finish();
            return;
        }

        // Toolbar
        setSupportActionBar(binding.toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.title_node_configuration);
        }

        // ---------------- Node Name Card ----------------
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

        final Button actionDetails = findViewById(R.id.action_show_details);
        actionDetails.setOnClickListener(v ->
                startActivity(new Intent(NodeConfigurationActivity.this,
                        NodeDetailsActivity.class)));

        // ---------------- Elements Recycler ----------------
        binding.recyclerViewElements.setLayoutManager(new LinearLayoutManager(this));
        final ElementAdapter adapter = new ElementAdapter();
        adapter.setOnItemClickListener(this);
        binding.recyclerViewElements.setAdapter(adapter);

        // ---------------- NetKeys Card ----------------
        binding.containerNetKeys.image.setBackground(
                ContextCompat.getDrawable(this, R.drawable.ic_vpn_key_24dp));
        binding.containerNetKeys.title.setText(R.string.title_net_keys);

        final TextView netKeySummary = binding.containerNetKeys.text;
        netKeySummary.setVisibility(View.VISIBLE);

        binding.containerNetKeys.getRoot().setOnClickListener(v ->
                startActivity(new Intent(this, AddNetKeysActivity.class)));

        // ---------------- AppKeys Card ----------------
        binding.containerAppKeys.image.setBackground(
                ContextCompat.getDrawable(this, R.drawable.ic_vpn_key_24dp));
        binding.containerAppKeys.title.setText(R.string.title_app_keys);

        final TextView appKeySummary = binding.containerAppKeys.text;
        appKeySummary.setVisibility(View.VISIBLE);

        binding.containerAppKeys.getRoot().setOnClickListener(v ->
                startActivity(new Intent(this, AddAppKeysActivity.class)));

        // ---------------- TTL Card ----------------
        binding.containerTtl.image.setBackground(
                ContextCompat.getDrawable(this, R.drawable.ic_numeric));
        binding.containerTtl.title.setText(R.string.title_ttl);

        final TextView defaultTtlSummary = binding.containerTtl.text;
        defaultTtlSummary.setVisibility(View.VISIBLE);

        // ---------------- Observe Node ----------------
        mViewModel.getSelectedMeshNode().observe(this, meshNode -> {
            if (meshNode == null) {
                finish();
                return;
            }

            adapter.update(meshNode);

            if (getSupportActionBar() != null) {
                getSupportActionBar().setSubtitle(meshNode.getNodeName());
            }

            nodeNameView.setText(meshNode.getNodeName());

            updateClickableViews();
            updateCompositionDataUi(meshNode);

            if (!meshNode.getAddedNetKeys().isEmpty()) {
                netKeySummary.setText(String.valueOf(meshNode.getAddedNetKeys().size()));
            } else {
                netKeySummary.setText(R.string.no_app_keys_added);
            }

            if (!meshNode.getAddedAppKeys().isEmpty()) {
                appKeySummary.setText(String.valueOf(meshNode.getAddedAppKeys().size()));
            } else {
                appKeySummary.setText(R.string.no_app_keys_added);
            }

            if (meshNode.getTtl() != null) {
                defaultTtlSummary.setText(String.valueOf(meshNode.getTtl()));
            } else {
                defaultTtlSummary.setText(R.string.unknown);
            }
        });

        // ---------------- Action Buttons ----------------
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
                            getString(R.string.reset_node_rationale_summary)
                    );
            resetNodeFragment.show(getSupportFragmentManager(), null);
        });

        updateProxySettingsCardUi();
        autoFetchCompositionData();
        autoBindDefaultAppKey(); // ✅ Auto-bind AppKey on first open
    }

    @Override
    protected void onResume() {
        super.onResume();
        // AppKey auto-bind is handled via autoBindDefaultAppKey() in onCreate.
    }

    // ─────────────────────────────────────────────────────────────
    //  Auto-fetch Composition Data
    // ─────────────────────────────────────────────────────────────

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

    // ─────────────────────────────────────────────────────────────
    //  Auto-bind Default AppKey via ConfigAppKeyAdd mesh message
    // ─────────────────────────────────────────────────────────────

    private void autoBindDefaultAppKey() {
        if (mAppKeyBindRequested) return;

        final ProvisionedMeshNode node = mViewModel.getSelectedMeshNode().getValue();
        if (node == null) return;

        // Already bound — nothing to do
        if (mSharedViewModel.isDefaultAppKeyBound(node)) {
            mSharedViewModel.setAutoAppKeyDone(node.getUnicastAddress());
            return;
        }

        final ApplicationKey defaultKey = mSharedViewModel.getDefaultAppKey();
        if (defaultKey == null) return;

        // NetKey object is required for ConfigAppKeyAdd
        if (node.getAddedNetKeys().isEmpty()) return;
        final int netKeyIndex = node.getAddedNetKeys().get(0).getIndex();

        // ✅ Get the actual NetworkKey object from the mesh network
        final MeshNetwork network = mViewModel.getNetworkLiveData().getMeshNetwork();
        if (network == null) return;

        NetworkKey netKey = null;
        for (NetworkKey k : network.getNetKeys()) {
            if (k.getKeyIndex() == netKeyIndex) {
                netKey = k;
                break;
            }
        }
        if (netKey == null) return;

        if (!checkConnectivity(binding.container)) return;

        mAppKeyBindRequested = true;

        Log.d("AUTO_APP_KEY", "Sending ConfigAppKeyAdd for appKey index: "
                + defaultKey.getKeyIndex());

        // ✅ ConfigAppKeyAdd(NetworkKey, ApplicationKey)
        sendMessage(new ConfigAppKeyAdd(netKey, defaultKey));
    }
    // ─────────────────────────────────────────────────────────────
    //  Lifecycle
    // ─────────────────────────────────────────────────────────────

    @Override
    protected void onStart() {
        super.onStart();
        mViewModel.setActivityVisible(true);
    }

    @Override
    protected void onStop() {
        super.onStop();
        mViewModel.setActivityVisible(false);
        if (isFinishing()) {
            mHandler.removeCallbacksAndMessages(null);
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull final Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(PROGRESS_BAR_STATE,
                binding.configurationProgressBar.getVisibility() == View.VISIBLE);
        outState.putBoolean(REQUESTED_PROXY_STATE, mRequestedState);
    }

    // ─────────────────────────────────────────────────────────────
    //  Mesh Message Handling
    // ─────────────────────────────────────────────────────────────

    protected void updateMeshMessage(final MeshMessage meshMessage) {
        if (meshMessage instanceof ProxyConfigFilterStatus) {
            hideProgressBar();
        }
        if (meshMessage instanceof ConfigCompositionDataStatus) {
            hideProgressBar();
        } else if (meshMessage instanceof ConfigAppKeyStatus) {
            // ✅ AppKey bind response from node
            final ConfigAppKeyStatus status = (ConfigAppKeyStatus) meshMessage;
            final ProvisionedMeshNode node = mViewModel.getSelectedMeshNode().getValue();
            if (node != null) {
                if (status.isSuccessful()) {
                    Log.d("AUTO_APP_KEY", "AppKey bound successfully.");
                    mSharedViewModel.setAutoAppKeyDone(node.getUnicastAddress());
                } else {
                    Log.w("AUTO_APP_KEY", "AppKey bind failed: " + status.getStatusCode());
                    mAppKeyBindRequested = false; // allow retry
                }
            }
            hideProgressBar();
        } else if (meshMessage instanceof ConfigDefaultTtlStatus) {
            hideProgressBar();
        } else if (meshMessage instanceof ConfigNodeResetStatus) {
            hideProgressBar();
            finish();
        } else if (meshMessage instanceof ConfigGattProxyStatus) {
            updateProxySettingsCardUi();
            hideProgressBar();
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  Element / Model clicks
    // ─────────────────────────────────────────────────────────────

    @Override
    public void onElementClicked(@NonNull final Element element) {
        int elementAddress = element.getElementAddress();
        mViewModel.setSelectedElement(element);
        mViewModel.setSelectedElementAddress(elementAddress);
        Log.d("ELEMENT_CLICKED",
                "name=" + element.getName() +
                        ", address=0x" + String.format("%04X", elementAddress));
    }

    @Override
    public void onModelClicked(@NonNull final ProvisionedMeshNode meshNode,
                               @NonNull final Element element,
                               @NonNull final MeshModel model) {
        mViewModel.setSelectedElement(element);
        mViewModel.setSelectedElementAddress(element.getElementAddress());
        mViewModel.setSelectedModel(model);
        mViewModel.navigateToModelActivity(this, model);
    }

    // ─────────────────────────────────────────────────────────────
    //  Dialog callbacks
    // ─────────────────────────────────────────────────────────────

    @Override
    public void onNodeReset() {
        sendMessage(new ConfigNodeReset());
    }

    @Override
    public void onConfigurationCompleted() {
        // do nothing
    }

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
        final MeshNetwork network = mViewModel.getNetworkLiveData().getMeshNetwork();
        return network.updateElementName(address, name);
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

    // ─────────────────────────────────────────────────────────────
    //  UI Helpers
    // ─────────────────────────────────────────────────────────────

    private void updateProxySettingsCardUi() {
        final ProvisionedMeshNode meshNode = mViewModel.getSelectedMeshNode().getValue();
        if (meshNode != null && meshNode.getNodeFeatures() != null
                && meshNode.getNodeFeatures().isProxyFeatureSupported()) {
            // optional UI update
        }
    }

    protected void showProgressBar() {
        mHandler.postDelayed(mRunnableOperationTimeout, Utils.MESSAGE_TIME_OUT);
        disableClickableViews();
        binding.configurationProgressBar.setVisibility(View.VISIBLE);
    }

    protected void hideProgressBar() {
        enableClickableViews();
        binding.configurationProgressBar.setVisibility(View.INVISIBLE);
        mHandler.removeCallbacks(mRunnableOperationTimeout);
    }

    protected void enableClickableViews() {
        binding.actionGetCompositionData.setEnabled(true);
        binding.actionGetDefaultTtl.setEnabled(true);
        binding.actionSetDefaultTtl.setEnabled(true);
        binding.actionResetNode.setEnabled(true);
    }

    protected void disableClickableViews() {
        binding.actionGetCompositionData.setEnabled(false);
        binding.actionGetDefaultTtl.setEnabled(false);
        binding.actionSetDefaultTtl.setEnabled(false);
        binding.actionResetNode.setEnabled(false);
    }

    protected void updateClickableViews() {
        final ProvisionedMeshNode meshNode = mViewModel.getSelectedMeshNode().getValue();
        if (meshNode != null && meshNode.isConfigured() &&
                !mViewModel.isModelExists(SigModelParser.CONFIGURATION_SERVER)) {
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
                mViewModel.getMeshManagerApi().createMeshPdu(
                        node.getUnicastAddress(), meshMessage);
                showProgressBar();
            }
        } catch (IllegalArgumentException ex) {
            hideProgressBar();
            final DialogFragmentError message = DialogFragmentError.newInstance(
                    getString(R.string.title_error),
                    ex.getMessage() == null
                            ? getString(R.string.unknwon_error)
                            : ex.getMessage()
            );
            message.show(getSupportFragmentManager(), null);
        }
    }
}