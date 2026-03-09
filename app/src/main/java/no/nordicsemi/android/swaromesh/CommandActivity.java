package no.nordicsemi.android.swaromesh;

import android.os.Bundle;
import android.util.Log;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import dagger.hilt.android.AndroidEntryPoint;
import no.nordicsemi.android.swaromesh.databinding.ActivityCommandBinding;
import no.nordicsemi.android.swaromesh.dialog.DialogFragmentError;
import no.nordicsemi.android.swaromesh.transport.GenericOnOffSet;
import no.nordicsemi.android.swaromesh.transport.MeshMessage;
import no.nordicsemi.android.swaromesh.viewmodels.BaseActivity;
import no.nordicsemi.android.swaromesh.viewmodels.SharedViewModel;

@AndroidEntryPoint
public class CommandActivity extends BaseActivity {

    private static final String TAG       = "CommandActivity";
    private static final String TAG_ONOFF = "ONOFF_CMD";
    private static final String TAG_TID   = "TID";
    private static final int    MAX_TID   = 255;

    // ✅ Auto-hide progress bar after this delay (ms) if no response comes
    private static final int PROGRESS_AUTO_HIDE_MS = 3000;

    // Hardcoded destination element address
    private static final int HARDCODED_ELEMENT_ADDRESS = 0x003E;

    private final AtomicInteger genericOnOffTidCounter = new AtomicInteger(0);

    private ActivityCommandBinding binding;
    private CoordinatorLayout      mContainer;
    private TextInputEditText      mCommandEditText;
    private TextInputEditText      mStateEditText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityCommandBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        Log.d(TAG, "onCreate ▶ start");

        mViewModel = new ViewModelProvider(this).get(SharedViewModel.class);
        initialize(); // sets mHandler, mIsConnected observer, mesh message observer

        setSupportActionBar(binding.toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Send Command");
            getSupportActionBar().setSubtitle(
                    String.format("→ Element 0x%04X", HARDCODED_ELEMENT_ADDRESS));
        }

        mContainer       = binding.container;
        mCommandEditText = binding.etCommand;
        mStateEditText   = binding.etState;

        binding.actionOn.setOnClickListener(v -> sendGenericOnOffCommand());

        Log.d(TAG, "onCreate ▶ done");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Send Short Command to hardcoded element address 0x003E
    // ─────────────────────────────────────────────────────────────────────────
    private void sendGenericOnOffCommand() {
        final String cs = mCommandEditText.getText() != null
                ? mCommandEditText.getText().toString().trim() : "";
        final String ss = mStateEditText.getText() != null
                ? mStateEditText.getText().toString().trim() : "";

        if (cs.isEmpty() || ss.isEmpty()) {
            showSnackbar("Enter command and state");
            return;
        }

        try {
            int cmd   = Integer.parseInt(cs);
            int state = Integer.parseInt(ss);

            if (cmd < 0 || cmd > 255)    { showSnackbar("Command must be 0–255"); return; }
            if (state < 0 || state > 255) { showSnackbar("State must be 0–255");  return; }

            List<ApplicationKey> appKeys = mViewModel.getNetworkLiveData().getAppKeys();
            if (appKeys == null || appKeys.isEmpty()) {
                showSnackbar("No AppKey found in network");
                return;
            }
            ApplicationKey appKey = appKeys.get(0);

            int tid = getNextTid();

            Log.d(TAG_ONOFF, "══════════════════════════════════════");
            Log.d(TAG_ONOFF, "📤 SHORT COMMAND");
            Log.d(TAG_ONOFF, String.format("   Destination : 0x%04X (%d)",
                    HARDCODED_ELEMENT_ADDRESS, HARDCODED_ELEMENT_ADDRESS));
            Log.d(TAG_ONOFF, String.format("   CMD=0x%02X  STATE=0x%02X  TID=%d",
                    cmd, state, tid));
            Log.d(TAG_ONOFF, "══════════════════════════════════════");

            showSnackbar(String.format("Sending → 0x%04X  CMD=0x%02X STATE=0x%02X TID=%d",
                    HARDCODED_ELEMENT_ADDRESS, cmd, state, tid));

            sendAcknowledgedMessage(HARDCODED_ELEMENT_ADDRESS,
                    new GenericOnOffSet(appKey, cmd, state, tid));

        } catch (NumberFormatException e) {
            showSnackbar("Invalid number");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Mesh send helper
    // ─────────────────────────────────────────────────────────────────────────
    protected void sendAcknowledgedMessage(int address, @NonNull MeshMessage msg) {
        try {
            if (!checkConnectivity(mContainer)) return;
            mViewModel.getMeshManagerApi().createMeshPdu(address, msg);
            showProgressBar();
        } catch (IllegalArgumentException ex) {
            hideProgressBar();
            DialogFragmentError.newInstance(
                    getString(R.string.title_error),
                    ex.getMessage() == null
                            ? getString(R.string.unknwon_error)
                            : ex.getMessage()
            ).show(getSupportFragmentManager(), null);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────
    private void showSnackbar(String msg) {
        mViewModel.displaySnackBar(this, mContainer, msg, Snackbar.LENGTH_SHORT);
    }

    private int getNextTid() {
        int c = genericOnOffTidCounter.getAndIncrement();
        if (c > MAX_TID) { genericOnOffTidCounter.set(0); c = 0; }
        Log.d(TAG_TID, "TID=" + c);
        return c;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BaseActivity abstract overrides
    // ─────────────────────────────────────────────────────────────────────────
    @Override
    protected void updateClickableViews() {
        binding.actionOn.setEnabled(mIsConnected);
    }

    @Override
    protected void showProgressBar() {
        // Cancel any pending auto-hide before starting a new one
        mHandler.removeCallbacks(mAutoHideProgress);

        binding.configurationProgressBar.setVisibility(View.VISIBLE);
        binding.actionOn.setEnabled(false);

        // ✅ FIX: GenericOnOffSet is unacknowledged — no mesh response comes back,
        //    so updateMeshMessage() never fires → progress bar stays forever.
        //    Auto-hide after PROGRESS_AUTO_HIDE_MS so button re-enables itself.
        mHandler.postDelayed(mAutoHideProgress, PROGRESS_AUTO_HIDE_MS);
    }

    @Override
    protected void hideProgressBar() {
        // Cancel auto-hide if we're hiding manually (e.g. mesh response arrived)
        mHandler.removeCallbacks(mAutoHideProgress);

        binding.configurationProgressBar.setVisibility(View.INVISIBLE);
        binding.actionOn.setEnabled(mIsConnected);
    }

    // Runnable that hides progress bar after timeout
    private final Runnable mAutoHideProgress = () -> {
        Log.d(TAG, "Auto-hiding progress bar after " + PROGRESS_AUTO_HIDE_MS + "ms");
        binding.configurationProgressBar.setVisibility(View.INVISIBLE);
        binding.actionOn.setEnabled(mIsConnected);
    };

    @Override protected void enableClickableViews()  { binding.actionOn.setEnabled(true);  }
    @Override protected void disableClickableViews() { binding.actionOn.setEnabled(false); }

    // Called when mesh message response arrives (acknowledged messages)
    @Override
    protected void updateMeshMessage(MeshMessage meshMessage) {
        Log.d(TAG, "updateMeshMessage ▶ response received, hiding progress bar");
        hideProgressBar();
    }
}