package no.nordicsemi.android.swaromapmesh;

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
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import no.nordicsemi.android.swaromapmesh.transport.GenericLightSet;
import no.nordicsemi.android.swaromapmesh.transport.ProvisionedMeshNode;
import no.nordicsemi.android.swaromapmesh.viewmodels.SharedViewModel;

public class RgbControllerFragment extends Fragment {

    private static final String TAG              = "RgbControllerFragment";
    private static final int    LONG_CMD_LENGTH  = 1;
    private static final int    LONG_CMD_COMMAND = 4;
    private static final int    MAX_TID          = 255;
    private static final long   DEBOUNCE_MS      = 500L;

    private final Handler       debounceHandler = new Handler(Looper.getMainLooper());
    private final Runnable      sendRunnable    = this::sendRgbCommand;
    private final AtomicInteger tidCounter      = new AtomicInteger(0);

    // Views
    private ColorWheelView colorWheelView;
    private SeekBar        brightnessSeek;
    private SeekBar        saturationSeek;
    private View           brightnessFill;
    private View           saturationFill;
    private View           brightnessThumb;
    private View           saturationThumb;

    private SharedViewModel mViewModel;

    // State
    private int wheelAngle    = 0;
    private int brightness    = 60;
    private int saturation    = 100;
    private int selectedColor = Color.RED;

    public RgbControllerFragment() {}

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {

        View view = inflater.inflate(R.layout.fragment_rgb_controller, container, false);

        mViewModel      = new ViewModelProvider(requireActivity()).get(SharedViewModel.class);
        colorWheelView  = view.findViewById(R.id.colorWheelView);
        brightnessSeek  = view.findViewById(R.id.brightnessSeek);
        saturationSeek  = view.findViewById(R.id.saturationSeek);
        brightnessFill  = view.findViewById(R.id.brightnessFill);
        saturationFill  = view.findViewById(R.id.saturationFill);
        brightnessThumb = view.findViewById(R.id.brightnessThumb);
        saturationThumb = view.findViewById(R.id.saturationThumb);

        // Init fills after layout measured
        brightnessSeek.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override public void onGlobalLayout() {
                        brightnessSeek.getViewTreeObserver()
                                .removeOnGlobalLayoutListener(this);
                        updateSliderGeometry(brightnessFill, brightnessThumb,
                                brightnessSeek.getProgress(),
                                brightnessSeek.getWidth());
                    }
                });

        saturationSeek.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override public void onGlobalLayout() {
                        saturationSeek.getViewTreeObserver()
                                .removeOnGlobalLayoutListener(this);
                        updateSaturationGradient(selectedColor);
                        updateSliderGeometry(saturationFill, saturationThumb,
                                saturationSeek.getProgress(),
                                saturationSeek.getWidth());
                    }
                });

        // Color Wheel
        colorWheelView.setOnColorPickedListener(color -> {
            wheelAngle    = colorWheelView.getThumbAngle();
            selectedColor = color;
            updateSaturationGradient(selectedColor);
            updateSliderGeometry(saturationFill, saturationThumb,
                    saturationSeek.getProgress(),
                    saturationSeek.getWidth());
            Log.d(TAG, String.format("Wheel: %d°  color=#%06X",
                    wheelAngle, selectedColor & 0xFFFFFF));
            scheduleCommand();
        });

        // Brightness
        brightnessSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                if (!fromUser) return;
                brightness = progress;
                updateSliderGeometry(brightnessFill, brightnessThumb,
                        progress, s.getWidth());
                scheduleCommand();
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {
                brightness = s.getProgress();
                updateSliderGeometry(brightnessFill, brightnessThumb,
                        brightness, s.getWidth());
                fireCommandNow();
            }
        });

        // Saturation
        saturationSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                if (!fromUser) return;
                saturation = progress;
                updateSliderGeometry(saturationFill, saturationThumb,
                        progress, s.getWidth());
                scheduleCommand();
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {
                saturation = s.getProgress();
                updateSliderGeometry(saturationFill, saturationThumb,
                        saturation, s.getWidth());
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
    // Slider geometry — fill width + thumb X
    // =========================================================================

    private void updateSliderGeometry(View fillView, View thumbView,
                                      int progress, int trackWidth) {
        if (trackWidth <= 0) return;

        int thumbW = thumbView.getWidth();
        if (thumbW == 0) thumbW = dpToPx(40);

        int trackH = thumbView.getRootView() != null
                ? ((View) thumbView.getParent()).getHeight() : dpToPx(58);

        int fillWidth = Math.round((progress / 100f) * trackWidth);
        fillWidth = Math.max(thumbW / 2, Math.min(fillWidth, trackWidth));

        // Fill width
        ViewGroup.LayoutParams lp = fillView.getLayoutParams();
        lp.width = fillWidth;
        fillView.setLayoutParams(lp);

        // Thumb X — center aligns with fill right edge
        float thumbX = fillWidth - thumbW / 2f;
        thumbX = Math.max(0, Math.min(thumbX, trackWidth - thumbW));
        thumbView.setX(thumbX);

        // Thumb Y — vertically centered in parent FrameLayout
        int thumbH = thumbView.getHeight();
        if (thumbH == 0) thumbH = dpToPx(40);
        float thumbY = (trackH - thumbH) / 2f;
        thumbView.setY(thumbY);
    }

    // =========================================================================
    // Saturation gradient — white → wheel selected color
    // =========================================================================

    private void updateSaturationGradient(int color) {
        int endColor = Color.rgb(
                Color.red(color),
                Color.green(color),
                Color.blue(color));

        // Fill gradient
        GradientDrawable gradient = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Color.WHITE, endColor});
        gradient.setCornerRadius(dpToPx(50));
        saturationFill.setBackground(gradient);

        // Thumb — white ring + inner selected color
        GradientDrawable thumbBg = new GradientDrawable();
        thumbBg.setShape(GradientDrawable.OVAL);
        thumbBg.setColor(endColor);
        thumbBg.setStroke(dpToPx(5), Color.WHITE);
        saturationThumb.setBackground(thumbBg);
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
        sendRgbCommand();
    }

    // =========================================================================
    // Send
    // =========================================================================

    private void sendRgbCommand() {
        int unicastAddress = getTargetDeviceAddress();
        if (unicastAddress == -1) {
            Toast.makeText(requireContext(),
                    "No valid provisioned device found!",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        int angleLow  = wheelAngle & 0xFF;
        int angleHigh = (wheelAngle >> 8) & 0xFF;

        int[] data = new int[]{
                brightness,
                saturation,
                angleLow,
                angleHigh,
                0, 0, 0, 0
        };

        ApplicationKey appKey = getFirstAppKey();
        if (appKey == null) {
            Toast.makeText(requireContext(),
                    "No AppKey found!", Toast.LENGTH_SHORT).show();
            return;
        }

        int tid = getNextTid();

        Log.d(TAG, "══ sendRgbCommand ══");
        Log.d(TAG, String.format("  Dest     : 0x%04X", unicastAddress));
        Log.d(TAG, String.format("  data[0] B: %d",     brightness));
        Log.d(TAG, String.format("  data[1] S: %d",     saturation));
        Log.d(TAG, String.format("  data[2]  : %d",     angleLow));
        Log.d(TAG, String.format("  data[3]  : %d  full=%d°", angleHigh, wheelAngle));
        Log.d(TAG, String.format("  TID      : %d",     tid));
        Log.d(TAG, "════════════════════");

        try {
            GenericLightSet msg = new GenericLightSet(
                    appKey, LONG_CMD_LENGTH, LONG_CMD_COMMAND, data, tid);
            mViewModel.getMeshManagerApi().createMeshPdu(unicastAddress, msg);
            Log.d(TAG, "✓ Sent");
        } catch (Exception e) {
            Log.e(TAG, "sendRgbCommand failed", e);
            Toast.makeText(requireContext(),
                    "Send failed: " + e.getMessage(),
                    Toast.LENGTH_SHORT).show();
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private int getNextTid() {
        int c = tidCounter.getAndIncrement();
        if (c > MAX_TID) { tidCounter.set(0); c = 0; }
        return c;
    }

    private int getTargetDeviceAddress() {
        List<ProvisionedMeshNode> nodes = mViewModel.getAllProvisionedNodes();
        if (nodes == null || nodes.isEmpty()) return -1;
        for (ProvisionedMeshNode node : nodes) {
            int addr = node.getUnicastAddress();
            if (addr == 0x0000 || addr == 0x0001) continue;
            Log.d(TAG, String.format("Using node: 0x%04X  mac=%s",
                    addr, node.getMacAddress()));
            return addr;
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