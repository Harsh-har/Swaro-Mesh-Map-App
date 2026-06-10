package no.nordicsemi.android.swarorgbww;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
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

    private static final String TAG              = "TunableWhiteFragment";
    private static final int    CMD_LENGTH       = 1;
    private static final int    CMD_COMMAND      = 5;
    private static final int    MAX_TID          = 255;
    private static final long   DEBOUNCE_MS      = 500L;

    private static final int KELVIN_MIN          = 2700;
    private static final int KELVIN_MAX          = 6500;

    private final Handler       debounceHandler = new Handler(Looper.getMainLooper());
    private final Runnable      sendRunnable    = this::sendTunableCommand;
    private final AtomicInteger tidCounter      = new AtomicInteger(0);

    private SharedViewModel mViewModel;

    // Brightness (New Dimmer Style)
    private SeekBar  brightSeek;
    private View     brightFill;
    private TextView brightLabel;
    private int      brightness = 60;

    // Color Temp (Original Style)
    private SeekBar  tempSeek;
    private View     colorTempThumb;
    private View     colorTempTrack;
    private TextView kelvinLabel;
    private int      colorTempPct = 50;

    // Touch logic (for Brightness only)
    private float   startX;
    private boolean isDragging = false;
    private int     preTouchValue;
    private int     touchSlop;

    public TunableWhiteFragment() {}

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_tunable_white, container, false);

        mViewModel = new ViewModelProvider(requireActivity()).get(SharedViewModel.class);
        touchSlop  = ViewConfiguration.get(requireContext()).getScaledTouchSlop();

        // Bind Brightness
        brightSeek  = view.findViewById(R.id.tunableBrightnessSeek);
        brightFill  = view.findViewById(R.id.tunableBrightnessFill);
        brightLabel = view.findViewById(R.id.tunableBrightnessLabel);
        initBrightnessSlider(view);

        // Bind Color Temp
        tempSeek       = view.findViewById(R.id.colorTempSeek);
        colorTempThumb = view.findViewById(R.id.colorTempThumb);
        colorTempTrack = view.findViewById(R.id.colorTempTrack);
        kelvinLabel    = view.findViewById(R.id.kelvinLabel);
        initTempSlider();

        return view;
    }

    private void initBrightnessSlider(View root) {
        brightLabel.setText(brightness + "%");
        brightSeek.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override public void onGlobalLayout() {
                brightSeek.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                updateSliderFill(brightFill, brightness, brightSeek.getWidth());
            }
        });

        brightSeek.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    startX = event.getX();
                    isDragging = false;
                    preTouchValue = brightness;
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (!isDragging && Math.abs(event.getX() - startX) > touchSlop) isDragging = true;
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (!isDragging) updateBrightnessUi();
                    isDragging = false;
                    break;
            }
            return false;
        });

        brightSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                if (!fromUser) return;
                if (isDragging) {
                    brightness = progress;
                    brightLabel.setText(progress + "%");
                    updateSliderFill(brightFill, progress, s.getWidth());
                    scheduleCommand();
                } else s.setProgress(preTouchValue);
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {
                if (isDragging) {
                    brightness = s.getProgress();
                    brightLabel.setText(brightness + "%");
                    updateSliderFill(brightFill, brightness, s.getWidth());
                    fireCommandNow();
                }
            }
        });

        root.findViewById(R.id.brightDecrement).setOnClickListener(v -> {
            if (brightness > 0) {
                brightness--;
                updateBrightnessUi();
                fireCommandNow();
            }
        });
        root.findViewById(R.id.brightIncrement).setOnClickListener(v -> {
            if (brightness < 100) {
                brightness++;
                updateBrightnessUi();
                fireCommandNow();
            }
        });
    }

    private void initTempSlider() {
        updateKelvinLabel(colorTempPct);
        tempSeek.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override public void onGlobalLayout() {
                tempSeek.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                applyColorTempGradients();
                updateColorTempThumb(colorTempPct, tempSeek.getWidth());
            }
        });

        tempSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                if (!fromUser) return;
                colorTempPct = progress;
                updateKelvinLabel(progress);
                updateColorTempThumb(progress, s.getWidth());
                scheduleCommand();
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {
                colorTempPct = s.getProgress();
                updateKelvinLabel(colorTempPct);
                updateColorTempThumb(colorTempPct, s.getWidth());
                fireCommandNow();
            }
        });
    }

    private void updateBrightnessUi() {
        brightSeek.setProgress(brightness);
        brightLabel.setText(brightness + "%");
        updateSliderFill(brightFill, brightness, brightSeek.getWidth());
    }

    private void updateSliderFill(View fill, int progress, int width) {
        if (width <= 0) return;
        ViewGroup.LayoutParams lp = fill.getLayoutParams();
        lp.width = Math.round((progress / 100f) * width);
        fill.setLayoutParams(lp);
    }

    private void updateKelvinLabel(int progress) {
        int kelvin = KELVIN_MIN + Math.round((progress / 100f) * (KELVIN_MAX - KELVIN_MIN));
        kelvinLabel.setText(String.format(java.util.Locale.US, "%d K", kelvin));
    }

    private void applyColorTempGradients() {
        int warmColor = kelvinToColor(KELVIN_MIN);
        int coolColor = kelvinToColor(KELVIN_MAX);
        GradientDrawable trackGrad = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{warmColor, coolColor});
        trackGrad.setCornerRadius(dpToPx(50));
        colorTempTrack.setBackground(trackGrad);
    }

    private void updateColorTempThumb(int progress, int trackWidth) {
        if (trackWidth <= 0) return;
        int thumbW = colorTempThumb.getWidth();
        if (thumbW == 0) thumbW = dpToPx(40);
        int kelvin = KELVIN_MIN + Math.round((progress / 100f) * (KELVIN_MAX - KELVIN_MIN));
        int thumbColor = kelvinToColor(kelvin);

        GradientDrawable thumbBg = new GradientDrawable();
        thumbBg.setShape(GradientDrawable.OVAL);
        thumbBg.setColor(thumbColor);
        thumbBg.setStroke(dpToPx(5), Color.WHITE);
        colorTempThumb.setBackground(thumbBg);

        int trackH = ((View) colorTempThumb.getParent()).getHeight();
        float thumbX = (progress / 100f) * trackWidth - thumbW / 2f;
        thumbX = Math.max(0, Math.min(thumbX, trackWidth - thumbW));
        colorTempThumb.setX(thumbX);
        colorTempThumb.setY((trackH - colorTempThumb.getHeight()) / 2f);
    }

    private int kelvinToColor(int kelvin) {
        float t = (kelvin - KELVIN_MIN) / (float) (KELVIN_MAX - KELVIN_MIN);
        int r = Math.round(0xFF + t * (0xAA - 0xFF));
        int g = Math.round(0xAA + t * (0xD4 - 0xAA));
        int b = Math.round(0x44 + t * (0xFF - 0x44));
        return Color.rgb(r, g, b);
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    private void scheduleCommand() {
        debounceHandler.removeCallbacks(sendRunnable);
        debounceHandler.postDelayed(sendRunnable, DEBOUNCE_MS);
    }

    private void fireCommandNow() {
        debounceHandler.removeCallbacks(sendRunnable);
        sendTunableCommand();
    }

    private void sendTunableCommand() {
        int addr = getTargetDeviceAddress();
        if (addr == -1) return;
        int[] data = new int[]{brightness, colorTempPct, 0, 0, 0, 0, 0, 0};
        ApplicationKey appKey = getFirstAppKey();
        if (appKey == null) return;
        int tid = getNextTid();
        try {
            GenericLightSet msg = new GenericLightSet(appKey, CMD_LENGTH, CMD_COMMAND, data, tid);
            mViewModel.getMeshManagerApi().createMeshPdu(addr, msg);
        } catch (Exception e) {
            Log.e(TAG, "Send failed", e);
        }
    }

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

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        debounceHandler.removeCallbacks(sendRunnable);
    }
}
