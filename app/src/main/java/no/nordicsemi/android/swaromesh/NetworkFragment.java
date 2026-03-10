package no.nordicsemi.android.swaromesh;

import android.content.Intent;
import android.os.Bundle;
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

import dagger.hilt.android.AndroidEntryPoint;
import no.nordicsemi.android.swaromesh.Map.HomePageActivity;
import no.nordicsemi.android.swaromesh.ble.ScannerActivity;
import no.nordicsemi.android.swaromesh.databinding.FragmentNetworkBinding;
import no.nordicsemi.android.swaromesh.dialog.DialogFragmentDeleteNode;
import no.nordicsemi.android.swaromesh.dialog.DialogFragmentError;
import no.nordicsemi.android.swaromesh.node.NodeConfigurationActivity;
import no.nordicsemi.android.swaromesh.node.adapter.NodeAdapter;
import no.nordicsemi.android.swaromesh.transport.ProvisionedMeshNode;
import no.nordicsemi.android.swaromesh.utils.Utils;
import no.nordicsemi.android.swaromesh.viewmodels.SharedViewModel;
import no.nordicsemi.android.swaromesh.widgets.ItemTouchHelperAdapter;
import no.nordicsemi.android.swaromesh.widgets.RemovableItemTouchHelperCallback;
import no.nordicsemi.android.swaromesh.widgets.RemovableViewHolder;

import static android.app.Activity.RESULT_OK;

@AndroidEntryPoint
public class NetworkFragment extends Fragment implements
        NodeAdapter.OnItemClickListener,
        ItemTouchHelperAdapter,
        DialogFragmentDeleteNode.DialogFragmentDeleteNodeListener {

    private FragmentNetworkBinding binding;
    private SharedViewModel mViewModel;
    private NodeAdapter mNodeAdapter;

    // For provisioning a new node via ScannerActivity
    private final ActivityResultLauncher<Intent> provisioner =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                    this::handleProvisioningResult);

    // For connecting to a proxy after provisioning or node config









    private final ActivityResultLauncher<Intent> proxyConnector =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                    this::handleProxyConnectResult);

    @Nullable
    @Override
    public View onCreateView(@NonNull final LayoutInflater inflater,
                             @Nullable final ViewGroup viewGroup,
                             @Nullable final Bundle savedInstanceState) {

        binding = FragmentNetworkBinding.inflate(getLayoutInflater());
        mViewModel = new ViewModelProvider(requireActivity()).get(SharedViewModel.class);

        final ExtendedFloatingActionButton fab = binding.fabAddNode;
        final ExtendedFloatingActionButton map = binding.mapnode;

        final RecyclerView mRecyclerViewNodes = binding.recyclerViewProvisionedNodes;
        final View noNetworksConfiguredView = binding.noNetworksConfigured.getRoot();

        mNodeAdapter = new NodeAdapter(this, mViewModel.getNodes());
        mNodeAdapter.setOnItemClickListener(this);

        mRecyclerViewNodes.setLayoutManager(new LinearLayoutManager(getContext()));
        mRecyclerViewNodes.addItemDecoration(
                new DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL));

        final ItemTouchHelper.Callback itemTouchHelperCallback =
                new RemovableItemTouchHelperCallback(this);
        final ItemTouchHelper itemTouchHelper = new ItemTouchHelper(itemTouchHelperCallback);
        itemTouchHelper.attachToRecyclerView(mRecyclerViewNodes);
        mRecyclerViewNodes.setAdapter(mNodeAdapter);

        mViewModel.getNodes().observe(getViewLifecycleOwner(), nodes -> {
            noNetworksConfiguredView.setVisibility(nodes != null && !nodes.isEmpty()
                    ? View.GONE : View.VISIBLE);
            requireActivity().invalidateOptionsMenu();
        });

        mViewModel.isConnectedToProxy().observe(getViewLifecycleOwner(),
                isConnected -> requireActivity().invalidateOptionsMenu());

        mRecyclerViewNodes.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull final RecyclerView recyclerView,
                                   final int dx, final int dy) {
                super.onScrolled(recyclerView, dx, dy);
                final LinearLayoutManager m =
                        (LinearLayoutManager) recyclerView.getLayoutManager();
                if (m != null) fab.setExtended(m.findFirstCompletelyVisibleItemPosition() == 0);
            }
        });

        // FAB → start provisioning
        fab.setOnClickListener(v -> {
            final Intent intent = new Intent(requireContext(), ScannerActivity.class);
            intent.putExtra(Utils.EXTRA_DATA_PROVISIONING_SERVICE, true);
            provisioner.launch(intent);
        });

        // ✅ FIXED: was using provisioner.launch() which incorrectly routed the back-result
        //    to handleProvisioningResult(). CommandActivity returns no result,
        //    so plain startActivity() is the correct call here.
        map.setOnClickListener(v ->
                startActivity(new Intent(requireContext(), HomePageActivity.class)));

        binding.searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                mNodeAdapter.filter(query);
                return true;
            }
            @Override
            public boolean onQueryTextChange(String newText) {
                mNodeAdapter.filter(newText);
                return true;
            }
        });

        mNodeAdapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
            @Override
            public void onChanged() {
                noNetworksConfiguredView.setVisibility(mNodeAdapter.getItemCount() == 0
                        ? View.VISIBLE : View.GONE);
            }
        });

        return binding.getRoot();
    }

    @Override
    public void onConfigureClicked(final ProvisionedMeshNode node) {
        mViewModel.setSelectedMeshNode(node);

        if (!mViewModel.isProxyEnabled()) {
            startActivity(new Intent(requireActivity(), NodeConfigurationActivity.class));
            return;
        }

        final Boolean isConnected = mViewModel.isConnectedToProxy().getValue();
        if (Boolean.TRUE.equals(isConnected)) {
            startActivity(new Intent(requireActivity(), NodeConfigurationActivity.class));
        } else {
            startProxyConnectInBackground(node.getMacAddress());
        }
    }

    private void startProxyConnectInBackground(@Nullable String macAddress) {
        final Intent intent = new Intent(requireContext(), ScannerActivity.class);
        intent.putExtra(Utils.EXTRA_DATA_PROVISIONING_SERVICE, false);
        intent.putExtra(Utils.EXTRA_SILENT_CONNECT, true);
        intent.putExtra(Utils.EXTRA_TARGET_PROXY_MAC, macAddress);
        proxyConnector.launch(intent);
    }

    @Override
    public void onItemDismiss(final RemovableViewHolder viewHolder) {
        final int position = viewHolder.getAdapterPosition();
        if (!mNodeAdapter.isEmpty()) {
            DialogFragmentDeleteNode.newInstance(position)
                    .show(getChildFragmentManager(), null);
        }
    }

    @Override
    public void onItemDismissFailed(final RemovableViewHolder viewHolder) {}

    @Override
    public void onNodeDeleteConfirmed(final int position) {
        final ProvisionedMeshNode node = mNodeAdapter.getItem(position);
        if (mViewModel.getNetworkLiveData().getMeshNetwork().deleteNode(node)) {
            mViewModel.displaySnackBar(requireActivity(),
                    binding.container,
                    getString(R.string.node_deleted),
                    Snackbar.LENGTH_LONG);
        }
    }

    @Override
    public void onNodeDeleteCancelled(final int position) {
        mNodeAdapter.notifyItemChanged(position);
    }

    private void handleProvisioningResult(final ActivityResult result) {
        final Intent data = result.getData();
        if (result.getResultCode() == RESULT_OK && data != null) {
            final boolean provisioningSuccess =
                    data.getBooleanExtra(Utils.PROVISIONING_COMPLETED, false);
            if (provisioningSuccess) {
                final Intent intent = new Intent(requireContext(), ScannerActivity.class);
                intent.putExtra(Utils.EXTRA_DATA_PROVISIONING_SERVICE, false);
                intent.putExtra(Utils.EXTRA_NEWLY_PROVISIONED_NODE, true);
                intent.putExtra(Utils.EXTRA_SILENT_CONNECT, true);
                proxyConnector.launch(intent);
            }
            requireActivity().invalidateOptionsMenu();
        }
    }

    private void handleProxyConnectResult(final ActivityResult result) {
        if (result.getResultCode() == RESULT_OK) {
            startActivity(new Intent(requireActivity(), NodeConfigurationActivity.class));
        }
    }

    private void showErrorDialog(@NonNull final String title, @NonNull final String message) {
        DialogFragmentError.newInstance(title, message)
                .show(getChildFragmentManager(), null);
    }
}