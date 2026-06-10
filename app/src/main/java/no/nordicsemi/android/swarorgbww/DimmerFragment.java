package no.nordicsemi.android.swarorgbww;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
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

public class DimmerFragment extends Fragment {

    private static final String TAG              = "DimmerFragment";
    private static final int    LONG_CMD_LENGTH  = 1;
    private static final int    LONG_CMD_COMMAND = 3; // Assuming 3 for Dimmer
    private static final int    MAX_TID          = 255;
    private static final long   DEBOUNCE_MS      = 500L;

    private final Handler       debounceHandler = new Handler(Looper.getMainLooper());
    private final Runnable      sendRunnable    = this::sendDimmerCommand;
    private final AtomicInteger tidCounter      = new AtomicInteger(0);

    private SeekBar  brightnessSeek;
    private View     brightnessFill;
    private View     brightnessThumb;
    private TextView brightnessLabel;

    private SharedViewModel mViewModel;
    private int brightness = 60;
    
    // For preventing click-to-jump on SeekBar
    private float startX;
    private boolean isDraggingSlider = false;
    private int preTouchBrightness;
    private int touchSlop;

    public DimmerFragment() {}

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_dimmer, container, false);

        mViewModel      = new ViewModelProvider(requireActivity()).get(SharedViewModel.class);
        brightnessSeek  = view.findViewById(R.id.dimmerBrightnessSeek);
        brightnessFill  = view.findViewById(R.id.dimmerBrightnessFill);
        brightnessThumb = view.findViewById(R.id.dimmerBrightnessThumb);
        brightnessLabel = view.findViewById(R.id.dimmerBrightnessLabel);
        
        touchSlop = ViewConfiguration.get(requireContext()).getScaledTouchSlop();

        brightnessLabel.setText(String.format(java.util.Locale.US, "%d%%", brightness));

        brightnessSeek.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                brightnessSeek.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                updateSliderGeometry(brightnessFill, brightnessThumb, brightnessSeek.getProgress(), brightnessSeek.getWidth());
            }
        });

        brightnessSeek.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    startX = event.getX();
                    isDraggingSlider = false;
                    preTouchBrightness = brightness;
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (!isDraggingSlider && Math.abs(event.getX() - startX) > touchSlop) {
                        isDraggingSlider = true;
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (!isDraggingSlider) {
                        // It was just a click, revert to pre-touch brightness
                        brightness = preTouchBrightness;
                        updateUiForManualChange();
                    }
                    isDraggingSlider = false;
                    break;
            }
            return false; // Let SeekBar handle the actual movement
        });

        brightnessSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                if (!fromUser) return;
                
                // Only update if we have confirmed it's a drag, not just a tap
                if (isDraggingSlider) {
                    brightness = progress;
                    brightnessLabel.setText(String.format(java.util.Locale.US, "%d%%", progress));
                    updateSliderGeometry(brightnessFill, brightnessThumb, progress, s.getWidth());
                    scheduleCommand();
                } else {
                    // Revert visual progress if not dragging yet (to prevent jump)
                    s.setProgress(preTouchBrightness);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {
                if (isDraggingSlider) {
                    brightness = s.getProgress();
                    brightnessLabel.setText(String.format(java.util.Locale.US, "%d%%", brightness));
                    updateSliderGeometry(brightnessFill, brightnessThumb, brightness, s.getWidth());
                    fireCommandNow();
                }
            }
        });

        view.findViewById(R.id.dimmerDecrement).setOnClickListener(v -> {
            if (brightness > 0) {
                brightness--;
                updateUiForManualChange();
                fireCommandNow();
            }
        });

        view.findViewById(R.id.dimmerIncrement).setOnClickListener(v -> {
            if (brightness < 100) {
                brightness++;
                updateUiForManualChange();
                fireCommandNow();
            }
        });

        return view;
    }

    private void updateUiForManualChange() {
        brightnessSeek.setProgress(brightness);
        brightnessLabel.setText(String.format(java.util.Locale.US, "%d%%", brightness));
        updateSliderGeometry(brightnessFill, brightnessThumb, brightness, brightnessSeek.getWidth());
    }

    private void updateSliderGeometry(View fillView, View thumbView, int progress, int trackWidth) {
        if (trackWidth <= 0) return;
        
        int fillWidth = Math.round((progress / 100f) * trackWidth);
        
        ViewGroup.LayoutParams lp = fillView.getLayoutParams();
        lp.width = fillWidth;
        fillView.setLayoutParams(lp);
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
        sendDimmerCommand();
    }

    private void sendDimmerCommand() {
        int unicastAddress = getTargetDeviceAddress();
        if (unicastAddress == -1) {
            Toast.makeText(requireContext(), "No valid provisioned device found!", Toast.LENGTH_SHORT).show();
            return;
        }

        int[] data = new int[]{ brightness, 0, 0, 0, 0, 0, 0, 0 };
        ApplicationKey appKey = getFirstAppKey();
        if (appKey == null) {
            Toast.makeText(requireContext(), "No AppKey found!", Toast.LENGTH_SHORT).show();
            return;
        }

        int tid = getNextTid();
        try {
            GenericLightSet msg = new GenericLightSet(appKey, LONG_CMD_LENGTH, LONG_CMD_COMMAND, data, tid);
            mViewModel.getMeshManagerApi().createMeshPdu(unicastAddress, msg);
        } catch (Exception e) {
            Log.e(TAG, "sendDimmerCommand failed", e);
        }
    }

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
