package no.nordicsemi.android.swaromapmesh;

import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import com.google.android.material.switchmaterial.SwitchMaterial;
import java.util.ArrayList;
import java.util.List;
import dagger.hilt.android.AndroidEntryPoint;
import no.nordicsemi.android.swaromapmesh.adapter.FilterAddressAdapter;
import no.nordicsemi.android.swaromapmesh.databinding.ActivityProxyFilterBinding;
import no.nordicsemi.android.swaromapmesh.dialog.DialogFragmentError;
import no.nordicsemi.android.swaromapmesh.dialog.DialogFragmentFilterAddAddress;
import no.nordicsemi.android.swaromapmesh.transport.MeshMessage;
import no.nordicsemi.android.swaromapmesh.transport.ProxyConfigAddAddressToFilter;
import no.nordicsemi.android.swaromapmesh.transport.ProxyConfigRemoveAddressFromFilter;
import no.nordicsemi.android.swaromapmesh.transport.ProxyConfigSetFilterType;
import no.nordicsemi.android.swaromapmesh.utils.AddressArray;
import no.nordicsemi.android.swaromapmesh.utils.MeshAddress;
import no.nordicsemi.android.swaromapmesh.utils.ProxyFilter;
import no.nordicsemi.android.swaromapmesh.utils.ProxyFilterType;
import no.nordicsemi.android.swaromapmesh.viewmodels.SharedViewModel;
import no.nordicsemi.android.swaromapmesh.widgets.ItemTouchHelperAdapter;
import no.nordicsemi.android.swaromapmesh.widgets.RemovableItemTouchHelperCallback;
import no.nordicsemi.android.swaromapmesh.widgets.RemovableViewHolder;

@AndroidEntryPoint
public class ProxyFilterActivity extends AppCompatActivity implements
        DialogFragmentFilterAddAddress.DialogFragmentFilterAddressListener,
        ItemTouchHelperAdapter {

    private static final String CLEAR_ADDRESS_PRESSED = "CLEAR_ADDRESS_PRESSED";
    private static final String FILTER_ENABLED = "FILTER_ENABLED";

    private SharedViewModel mViewModel;
    private ActivityProxyFilterBinding binding;

    private ProxyFilter mFilter;
    private boolean clearAddressPressed;
    private boolean isFilterEnabled = true;

    private FilterAddressAdapter addressAdapter;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityProxyFilterBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        mViewModel = new ViewModelProvider(this).get(SharedViewModel.class);

        setSupportActionBar(binding.toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Proxy Filter");
        }

        setupUi();

        if (savedInstanceState != null) {
            clearAddressPressed = savedInstanceState.getBoolean(CLEAR_ADDRESS_PRESSED, false);
            isFilterEnabled = savedInstanceState.getBoolean(FILTER_ENABLED, true);
        } else {
            isFilterEnabled = mViewModel.isProxyEnabled();
        }

        initLogic();
    }

    private void setupUi() {
        // Setup Header
        binding.header.image.setBackground(ContextCompat.getDrawable(this, R.drawable.ic_proxy));
        binding.header.title.setText(R.string.title_filter_control);
        binding.header.text.setVisibility(View.VISIBLE);
        binding.header.text.setText(R.string.subtitle_filter_control);
        binding.header.actionChangeTestMode.setVisibility(View.VISIBLE);

        binding.header.getRoot().setOnClickListener(v -> binding.header.actionChangeTestMode.toggle());

        // Recycler
        binding.recyclerViewFilterAddresses.setLayoutManager(new LinearLayoutManager(this));
        binding.recyclerViewFilterAddresses.setItemAnimator(new DefaultItemAnimator());

        final ItemTouchHelper.Callback itemTouchHelperCallback = new RemovableItemTouchHelperCallback(this);
        final ItemTouchHelper itemTouchHelper = new ItemTouchHelper(itemTouchHelperCallback);
        itemTouchHelper.attachToRecyclerView(binding.recyclerViewFilterAddresses);

        addressAdapter = new FilterAddressAdapter();
        binding.recyclerViewFilterAddresses.setAdapter(addressAdapter);

        // Add Address
        binding.actionAddAddress.setOnClickListener(v -> {
            final ProxyFilterType filterType;
            if (mFilter == null) {
                filterType = new ProxyFilterType(ProxyFilterType.INCLUSION_LIST_FILTER);
            } else {
                filterType = mFilter.getFilterType();
            }
            final DialogFragmentFilterAddAddress filterAddAddress = DialogFragmentFilterAddAddress.newInstance(filterType);
            filterAddAddress.show(getSupportFragmentManager(), null);
        });

        // Clear Addresses
        binding.actionClearAddresses.setOnClickListener(v -> removeAddresses());
    }

    private void initLogic() {
        final SwitchMaterial switchEnableFilter = binding.header.actionChangeTestMode;
        switchEnableFilter.setChecked(isFilterEnabled);
        binding.proxyFilterAddressCard.setVisibility(isFilterEnabled ? View.VISIBLE : View.GONE);

        switchEnableFilter.setOnCheckedChangeListener((buttonView, isChecked) -> {
            isFilterEnabled = isChecked;
            mViewModel.setProxyEnabled(isChecked);
            binding.proxyFilterAddressCard.setVisibility(isChecked ? View.VISIBLE : View.GONE);

            if (!isChecked) {
                binding.actionAddAddress.setEnabled(false);
                binding.actionClearAddresses.setVisibility(View.GONE);
                binding.recyclerViewFilterAddresses.setVisibility(View.GONE);
                binding.noAddresses.setVisibility(View.VISIBLE);
            } else {
                binding.actionAddAddress.setEnabled(true);
                setFilter(new ProxyFilterType(ProxyFilterType.INCLUSION_LIST_FILTER));
            }
        });

        // Observe Proxy Connection
        mViewModel.isConnectedToProxy().observe(this, isConnected -> {
            if (!isConnected) {
                clearAddressPressed = false;
                final MeshNetwork network = mViewModel.getNetworkLiveData().getMeshNetwork();
                if (network != null) {
                    mFilter = network.getProxyFilter();
                    if (mFilter == null) {
                        addressAdapter.clearData();
                        binding.noAddresses.setVisibility(View.VISIBLE);
                        binding.recyclerViewFilterAddresses.setVisibility(View.GONE);
                    }
                }
                binding.actionAddAddress.setEnabled(false);
                binding.actionClearAddresses.setVisibility(View.GONE);
                return;
            }
            binding.actionAddAddress.setEnabled(isFilterEnabled);
        });

        // Observe Network Data
        mViewModel.getNetworkLiveData().observe(this, meshNetworkLiveData -> {
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
                binding.recyclerViewFilterAddresses.setVisibility(View.GONE);
                binding.noAddresses.setVisibility(View.VISIBLE);
                binding.actionClearAddresses.setVisibility(View.GONE);
                return;
            }

            if (!filter.getAddresses().isEmpty()) {
                binding.noAddresses.setVisibility(View.GONE);
                binding.recyclerViewFilterAddresses.setVisibility(View.VISIBLE);
                binding.actionClearAddresses.setVisibility(View.VISIBLE);
            } else {
                binding.recyclerViewFilterAddresses.setVisibility(View.GONE);
                binding.noAddresses.setVisibility(View.VISIBLE);
                binding.actionClearAddresses.setVisibility(View.GONE);
            }

            binding.actionAddAddress.setEnabled(true);
            addressAdapter.updateData(filter);
        });
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(CLEAR_ADDRESS_PRESSED, clearAddressPressed);
        outState.putBoolean(FILTER_ENABLED, isFilterEnabled);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void addAddresses(final List<AddressArray> addresses) {
        final ProxyConfigAddAddressToFilter addAddressToFilter = new ProxyConfigAddAddressToFilter(addresses);
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
                final ProxyConfigRemoveAddressFromFilter removeAddressFromFilter = new ProxyConfigRemoveAddressFromFilter(addresses);
                sendMessage(removeAddressFromFilter);
            }
        }
    }

    private void removeAddresses() {
        final MeshNetwork meshNetwork = mViewModel.getNetworkLiveData().getMeshNetwork();
        if (meshNetwork != null) {
            final ProxyFilter proxyFilter = meshNetwork.getProxyFilter();
            if (proxyFilter != null && !proxyFilter.getAddresses().isEmpty()) {
                final ProxyConfigRemoveAddressFromFilter removeAddressFromFilter = new ProxyConfigRemoveAddressFromFilter(proxyFilter.getAddresses());
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
            message.show(getSupportFragmentManager(), null);
        }
    }
}
