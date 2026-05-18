package no.nordicsemi.android.swarorgbww;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import no.nordicsemi.android.swarorgbww.transport.GenericLightSet;
import no.nordicsemi.android.swarorgbww.transport.ProvisionedMeshNode;
import no.nordicsemi.android.swarorgbww.viewmodels.SharedViewModel;

public class TunableWhiteFragment extends Fragment {

    private static final String TAG                  = "TunableWhiteFragment";
    private static final int    LONG_CMD_LENGTH      = 1;
    private static final int    LONG_CMD_COMMAND     = 5;
    private static final int    MAX_TID              = 255;
    private static final long   DEBOUNCE_MS          = 500L;

    private static final int KELVIN_MIN              = 2700;
    private static final int KELVIN_MAX              = 6500;
    private static final int SLIDER_CORNER_RADIUS_DP = 50;

    private final Handler       debounceHandler = new Handler(Looper.getMainLooper());
    private final Runnable      sendRunnable    = this::sendTunableCommand;
    private final AtomicInteger tidCounter      = new AtomicInteger(0);

    // Views
    private SeekBar  brightnessSeek;
    private SeekBar  colorTempSeek;
    private View     brightnessFill;
    private View     brightnessThumb;
    private View     colorTempThumb;
    private View     colorTempTrack;
    private TextView kelvinLabel;
    private TextView brightnessLabel;   // ← new

    private SharedViewModel mViewModel;

    // State
    private int brightness   = 60;
    private int colorTempPct = 50;

    public TunableWhiteFragment() {}

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {

        View view = inflater.inflate(R.layout.fragment_tunable_white, container, false);

        mViewModel      = new ViewModelProvider(requireActivity()).get(SharedViewModel.class);

        brightnessSeek  = view.findViewById(R.id.tunableBrightnessSeek);
        colorTempSeek   = view.findViewById(R.id.colorTempSeek);
        brightnessFill  = view.findViewById(R.id.tunableBrightnessFill);
        brightnessThumb = view.findViewById(R.id.tunableBrightnessThumb);
        colorTempThumb  = view.findViewById(R.id.colorTempThumb);
        colorTempTrack  = view.findViewById(R.id.colorTempTrack);
        kelvinLabel     = view.findViewById(R.id.kelvinLabel);
        brightnessLabel = view.findViewById(R.id.tunableBrightnessLabel);  // ← new

        // Initial label
        brightnessLabel.setText(brightness + "%");

        // Init brightness slider
        brightnessSeek.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override public void onGlobalLayout() {
                        brightnessSeek.getViewTreeObserver()
                                .removeOnGlobalLayoutListener(this);
                        updateSliderGeometry(
                                brightnessFill, brightnessThumb,
                                brightnessSeek.getProgress(),
                                brightnessSeek.getWidth());
                        brightnessLabel.setText(brightnessSeek.getProgress() + "%");
                    }
                });

        // Init color-temp slider
        colorTempSeek.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override public void onGlobalLayout() {
                        colorTempSeek.getViewTreeObserver()
                                .removeOnGlobalLayoutListener(this);
                        applyColorTempGradients();
                        updateColorTempThumb(
                                colorTempSeek.getProgress(),
                                colorTempSeek.getWidth());
                        updateKelvinLabel(colorTempSeek.getProgress());
                    }
                });

        // Brightness listener
        brightnessSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                if (!fromUser) return;
                brightness = progress;
                brightnessLabel.setText(progress + "%");
                updateSliderGeometry(brightnessFill, brightnessThumb,
                        progress, s.getWidth());
                scheduleCommand();
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {
                brightness = s.getProgress();
                brightnessLabel.setText(brightness + "%");
                updateSliderGeometry(brightnessFill, brightnessThumb,
                        brightness, s.getWidth());
                fireCommandNow();
            }
        });

        // Color Temperature listener
        colorTempSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                if (!fromUser) return;
                colorTempPct = progress;
                updateColorTempThumb(progress, s.getWidth());
                updateKelvinLabel(progress);
                scheduleCommand();
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {
                colorTempPct = s.getProgress();
                updateColorTempThumb(colorTempPct, s.getWidth());
                updateKelvinLabel(colorTempPct);
                fireCommandNow();
            }
        });

        return view;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        debounceHandler.removeCallbacks(sendRunnable);
    }

    // =========================================================================
    // Color temperature helpers
    // =========================================================================

    private int kelvinToColor(int kelvin) {
        float t = (kelvin - KELVIN_MIN) / (float) (KELVIN_MAX - KELVIN_MIN);
        int r = Math.round(0xFF + t * (0xAA - 0xFF));
        int g = Math.round(0xAA + t * (0xD4 - 0xAA));
        int b = Math.round(0x44 + t * (0xFF - 0x44));
        return Color.rgb(r, g, b);
    }

    private void applyColorTempGradients() {
        int warmColor = kelvinToColor(KELVIN_MIN);
        int coolColor = kelvinToColor(KELVIN_MAX);

        GradientDrawable trackGrad = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{warmColor, coolColor});
        trackGrad.setCornerRadius(dpToPx(SLIDER_CORNER_RADIUS_DP));
        colorTempTrack.setBackground(trackGrad);
    }

    private void updateColorTempThumb(int progress, int trackWidth) {
        if (trackWidth <= 0) return;

        int thumbW = colorTempThumb.getWidth();
        if (thumbW == 0) thumbW = dpToPx(40);

        int kelvin     = pctToKelvin(progress);
        int thumbColor = kelvinToColor(kelvin);

        GradientDrawable thumbBg = new GradientDrawable();
        thumbBg.setShape(GradientDrawable.OVAL);
        thumbBg.setColor(thumbColor);
        thumbBg.setStroke(dpToPx(5), Color.WHITE);
        colorTempThumb.setBackground(thumbBg);

        int   trackH = ((View) colorTempThumb.getParent()).getHeight();
        float thumbX = (progress / 100f) * trackWidth - thumbW / 2f;
        thumbX = Math.max(0, Math.min(thumbX, trackWidth - thumbW));
        colorTempThumb.setX(thumbX);

        int thumbH = colorTempThumb.getHeight();
        if (thumbH == 0) thumbH = dpToPx(40);
        colorTempThumb.setY((trackH - thumbH) / 2f);
    }

    private void updateKelvinLabel(int progress) {
        int kelvin = pctToKelvin(progress);
        kelvinLabel.setText(String.format(java.util.Locale.US, "%d K", kelvin));
    }

    private int pctToKelvin(int pct) {
        return KELVIN_MIN + Math.round((pct / 100f) * (KELVIN_MAX - KELVIN_MIN));
    }

    // =========================================================================
    // Brightness slider geometry
    // =========================================================================

    private void updateSliderGeometry(View fillView, View thumbView,
                                      int progress, int trackWidth) {
        if (trackWidth <= 0) return;

        int thumbW = thumbView.getWidth();
        if (thumbW == 0) thumbW = dpToPx(40);

        int trackH    = ((View) thumbView.getParent()).getHeight();
        int minOffset = dpToPx(4);
        int extra     = dpToPx(35);

        float thumbX = minOffset + (progress / 100f) * (trackWidth - thumbW - minOffset);
        thumbX = Math.max(minOffset, Math.min(thumbX, trackWidth - thumbW));
        thumbView.setX(thumbX);

        int fillWidth = Math.round(minOffset + (progress / 100f) * (trackWidth - thumbW - minOffset));
        fillWidth = Math.max(minOffset, Math.min(fillWidth + extra, trackWidth));

        ViewGroup.LayoutParams lp = fillView.getLayoutParams();
        lp.width = fillWidth;
        fillView.setLayoutParams(lp);

        int thumbH = thumbView.getHeight();
        if (thumbH == 0) thumbH = dpToPx(40);
        thumbView.setY((trackH - thumbH) / 2f);
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    // =========================================================================
    // Debounce
    // =========================================================================

    private void scheduleCommand() {
        debounceHandler.removeCallbacks(sendRunnable);
        debounceHandler.postDelayed(sendRunnable, DEBOUNCE_MS);
    }

    private void fireCommandNow() {
        debounceHandler.removeCallbacks(sendRunnable);
        sendTunableCommand();
    }

    // =========================================================================
    // Send
    // =========================================================================

    private void sendTunableCommand() {
        int unicastAddress = getTargetDeviceAddress();
        if (unicastAddress == -1) {
            Toast.makeText(requireContext(),
                    "No valid provisioned device found!",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        int[] data = new int[]{
                brightness,
                colorTempPct,
                0, 0, 0, 0, 0, 0
        };

        ApplicationKey appKey = getFirstAppKey();
        if (appKey == null) {
            Toast.makeText(requireContext(),
                    "No AppKey found!",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        int tid = getNextTid();

        Log.d(TAG, "══ sendTunableCommand ══");
        Log.d(TAG, String.format("  Dest        : 0x%04X", unicastAddress));
        Log.d(TAG, String.format("  data[0] B   : %d",    brightness));
        Log.d(TAG, String.format("  data[1] CT  : %d",    colorTempPct));
        Log.d(TAG, String.format("  TID         : %d",    tid));
        Log.d(TAG, "════════════════════════");

        try {
            GenericLightSet msg = new GenericLightSet(
                    appKey, LONG_CMD_LENGTH, LONG_CMD_COMMAND, data, tid);
            mViewModel.getMeshManagerApi().createMeshPdu(unicastAddress, msg);
            Log.d(TAG, "✓ Sent");
        } catch (Exception e) {
            Log.e(TAG, "sendTunableCommand failed", e);
            Toast.makeText(requireContext(),
                    "Send failed: " + e.getMessage(),
                    Toast.LENGTH_SHORT).show();
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private int getNextTid() {
        int count = tidCounter.getAndIncrement();
        if (count > MAX_TID) { tidCounter.set(0); count = 0; }
        return count;
    }

    private int getTargetDeviceAddress() {
        List<ProvisionedMeshNode> nodes = mViewModel.getAllProvisionedNodes();
        if (nodes == null || nodes.isEmpty()) return -1;
        for (ProvisionedMeshNode node : nodes) {
            int address = node.getUnicastAddress();
            if (address == 0x0000 || address == 0x0001) continue;
            Log.d(TAG, String.format("Using node: 0x%04X  mac=%s",
                    address, node.getMacAddress()));
            return address;
        }
        return -1;
    }

    private ApplicationKey getFirstAppKey() {
        try {
            List<ApplicationKey> keys = mViewModel.getNetworkLiveData().getAppKeys();
            if (keys != null && !keys.isEmpty()) return keys.get(0);
        } catch (Exception e) {
            Log.e(TAG, "getFirstAppKey error", e);
        }
        return null;
    }
}