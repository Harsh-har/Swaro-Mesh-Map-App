package no.nordicsemi.android.swaromesh.keys;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.android.material.snackbar.Snackbar;
import java.util.List;
import dagger.hilt.android.AndroidEntryPoint;
import no.nordicsemi.android.swaromesh.ApplicationKey;
import no.nordicsemi.android.swaromesh.NetworkKey;
import no.nordicsemi.android.swaromesh.NodeKey;
import no.nordicsemi.android.swaromesh.R;
import no.nordicsemi.android.swaromesh.keys.adapter.AddedAppKeyAdapter;
import no.nordicsemi.android.swaromesh.transport.ConfigAppKeyAdd;
import no.nordicsemi.android.swaromesh.transport.ConfigAppKeyDelete;
import no.nordicsemi.android.swaromesh.transport.ConfigAppKeyGet;
import no.nordicsemi.android.swaromesh.transport.ConfigAppKeyStatus;
import no.nordicsemi.android.swaromesh.transport.MeshMessage;
import no.nordicsemi.android.swaromesh.transport.ProvisionedMeshNode;
import no.nordicsemi.android.swaromesh.utils.Utils;
import no.nordicsemi.android.swaromesh.viewmodels.AddKeysViewModel;

@AndroidEntryPoint
public class AddAppKeysActivity extends AddKeysActivity implements
        AddedAppKeyAdapter.OnItemClickListener {

    private AddedAppKeyAdapter adapter;

    // ✅ Auto flow flags
    private boolean autoStarted = false;
    private boolean finishScheduled = false;

    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(@Nullable final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (getSupportActionBar() != null)
            getSupportActionBar().setTitle(R.string.title_added_app_keys);

        adapter = new AddedAppKeyAdapter(
                this,
                mViewModel.getNetworkLiveData().getMeshNetwork().getAppKeys(),
                mViewModel.getSelectedMeshNode()
        );

        binding.recyclerViewKeys.setAdapter(adapter);
        adapter.setOnItemClickListener(this);

        updateClickableViews();
        setUpObserver();
    }

    @Override
    protected void onResume() {
        super.onResume();

        // ✅ Auto start only once
        if (autoStarted) return;
        autoStarted = true;

        handler.postDelayed(() -> {
            final ProvisionedMeshNode node = mViewModel.getSelectedMeshNode().getValue();
            if (node == null) return;

            final int unicast = node.getUnicastAddress();

            // ✅ Already done for this device => don't auto bind again
            if (Utils.isAutoAppKeyDone(this, unicast)) {
                return;
            }

            // ✅ Start auto add only if connected
            if (!checkConnectivity(binding.container)) {
                // ❗If not connected, user can still manually do later when connected
                return;
            }

            startAutoAddMissingAppKeys();
        }, 200);
    }

    /**
     * ✅ AUTO: add all missing appkeys one by one
     */
    private void startAutoAddMissingAppKeys() {
        if (!checkConnectivity(binding.container))
            return;

        final List<ApplicationKey> appKeys =
                mViewModel.getNetworkLiveData().getMeshNetwork().getAppKeys();

        if (appKeys == null || appKeys.isEmpty()) {
            markAutoDoneAndFinish();
            return;
        }

        // Find first missing key and add it
        for (ApplicationKey appKey : appKeys) {

            if (!((AddKeysViewModel) mViewModel).isAppKeyAdded(appKey.getKeyIndex())) {

                final NetworkKey networkKey =
                        mViewModel.getNetworkLiveData().getMeshNetwork()
                                .getNetKey(appKey.getBoundNetKeyIndex());

                final MeshMessage meshMessage = new ConfigAppKeyAdd(networkKey, appKey);

                mViewModel.displaySnackBar(
                        this,
                        binding.container,
                        getString(R.string.adding_app_key),
                        Snackbar.LENGTH_SHORT
                );

                sendMessage(meshMessage);
                return; // ✅ send one at a time
            }
        }

        // ✅ No missing keys left => finish
        markAutoDoneAndFinish();
    }

    /**
     * ✅ Manual click works always (like NetKey screen)
     */
    @Override
    public void onItemClick(@NonNull final ApplicationKey appKey) {
        if (!checkConnectivity(binding.container))
            return;

        final MeshMessage meshMessage;
        final String message;

        final NetworkKey networkKey =
                mViewModel.getNetworkLiveData().getMeshNetwork()
                        .getNetKey(appKey.getBoundNetKeyIndex());

        if (!((AddKeysViewModel) mViewModel).isAppKeyAdded(appKey.getKeyIndex())) {
            message = getString(R.string.adding_app_key);
            meshMessage = new ConfigAppKeyAdd(networkKey, appKey);
        } else {
            message = getString(R.string.deleting_app_key);
            meshMessage = new ConfigAppKeyDelete(networkKey, appKey);
        }

        mViewModel.displaySnackBar(this, binding.container, message, Snackbar.LENGTH_SHORT);
        sendMessage(meshMessage);
    }

    /**
     * Manual refresh like original code
     */
    @Override
    public void onRefresh() {
        super.onRefresh();

        final ProvisionedMeshNode node = mViewModel.getSelectedMeshNode().getValue();
        if (node != null) {
            for (NodeKey key : node.getAddedNetKeys()) {
                final NetworkKey networkKey =
                        mViewModel.getNetworkLiveData().getMeshNetwork().getNetKey(key.getIndex());

                final ConfigAppKeyGet configAppKeyGet = new ConfigAppKeyGet(networkKey);
                mViewModel.getMessageQueue().add(configAppKeyGet);
            }
            sendMessage(mViewModel.getMessageQueue().peek());
        }
    }

    /**
     * ✅ When response comes, continue auto process
     */
    @Override
    protected void updateMeshMessage(final MeshMessage meshMessage) {
        super.updateMeshMessage(meshMessage);

        // Auto continue only if auto was started
        if (!autoStarted) return;

        if (meshMessage instanceof ConfigAppKeyStatus) {
            handler.postDelayed(this::startAutoAddMissingAppKeys, 150);
        }
    }

    /**
     * UI observer
     */
    protected void setUpObserver() {
        mViewModel.getNetworkLiveData().observe(this, networkLiveData -> {
            if (networkLiveData != null) {
                final List<ApplicationKey> keys = networkLiveData.getAppKeys();
                if (keys != null) {
                    binding.emptyAppKeys.getRoot()
                            .setVisibility(keys.isEmpty() ? View.VISIBLE : View.GONE);
                }
            }
        });
    }

    /**
     * ✅ Mark done only once per device + finish screen
     */
    private void markAutoDoneAndFinish() {
        if (finishScheduled) return;
        finishScheduled = true;

        final ProvisionedMeshNode node = mViewModel.getSelectedMeshNode().getValue();
        if (node != null) {
            Utils.setAutoAppKeyDone(this, node.getUnicastAddress(), true);
        }

        // back to NodeConfigurationActivity
        handler.postDelayed(this::finish, 200);
    }

    @Override
    void enableAdapterClickListener(final boolean enable) {
        // ✅ Manual selection enable/disable same like NetKey screen
        adapter.enableDisableKeySelection(enable);
    }
}
