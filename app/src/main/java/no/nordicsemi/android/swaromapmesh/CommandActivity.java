package no.nordicsemi.android.swaromapmesh;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.lifecycle.ViewModelProvider;
import com.google.android.material.snackbar.Snackbar;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import dagger.hilt.android.AndroidEntryPoint;
import no.nordicsemi.android.swaromapmesh.databinding.ActivityCommandBinding;
import no.nordicsemi.android.swaromapmesh.dialog.DialogFragmentError;
import no.nordicsemi.android.swaromapmesh.transport.GenericOnOffSet;
import no.nordicsemi.android.swaromapmesh.transport.MeshMessage;
import no.nordicsemi.android.swaromapmesh.viewmodels.BaseActivity;
import no.nordicsemi.android.swaromapmesh.viewmodels.SharedViewModel;

@AndroidEntryPoint
public class CommandActivity extends BaseActivity {

    private static final String TAG       = "CommandActivity";
    private static final String TAG_ONOFF = "ONOFF_CMD";
    private static final String TAG_TID   = "TID";
    private static final int    MAX_TID   = 255;

    private static final int PROGRESS_AUTO_HIDE_MS     = 3000;
    private static final int HARDCODED_ELEMENT_ADDRESS = 0x0149;
    private static final int HARDCODED_COMMAND         = 2;

    private final AtomicInteger genericOnOffTidCounter = new AtomicInteger(0);

    private ActivityCommandBinding binding;
    private CoordinatorLayout      mContainer;

    // 0–255 current brightness value
    private int currentDataValue = 153; // default ~60%

    // Power state
    private boolean isPoweredOn = true;

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityCommandBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        mViewModel = new ViewModelProvider(this).get(SharedViewModel.class);
        initialize();

        mContainer = binding.container;

        // Draw initial fill at default value (153/255 ≈ 60%)
        binding.brightnessRow.post(() -> updateBrightnessBar(currentDataValue));

        // ── Brightness bar acts as the slider ────────────────────────────────
        binding.brightnessRow.setOnTouchListener((v, event) -> {
            if (!isPoweredOn) return true; // ignore touch when off

            int action = event.getAction();
            if (action == MotionEvent.ACTION_DOWN
                    || action == MotionEvent.ACTION_MOVE) {

                float x        = Math.max(0, Math.min(event.getX(), v.getWidth()));
                float fraction = x / v.getWidth();                    // 0.0 – 1.0
                int   newValue = Math.round(fraction * 255);          // 0 – 255

                if (newValue != currentDataValue) {
                    currentDataValue = newValue;
                    updateBrightnessBar(currentDataValue);

                    if (mIsConnected) {
                        sendGenericOnOffCommand(currentDataValue);
                    } else {
                        showSnackbar("Not connected");
                    }
                }
                return true;
            }
            return false;
        });

        // ── Power button toggle ───────────────────────────────────────────────
        binding.btnPower.setOnClickListener(v -> {
            isPoweredOn = !isPoweredOn;

            if (isPoweredOn) {
                binding.viewBrightnessFill.setVisibility(View.VISIBLE);
                binding.btnPower.setIconTint(
                        android.content.res.ColorStateList.valueOf(
                                android.graphics.Color.parseColor("#F5A623")));
                sendGenericOnOffCommand(currentDataValue);
            } else {
                binding.viewBrightnessFill.setVisibility(View.INVISIBLE);
                binding.tvSliderValue.setText("0%");
                binding.btnPower.setIconTint(
                        android.content.res.ColorStateList.valueOf(
                                android.graphics.Color.parseColor("#AAAAAA")));
                sendGenericOnOffCommand(0);
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Update amber fill width + percentage label
    // ─────────────────────────────────────────────────────────────────────────
    private void updateBrightnessBar(int value) {
        float fraction = value / 255f;
        int   pct      = Math.round(fraction * 100);

        binding.tvSliderValue.setText(pct + "%");

        // Set fill view width as fraction of parent width
        int parentWidth = binding.brightnessRow.getWidth();
        int fillWidth   = Math.round(fraction * parentWidth);

        ViewGroup.LayoutParams lp = binding.viewBrightnessFill.getLayoutParams();
        lp.width = fillWidth;
        binding.viewBrightnessFill.setLayoutParams(lp);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Send mesh command
    // ─────────────────────────────────────────────────────────────────────────
    private void sendGenericOnOffCommand(int dataValue) {
        try {
            List<ApplicationKey> appKeys = mViewModel.getNetworkLiveData().getAppKeys();
            if (appKeys == null || appKeys.isEmpty()) {
                showSnackbar("No AppKey found");
                return;
            }
            ApplicationKey appKey = appKeys.get(0);
            int tid = getNextTid();

            Log.d(TAG_ONOFF, String.format(
                    "📤 CMD=0x%02X  DATA=0x%02X  TID=%d  → 0x%04X",
                    HARDCODED_COMMAND, dataValue, tid, HARDCODED_ELEMENT_ADDRESS));

            sendAcknowledgedMessage(HARDCODED_ELEMENT_ADDRESS,
                    new GenericOnOffSet(appKey, HARDCODED_COMMAND, dataValue, tid));

        } catch (Exception e) {
            showSnackbar("Send failed: " + e.getMessage());
        }
    }

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
    // BaseActivity overrides
    // ─────────────────────────────────────────────────────────────────────────
    @Override protected void updateClickableViews()  { }
    @Override protected void enableClickableViews()  { binding.brightnessRow.setEnabled(true);  }
    @Override protected void disableClickableViews() { binding.brightnessRow.setEnabled(false); }

    @Override
    protected void showProgressBar() {
        mHandler.removeCallbacks(mAutoHideProgress);
        mHandler.postDelayed(mAutoHideProgress, PROGRESS_AUTO_HIDE_MS);
    }

    @Override
    protected void hideProgressBar() {
        mHandler.removeCallbacks(mAutoHideProgress);
    }

    private final Runnable mAutoHideProgress = () ->
            Log.d(TAG, "Auto-hiding progress after " + PROGRESS_AUTO_HIDE_MS + "ms");

    @Override
    protected void updateMeshMessage(MeshMessage meshMessage) {
        Log.d(TAG, "updateMeshMessage ▶ response received");
        hideProgressBar();
    }
}