package no.nordicsemi.android.swaromesh;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import java.util.ArrayList;
import java.util.List;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textview.MaterialTextView;
import dagger.hilt.android.AndroidEntryPoint;
import no.nordicsemi.android.swaromesh.adapter.FilterAddressAdapter;
import no.nordicsemi.android.swaromesh.databinding.FragmentProxyFilterBinding;
import no.nordicsemi.android.swaromesh.dialog.DialogFragmentError;
import no.nordicsemi.android.swaromesh.dialog.DialogFragmentFilterAddAddress;
import no.nordicsemi.android.swaromesh.transport.MeshMessage;
import no.nordicsemi.android.swaromesh.transport.ProxyConfigAddAddressToFilter;
import no.nordicsemi.android.swaromesh.transport.ProxyConfigRemoveAddressFromFilter;
import no.nordicsemi.android.swaromesh.transport.ProxyConfigSetFilterType;
import no.nordicsemi.android.swaromesh.utils.AddressArray;
import no.nordicsemi.android.swaromesh.utils.MeshAddress;
import no.nordicsemi.android.swaromesh.utils.ProxyFilter;
import no.nordicsemi.android.swaromesh.utils.ProxyFilterType;
import no.nordicsemi.android.swaromesh.viewmodels.SharedViewModel;
import no.nordicsemi.android.swaromesh.widgets.ItemTouchHelperAdapter;
import no.nordicsemi.android.swaromesh.widgets.RemovableItemTouchHelperCallback;
import no.nordicsemi.android.swaromesh.widgets.RemovableViewHolder;

@AndroidEntryPoint
public class ProxyFilterFragment extends Fragment implements
        DialogFragmentFilterAddAddress.DialogFragmentFilterAddressListener,
        ItemTouchHelperAdapter {

    private static final String CLEAR_ADDRESS_PRESSED = "CLEAR_ADDRESS_PRESSED";
    private static final String FILTER_ENABLED = "FILTER_ENABLED";

    private SharedViewModel mViewModel;

    private ProxyFilter mFilter;
    private boolean clearAddressPressed;
    private boolean isFilterEnabled = true;

    private FilterAddressAdapter addressAdapter;

    @Nullable
    @Override
    public View onCreateView(@NonNull final LayoutInflater inflater,
                             @Nullable final ViewGroup container,
                             @Nullable final Bundle savedInstanceState) {

        mViewModel = new ViewModelProvider(requireActivity()).get(SharedViewModel.class);
        final FragmentProxyFilterBinding binding =
                FragmentProxyFilterBinding.inflate(getLayoutInflater());

        // UI
        final SwitchMaterial switchEnableFilter = binding.switchEnableFilter;
        final MaterialButton actionAddFilterAddress = binding.actionAddAddress;
        final MaterialButton actionClearFilterAddress = binding.actionClearAddresses;

        final MaterialTextView noAddressesAdded = binding.noAddresses;
        final RecyclerView recyclerViewAddresses = binding.recyclerViewFilterAddresses;

        if (savedInstanceState != null) {
            clearAddressPressed = savedInstanceState.getBoolean(CLEAR_ADDRESS_PRESSED, false);
            isFilterEnabled = savedInstanceState.getBoolean(FILTER_ENABLED, true);
        } else {
            //  sync from ViewModel
            isFilterEnabled = mViewModel.isProxyEnabled();
        }

        // Recycler
        recyclerViewAddresses.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerViewAddresses.setItemAnimator(new DefaultItemAnimator());

        final ItemTouchHelper.Callback itemTouchHelperCallback =
                new RemovableItemTouchHelperCallback(this);
        final ItemTouchHelper itemTouchHelper = new ItemTouchHelper(itemTouchHelperCallback);
        itemTouchHelper.attachToRecyclerView(recyclerViewAddresses);

        addressAdapter = new FilterAddressAdapter();
        recyclerViewAddresses.setAdapter(addressAdapter);

        // Set initial switch state
        switchEnableFilter.setChecked(isFilterEnabled);

        // Switch Toggle
        switchEnableFilter.setOnCheckedChangeListener((buttonView, isChecked) -> {
            isFilterEnabled = isChecked;

            //  this will control your auto-proxy-connect logic in NetworkFragment
            mViewModel.setProxyEnabled(isChecked);

            if (!isChecked) {
                // Disable UI
                actionAddFilterAddress.setEnabled(false);
                actionClearFilterAddress.setVisibility(View.GONE);
                recyclerViewAddresses.setVisibility(View.GONE);
                noAddressesAdded.setVisibility(View.VISIBLE);
            } else {
                // Enable filter again
                actionAddFilterAddress.setEnabled(true);

                // Set filter type again (default inclusion)
                setFilter(new ProxyFilterType(ProxyFilterType.INCLUSION_LIST_FILTER));
            }
        });

        // Observe Proxy Connection
        mViewModel.isConnectedToProxy().observe(getViewLifecycleOwner(), isConnected -> {
            if (!isConnected) {
                clearAddressPressed = false;

                final MeshNetwork network = mViewModel.getNetworkLiveData().getMeshNetwork();
                if (network != null) {
                    mFilter = network.getProxyFilter();
                    if (mFilter == null) {
                        addressAdapter.clearData();
                        noAddressesAdded.setVisibility(View.VISIBLE);
                        recyclerViewAddresses.setVisibility(View.GONE);
                    }
                }

                actionAddFilterAddress.setEnabled(false);
                actionClearFilterAddress.setVisibility(View.GONE);
                return;
            }

            // connected
            actionAddFilterAddress.setEnabled(isFilterEnabled);
        });

        // Observe Network Data
        mViewModel.getNetworkLiveData().observe(getViewLifecycleOwner(), meshNetworkLiveData -> {
            final MeshNetwork network = meshNetworkLiveData.getMeshNetwork();
            if (network == null) return;

            final ProxyFilter filter = mFilter = network.getProxyFilter();
            if (filter == null) {
                addressAdapter.clearData();
                return;
            }

            if (clearAddressPressed) {
                clearAddressPressed = false;
                return;
            }

            if (!isFilterEnabled) {
                recyclerViewAddresses.setVisibility(View.GONE);
                noAddressesAdded.setVisibility(View.VISIBLE);
                actionClearFilterAddress.setVisibility(View.GONE);
                return;
            }

            // Show addresses
            if (!filter.getAddresses().isEmpty()) {
                noAddressesAdded.setVisibility(View.GONE);
                recyclerViewAddresses.setVisibility(View.VISIBLE);
                actionClearFilterAddress.setVisibility(View.VISIBLE);
            } else {
                recyclerViewAddresses.setVisibility(View.GONE);
                noAddressesAdded.setVisibility(View.VISIBLE);
                actionClearFilterAddress.setVisibility(View.GONE);
            }

            actionAddFilterAddress.setEnabled(true);
            addressAdapter.updateData(filter);
        });

        // Add Address
        actionAddFilterAddress.setOnClickListener(v -> {
            final ProxyFilterType filterType;
            if (mFilter == null) {
                filterType = new ProxyFilterType(ProxyFilterType.INCLUSION_LIST_FILTER);
            } else {
                filterType = mFilter.getFilterType();
            }

            final DialogFragmentFilterAddAddress filterAddAddress =
                    DialogFragmentFilterAddAddress.newInstance(filterType);

            filterAddAddress.show(getChildFragmentManager(), null);
        });

        // Clear Addresses
        actionClearFilterAddress.setOnClickListener(v -> removeAddresses());

        return binding.getRoot();
    }

    @Override
    public void onSaveInstanceState(@NonNull final Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(CLEAR_ADDRESS_PRESSED, clearAddressPressed);
        outState.putBoolean(FILTER_ENABLED, isFilterEnabled);
    }

    @Override
    public void addAddresses(final List<AddressArray> addresses) {
        final ProxyConfigAddAddressToFilter addAddressToFilter =
                new ProxyConfigAddAddressToFilter(addresses);
        sendMessage(addAddressToFilter);
    }

    @Override
    public void onItemDismiss(final RemovableViewHolder viewHolder) {
        final int position = viewHolder.getAbsoluteAdapterPosition();
        if (viewHolder instanceof FilterAddressAdapter.ViewHolder) {
            removeAddress(position);
        }
    }

    @Override
    public void onItemDismissFailed(final RemovableViewHolder viewHolder) {
        // ignore
    }

    private void removeAddress(final int position) {
        final MeshNetwork meshNetwork = mViewModel.getNetworkLiveData().getMeshNetwork();
        if (meshNetwork != null) {
            final ProxyFilter proxyFilter = meshNetwork.getProxyFilter();
            if (proxyFilter != null) {
                clearAddressPressed = true;

                final AddressArray addressArr = proxyFilter.getAddresses().get(position);
                final List<AddressArray> addresses = new ArrayList<>();
                addresses.add(addressArr);

                addressAdapter.clearRow(proxyFilter, position);

                final ProxyConfigRemoveAddressFromFilter removeAddressFromFilter =
                        new ProxyConfigRemoveAddressFromFilter(addresses);

                sendMessage(removeAddressFromFilter);
            }
        }
    }

    private void removeAddresses() {
        final MeshNetwork meshNetwork = mViewModel.getNetworkLiveData().getMeshNetwork();
        if (meshNetwork != null) {
            final ProxyFilter proxyFilter = meshNetwork.getProxyFilter();
            if (proxyFilter != null && !proxyFilter.getAddresses().isEmpty()) {
                final ProxyConfigRemoveAddressFromFilter removeAddressFromFilter =
                        new ProxyConfigRemoveAddressFromFilter(proxyFilter.getAddresses());

                sendMessage(removeAddressFromFilter);
            }
        }
    }

    private void setFilter(final ProxyFilterType filterType) {
        final ProxyConfigSetFilterType setFilterType = new ProxyConfigSetFilterType(filterType);
        sendMessage(setFilterType);
    }

    private void sendMessage(final MeshMessage meshMessage) {
        try {
            mViewModel.getMeshManagerApi().createMeshPdu(MeshAddress.UNASSIGNED_ADDRESS, meshMessage);
        } catch (IllegalArgumentException ex) {
            final DialogFragmentError message = DialogFragmentError.newInstance(
                    getString(R.string.title_error),
                    ex.getMessage() == null ? getString(R.string.unknwon_error) : ex.getMessage()
            );
            message.show(getChildFragmentManager(), null);
        }
    }
}
