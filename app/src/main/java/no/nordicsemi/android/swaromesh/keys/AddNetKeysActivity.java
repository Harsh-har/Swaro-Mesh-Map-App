
package no.nordicsemi.android.swaromesh.keys;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.snackbar.Snackbar;

import dagger.hilt.android.AndroidEntryPoint;
import no.nordicsemi.android.swaromesh.NetworkKey;
import no.nordicsemi.android.swaromesh.transport.ConfigNetKeyAdd;
import no.nordicsemi.android.swaromesh.transport.ConfigNetKeyDelete;
import no.nordicsemi.android.swaromesh.transport.ConfigNetKeyGet;
import no.nordicsemi.android.swaromesh.transport.MeshMessage;
import no.nordicsemi.android.swaromesh.R;
import no.nordicsemi.android.swaromesh.keys.adapter.AddedNetKeyAdapter;
import no.nordicsemi.android.swaromesh.viewmodels.AddKeysViewModel;

@AndroidEntryPoint
public class AddNetKeysActivity extends AddKeysActivity implements
        AddedNetKeyAdapter.OnItemClickListener {
    private AddedNetKeyAdapter adapter;

    @Override
    protected void onCreate(@Nullable final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getSupportActionBar() != null)
            getSupportActionBar().setTitle(R.string.title_added_net_keys);
        adapter = new AddedNetKeyAdapter(this,
                mViewModel.getNetworkLiveData().getMeshNetwork().getNetKeys(), mViewModel.getSelectedMeshNode());
        binding.recyclerViewKeys.setAdapter(adapter);
        adapter.setOnItemClickListener(this);
        updateClickableViews();
    }

    @Override
    public void onItemClick(@NonNull final NetworkKey networkKey) {
        if (!checkConnectivity(binding.container))
            return;
        final MeshMessage meshMessage;
        final String message;
        if (!((AddKeysViewModel) mViewModel).isNetKeyAdded(networkKey.getKeyIndex())) {
            meshMessage = new ConfigNetKeyAdd(networkKey);
            message = getString(R.string.adding_net_key);
        } else {
            meshMessage = new ConfigNetKeyDelete(networkKey);
            message = getString(R.string.deleting_net_key);
        }
        mViewModel.displaySnackBar(this, binding.container, message, Snackbar.LENGTH_SHORT);
        sendMessage(meshMessage);
    }

    @Override
    public void onRefresh() {
        super.onRefresh();
        final ConfigNetKeyGet configNetKeyGet = new ConfigNetKeyGet();
        sendMessage(configNetKeyGet);
    }

    @Override
    void enableAdapterClickListener(final boolean enable) {
        adapter.enableDisableKeySelection(enable);
    }
}
