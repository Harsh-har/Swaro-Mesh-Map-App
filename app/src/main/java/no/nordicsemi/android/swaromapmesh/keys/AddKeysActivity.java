

package no.nordicsemi.android.swaromapmesh.keys;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.snackbar.Snackbar;

import no.nordicsemi.android.swaromapmesh.transport.ConfigAppKeyList;
import no.nordicsemi.android.swaromapmesh.transport.ConfigAppKeyStatus;
import no.nordicsemi.android.swaromapmesh.transport.ConfigNetKeyStatus;
import no.nordicsemi.android.swaromapmesh.transport.MeshMessage;
import no.nordicsemi.android.swaromapmesh.transport.ProvisionedMeshNode;
import no.nordicsemi.android.swaromapmesh.R;
import no.nordicsemi.android.swaromapmesh.databinding.ActivityAddKeysBinding;
import no.nordicsemi.android.swaromapmesh.dialog.DialogFragmentConfigStatus;
import no.nordicsemi.android.swaromapmesh.dialog.DialogFragmentError;
import no.nordicsemi.android.swaromapmesh.utils.Utils;
import no.nordicsemi.android.swaromapmesh.viewmodels.AddKeysViewModel;
import no.nordicsemi.android.swaromapmesh.viewmodels.BaseActivity;

public abstract class AddKeysActivity extends BaseActivity implements SwipeRefreshLayout.OnRefreshListener {

    protected ActivityAddKeysBinding binding;
    abstract void enableAdapterClickListener(final boolean enable);

    @Override
    protected void updateClickableViews() {
        if (mIsConnected) {
            enableClickableViews();
        } else {
            disableClickableViews();
        }
    }

    @Override
    protected void onCreate(@Nullable final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityAddKeysBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        mViewModel = new ViewModelProvider(this).get(AddKeysViewModel.class);
        initialize();
        mHandler = new Handler(Looper.getMainLooper());
        setSupportActionBar(binding.toolbar);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        binding.swipeRefresh.setOnRefreshListener(this);
        binding.recyclerViewKeys.setLayoutManager(new LinearLayoutManager(this));
        final DividerItemDecoration dividerItemDecoration =
                new DividerItemDecoration(binding.recyclerViewKeys.getContext(), DividerItemDecoration.VERTICAL);
        binding.recyclerViewKeys.addItemDecoration(dividerItemDecoration);
        binding.recyclerViewKeys.setItemAnimator(new DefaultItemAnimator());
      //  binding.fabAdd.hide();

    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
        if (isFinishing()) {
            mHandler.removeCallbacksAndMessages(null);
        }
    }

    private void showDialogFragment(@NonNull final String title, @NonNull final String message) {
        if (getSupportFragmentManager().findFragmentByTag(Utils.DIALOG_FRAGMENT_KEY_STATUS) == null) {
            final DialogFragmentConfigStatus fragmentKeyStatus = DialogFragmentConfigStatus.newInstance(title, message);
            fragmentKeyStatus.show(getSupportFragmentManager(), Utils.DIALOG_FRAGMENT_KEY_STATUS);
        }
    }

    protected void showProgressBar() {
        mHandler.postDelayed(mRunnableOperationTimeout, Utils.MESSAGE_TIME_OUT);
        disableClickableViews();
        binding.configurationProgressBar.setVisibility(View.VISIBLE);
    }

    protected final void hideProgressBar() {
        binding.swipeRefresh.setRefreshing(false);
        enableClickableViews();
        binding.configurationProgressBar.setVisibility(View.INVISIBLE);
        mHandler.removeCallbacks(mRunnableOperationTimeout);
    }

    protected void enableClickableViews() {
        enableAdapterClickListener(true);
        binding.recyclerViewKeys.setEnabled(true);
        binding.recyclerViewKeys.setClickable(true);
    }

    protected void disableClickableViews() {
        enableAdapterClickListener(false);
        binding.recyclerViewKeys.setEnabled(false);
        binding.recyclerViewKeys.setClickable(false);
    }

    private void handleStatuses() {
        final MeshMessage message = mViewModel.getMessageQueue().peek();
        if (message != null) {
            sendMessage(message);
        } else {
            mViewModel.displaySnackBar(this, binding.container, getString(R.string.operation_success), Snackbar.LENGTH_SHORT);
        }
    }

    protected void sendMessage(final MeshMessage meshMessage) {
        try {
            if (!checkConnectivity(binding.container))
                return;
            showProgressBar();
            final ProvisionedMeshNode node = mViewModel.getSelectedMeshNode().getValue();
            if (node != null) {
                mViewModel.getMeshManagerApi().createMeshPdu(node.getUnicastAddress(), meshMessage);
            }
        } catch (IllegalArgumentException ex) {
            hideProgressBar();
            final DialogFragmentError message = DialogFragmentError.
                    newInstance(getString(R.string.title_error), ex.getMessage());
            message.show(getSupportFragmentManager(), null);
        }
    }

    @Override
    protected void updateMeshMessage(final MeshMessage meshMessage) {
        if (meshMessage instanceof ConfigNetKeyStatus) {
            final ConfigNetKeyStatus status = (ConfigNetKeyStatus) meshMessage;
            if (status.isSuccessful()) {
                mViewModel.displaySnackBar(this, binding.container, getString(R.string.operation_success), Snackbar.LENGTH_SHORT);
            } else {
                showDialogFragment(getString(R.string.title_netkey_status), status.getStatusCodeName());
            }
        } else if (meshMessage instanceof ConfigAppKeyStatus) {
            final ConfigAppKeyStatus status = (ConfigAppKeyStatus) meshMessage;
            if (status.isSuccessful()) {
                mViewModel.displaySnackBar(this, binding.container, getString(R.string.operation_success), Snackbar.LENGTH_SHORT);
            } else {
                showDialogFragment(getString(R.string.title_appkey_status), status.getStatusCodeName());
            }
        } else if (meshMessage instanceof ConfigAppKeyList) {
            final ConfigAppKeyList status = (ConfigAppKeyList) meshMessage;
            if (!mViewModel.getMessageQueue().isEmpty())
                mViewModel.getMessageQueue().remove();
            if (status.isSuccessful()) {
                handleStatuses();
            } else {
                showDialogFragment(getString(R.string.title_appkey_status), status.getStatusCodeName());
            }
        }
        hideProgressBar();
    }

    @Override
    public void onRefresh() {
        if (!checkConnectivity(binding.container)) {
            binding.swipeRefresh.setRefreshing(false);
        }
    }
}
