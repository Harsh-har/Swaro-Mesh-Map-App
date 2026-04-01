package no.nordicsemi.android.swaromapmesh;

import android.bluetooth.BluetoothAdapter;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.SearchView;

import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.snackbar.Snackbar;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import dagger.hilt.android.AndroidEntryPoint;
import no.nordicsemi.android.swaromapmesh.adapter.ExtendedBluetoothDevice;
import no.nordicsemi.android.swaromapmesh.ble.AutoProxyConnectManager;
import no.nordicsemi.android.swaromapmesh.ble.ReconnectActivity;
import no.nordicsemi.android.swaromapmesh.ble.ScannerActivity;
import no.nordicsemi.android.swaromapmesh.databinding.FragmentGroupsBinding;
import no.nordicsemi.android.swaromapmesh.dialog.DialogFragmentDeleteNode;
import no.nordicsemi.android.swaromapmesh.dialog.DialogFragmentError;
import no.nordicsemi.android.swaromapmesh.node.NodeConfigurationActivity;
import no.nordicsemi.android.swaromapmesh.node.adapter.NodeAdapter;
import no.nordicsemi.android.swaromapmesh.transport.ProvisionedMeshNode;
import no.nordicsemi.android.swaromapmesh.utils.Utils;
import no.nordicsemi.android.swaromapmesh.viewmodels.SharedViewModel;
import no.nordicsemi.android.swaromapmesh.widgets.ItemTouchHelperAdapter;
import no.nordicsemi.android.swaromapmesh.widgets.RemovableItemTouchHelperCallback;
import no.nordicsemi.android.swaromapmesh.widgets.RemovableViewHolder;

import static android.app.Activity.RESULT_OK;

@AndroidEntryPoint
public class GroupsFragment extends Fragment implements
        NodeAdapter.OnItemClickListener,
        ItemTouchHelperAdapter,
        DialogFragmentDeleteNode.DialogFragmentDeleteNodeListener {

    private static final String TAG                    = "NetworkFragment";
    private static final long   AUTO_PROXY_SCAN_WINDOW = 5000L;

    private FragmentGroupsBinding binding;
    private SharedViewModel       mViewModel;
    private NodeAdapter           mNodeAdapter;

    // Auto-proxy
    private AutoProxyConnectManager mAutoProxyManager;
    private boolean                 mAutoConnectInProgress          = false;
    private boolean                 mAutoConnectTriggeredThisSession = false;

    // ─────────────────────────────────────────────────────────────
    // Activity Result Launchers
    // ─────────────────────────────────────────────────────────────

    private final ActivityResultLauncher<Intent> provisioner =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                    this::handleProvisioningResult);

    private final ActivityResultLauncher<Intent> proxyConnector =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                    this::handleProxyConnectResult);

    // ─────────────────────────────────────────────────────────────
    // onCreateView
    // ─────────────────────────────────────────────────────────────

    @Nullable
    @Override
    public View onCreateView(@NonNull final LayoutInflater inflater,
                             @Nullable final ViewGroup viewGroup,
                             @Nullable final Bundle savedInstanceState) {

        binding    = FragmentGroupsBinding.inflate(getLayoutInflater());
        mViewModel = new ViewModelProvider(requireActivity()).get(SharedViewModel.class);

        final ExtendedFloatingActionButton fab              = binding.fabAddNode;
        final RecyclerView                 recyclerViewNodes = binding.recyclerViewProvisionedNodes;
        final View                         noNetworksView   = binding.noNetworksConfigured.getRoot();

        mNodeAdapter = new NodeAdapter(this, mViewModel.getNodes());
        mNodeAdapter.setOnItemClickListener(this);

        recyclerViewNodes.setLayoutManager(new LinearLayoutManager(getContext()));
        recyclerViewNodes.addItemDecoration(
                new DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL));

        final ItemTouchHelper itemTouchHelper =
                new ItemTouchHelper(new RemovableItemTouchHelperCallback(this));
        itemTouchHelper.attachToRecyclerView(recyclerViewNodes);
        recyclerViewNodes.setAdapter(mNodeAdapter);

        // Observe nodes — trigger auto-connect when data first arrives
        mViewModel.getNodes().observe(getViewLifecycleOwner(), nodes -> {
            final boolean hasNodes = nodes != null && !nodes.isEmpty();
            noNetworksView.setVisibility(hasNodes ? View.GONE : View.VISIBLE);
            requireActivity().invalidateOptionsMenu();

            if (hasNodes && !mAutoConnectTriggeredThisSession) {
                mAutoConnectTriggeredThisSession = true;
                Log.d(TAG, "Nodes loaded (" + nodes.size() + ") — triggering auto-connect");
                tryAutoConnectToNearestProxy(nodes);
            }
        });

        mViewModel.isConnectedToProxy().observe(getViewLifecycleOwner(), isConnected -> {
            requireActivity().invalidateOptionsMenu();
            if (Boolean.TRUE.equals(isConnected) && mAutoConnectInProgress) {
                Log.d(TAG, "Proxy connected — cancelling background scan");
                stopAutoProxyScan();
            }
        });

        recyclerViewNodes.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                super.onScrolled(rv, dx, dy);
                final LinearLayoutManager lm = (LinearLayoutManager) rv.getLayoutManager();
                if (lm != null) fab.setExtended(lm.findFirstCompletelyVisibleItemPosition() == 0);
            }
        });

        fab.setOnClickListener(v -> {
            final Intent intent = new Intent(requireContext(), ScannerActivity.class);
            intent.putExtra(Utils.EXTRA_DATA_PROVISIONING_SERVICE, true);
            provisioner.launch(intent);
        });

        binding.searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override public boolean onQueryTextSubmit(String q) {
                mNodeAdapter.filter(q); return true;
            }
            @Override public boolean onQueryTextChange(String t) {
                mNodeAdapter.filter(t); return true;
            }
        });

        mNodeAdapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
            @Override public void onChanged() {
                noNetworksView.setVisibility(
                        mNodeAdapter.getItemCount() == 0 ? View.VISIBLE : View.GONE);
            }
        });

        return binding.getRoot();
    }

    // ─────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────

    @Override
    public void onPause() {
        super.onPause();
        stopAutoProxyScan();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        stopAutoProxyScan();
        binding = null;
    }

    // ─────────────────────────────────────────────────────────────
    // Auto-connect to nearest proxy
    // ─────────────────────────────────────────────────────────────

    private void tryAutoConnectToNearestProxy(List<ProvisionedMeshNode> nodes) {
        final Boolean isConnected = mViewModel.isConnectedToProxy().getValue();
        if (Boolean.TRUE.equals(isConnected)) {
            Log.d(TAG, "Auto-proxy: already connected, skipping");
            return;
        }
        if (mAutoConnectInProgress) {
            Log.d(TAG, "Auto-proxy: scan already in progress");
            return;
        }

        final Set<String> knownMacs = new HashSet<>();
        for (ProvisionedMeshNode node : nodes) {
            final String mac = node.getMacAddress();
            if (mac != null && !mac.isEmpty()) knownMacs.add(mac.toUpperCase());
        }

        if (knownMacs.isEmpty()) {
            Log.d(TAG, "Auto-proxy: no valid MACs — trying any proxy");
            startProxyScan(null);
        } else {
            Log.d(TAG, "Auto-proxy: scanning for " + knownMacs.size() + " known node(s)");
            startProxyScan(knownMacs);
        }
    }

    private void startProxyScan(@Nullable Set<String> knownMacs) {
        mAutoConnectInProgress = true;

        if (mAutoProxyManager != null) mAutoProxyManager.stop();
        mAutoProxyManager = new AutoProxyConnectManager(requireContext());

        mAutoProxyManager.findBestProxy(knownMacs, AUTO_PROXY_SCAN_WINDOW, bestMac -> {
            mAutoConnectInProgress = false;

            if (bestMac == null) {
                Log.d(TAG, "Auto-proxy: no proxy device found nearby");
                return;
            }
            final Boolean stillConnected = mViewModel.isConnectedToProxy().getValue();
            if (Boolean.TRUE.equals(stillConnected)) {
                Log.d(TAG, "Auto-proxy: connected while scanning — skipping");
                return;
            }
            if (!isAdded() || !isResumed()) {
                Log.w(TAG, "Auto-proxy: fragment not active — skipping for " + bestMac);
                return;
            }

            Log.i(TAG, "Auto-proxy: launching silent connect → " + bestMac);
            startProxyConnectInBackground(bestMac);
        });
    }

    private void stopAutoProxyScan() {
        if (mAutoProxyManager != null) {
            mAutoProxyManager.stop();
            mAutoProxyManager = null;
        }
        mAutoConnectInProgress = false;
    }

    // ─────────────────────────────────────────────────────────────
    // Node click — manual configure
    // ─────────────────────────────────────────────────────────────

    @Override
    public void onConfigureClicked(final ProvisionedMeshNode node) {
        mViewModel.setSelectedMeshNode(node);
        navigateToNodeConfig(node);
    }

    private void navigateToNodeConfig(final ProvisionedMeshNode node) {
        if (!mViewModel.isProxyEnabled()) {
            startActivity(new Intent(requireActivity(), NodeConfigurationActivity.class));
            return;
        }

        final Boolean isConnected = mViewModel.isConnectedToProxy().getValue();
        if (Boolean.TRUE.equals(isConnected)) {
            startActivity(new Intent(requireActivity(), NodeConfigurationActivity.class));
        } else {
            stopAutoProxyScan();
            startProxyConnectInBackground(node.getMacAddress());
        }
    }

    private void startProxyConnectInBackground(@Nullable String macAddress) {
        if (macAddress == null) {
            Log.w(TAG, "startProxyConnectInBackground: macAddress is null, skipping");
            return;
        }

        Log.d(TAG, "Direct silent connect to MAC: " + macAddress);

        final ExtendedBluetoothDevice device = getDeviceFromMac(macAddress);
        if (device == null) {
            Log.e(TAG, "Could not build device for MAC: " + macAddress);
            return;
        }

        final Intent intent = new Intent(requireContext(), ReconnectActivity.class);
        intent.putExtra(Utils.EXTRA_DEVICE, device);
        intent.putExtra(Utils.EXTRA_SILENT_CONNECT, true);
        intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        proxyConnector.launch(intent);
    }

    @Nullable
    private ExtendedBluetoothDevice getDeviceFromMac(String macAddress) {
        try {
            final BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null) {
                Log.e(TAG, "BluetoothAdapter is null");
                return null;
            }

            final android.bluetooth.BluetoothDevice btDevice =
                    adapter.getRemoteDevice(macAddress.toUpperCase());

            final no.nordicsemi.android.support.v18.scanner.ScanResult nordicScanResult =
                    new no.nordicsemi.android.support.v18.scanner.ScanResult(
                            btDevice,
                            null,
                            -70,
                            0
                    );

            return new ExtendedBluetoothDevice(nordicScanResult);

        } catch (Exception e) {
            Log.e(TAG, "getDeviceFromMac error: " + e.getMessage());
            return null;
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Swipe-to-delete
    // ─────────────────────────────────────────────────────────────

    @Override
    public void onItemDismiss(final RemovableViewHolder viewHolder) {
        final int position = viewHolder.getAdapterPosition();
        if (!mNodeAdapter.isEmpty()) {
            DialogFragmentDeleteNode.newInstance(position)
                    .show(getChildFragmentManager(), null);
        }
    }

    @Override public void onItemDismissFailed(final RemovableViewHolder viewHolder) {}

    @Override
    public void onNodeDeleteConfirmed(final int position) {

        final ProvisionedMeshNode node = mNodeAdapter.getItem(position);

        boolean success = mViewModel.fullyDeleteNode(node);

        if (success) {
            mViewModel.displaySnackBar(
                    requireActivity(),
                    binding.container,
                    getString(R.string.node_deleted),
                    Snackbar.LENGTH_LONG
            );
        } else {
            Log.e(TAG, "Delete failed");
        }
    }
    @Override
    public void onNodeDeleteCancelled(final int position) {
        mNodeAdapter.notifyItemChanged(position);
    }

    // ─────────────────────────────────────────────────────────────
    // Activity result handlers
    // ─────────────────────────────────────────────────────────────

    private void handleProvisioningResult(final ActivityResult result) {
        mAutoConnectTriggeredThisSession = false; // reset so auto-connect runs again

        final Intent data = result.getData();
        if (result.getResultCode() == RESULT_OK && data != null) {
            final boolean success = data.getBooleanExtra(Utils.PROVISIONING_COMPLETED, false);
            if (success) {

                ProvisionedMeshNode node = mViewModel.getSelectedMeshNode().getValue();
                mViewModel.autoMapNodeToCurrentSvg(node); // 🔥🔥🔥 MOST IMPORTANT

                final Intent intent = new Intent(requireContext(), ScannerActivity.class);
                intent.putExtra(Utils.EXTRA_DATA_PROVISIONING_SERVICE, false);
                intent.putExtra(Utils.EXTRA_NEWLY_PROVISIONED_NODE, true);
                intent.putExtra(Utils.EXTRA_SILENT_CONNECT, true);
                intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
                proxyConnector.launch(intent);
            }
            requireActivity().invalidateOptionsMenu();
        }
    }

    private void handleProxyConnectResult(final ActivityResult result) {
        mAutoConnectInProgress = false;
        if (result.getResultCode() == RESULT_OK) {
            startActivity(new Intent(requireActivity(), NodeConfigurationActivity.class));
        }
    }

    private void showErrorDialog(@NonNull final String title, @NonNull final String message) {
        DialogFragmentError.newInstance(title, message)
                .show(getChildFragmentManager(), null);
    }
}
