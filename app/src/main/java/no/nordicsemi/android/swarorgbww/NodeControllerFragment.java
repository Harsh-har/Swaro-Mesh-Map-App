package no.nordicsemi.android.swarorgbww;

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
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import no.nordicsemi.android.swarorgbww.transport.GenericLightSet;
import no.nordicsemi.android.swarorgbww.transport.ProvisionedMeshNode;
import no.nordicsemi.android.swarorgbww.viewmodels.SharedViewModel;

public class NodeControllerFragment extends Fragment {

    private static final String TAG              = "NodeControllerFragment";
    private static final int    CMD_LENGTH       = 1;
    private static final int    CMD_COMMAND      = 4; // Node Controller command
    private static final int    MAX_TID          = 255;
    private static final long   DEBOUNCE_MS      = 500L;

    private final Handler       debounceHandler = new Handler(Looper.getMainLooper());
    private final Runnable      sendRunnable    = this::sendNodeCommand;
    private final AtomicInteger tidCounter      = new AtomicInteger(0);

    private SharedViewModel mViewModel;

    // State for 4 channels + 1 global
    private final int[] brightnessValues = {60, 60, 60, 60};
    private int globalBrightness = 60;
    
    private final SeekBar[] seekBars      = new SeekBar[4];
    private final View[]    fillViews     = new View[4];
    private final TextView[] labels        = new TextView[4];
    
    private SeekBar  globalSeek;
    private View     globalFill;
    private TextView globalLabel;

    // Touch logic
    private float startX;
    private boolean isDragging = false;
    private int preTouchValue;
    private int touchSlop;

    public NodeControllerFragment() {}

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_node_controller, container, false);

        mViewModel = new ViewModelProvider(requireActivity()).get(SharedViewModel.class);
        touchSlop  = ViewConfiguration.get(requireContext()).getScaledTouchSlop();

        // Global
        globalSeek  = view.findViewById(R.id.globalBrightnessSeek);
        globalFill  = view.findViewById(R.id.globalBrightnessFill);
        globalLabel = view.findViewById(R.id.globalBrightnessLabel);
        initGlobal(view);

        // Individual Channels
        initChannel(view, 0, R.id.brightnessSeek1, R.id.brightnessFill1, R.id.brightnessLabel1, R.id.decrement1, R.id.increment1);
        initChannel(view, 1, R.id.brightnessSeek2, R.id.brightnessFill2, R.id.brightnessLabel2, R.id.decrement2, R.id.increment2);
        initChannel(view, 2, R.id.brightnessSeek3, R.id.brightnessFill3, R.id.brightnessLabel3, R.id.decrement3, R.id.increment3);
        initChannel(view, 3, R.id.brightnessSeek4, R.id.brightnessFill4, R.id.brightnessLabel4, R.id.decrement4, R.id.increment4);

        return view;
    }

    private void initGlobal(View root) {
        globalLabel.setText(globalBrightness + "%");
        
        globalSeek.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                globalSeek.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                updateGlobalSliderGeometry();
            }
        });

        globalSeek.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    startX = event.getX();
                    isDragging = false;
                    preTouchValue = globalBrightness;
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (!isDragging && Math.abs(event.getX() - startX) > touchSlop) isDragging = true;
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (!isDragging) {
                        globalBrightness = preTouchValue;
                        updateGlobalUi();
                    }
                    isDragging = false;
                    break;
            }
            return false;
        });

        globalSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                if (!fromUser) return;
                if (isDragging) {
                    applyGlobal(progress);
                    scheduleCommand();
                } else {
                    s.setProgress(preTouchValue);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {
                if (isDragging) {
                    applyGlobal(s.getProgress());
                    fireCommandNow();
                }
            }
        });

        root.findViewById(R.id.globalDecrement).setOnClickListener(v -> {
            if (globalBrightness > 0) {
                applyGlobal(globalBrightness - 1);
                fireCommandNow();
            }
        });

        root.findViewById(R.id.globalIncrement).setOnClickListener(v -> {
            if (globalBrightness < 100) {
                applyGlobal(globalBrightness + 1);
                fireCommandNow();
            }
        });
    }

    private void applyGlobal(int value) {
        globalBrightness = value;
        updateGlobalUi();
        for (int i = 0; i < 4; i++) {
            brightnessValues[i] = value;
            updateUi(i);
        }
    }

    private void updateGlobalUi() {
        globalSeek.setProgress(globalBrightness);
        globalLabel.setText(globalBrightness + "%");
        updateGlobalSliderGeometry();
    }

    private void updateGlobalSliderGeometry() {
        int width = globalSeek.getWidth();
        if (width <= 0) return;
        ViewGroup.LayoutParams lp = globalFill.getLayoutParams();
        lp.width = Math.round((globalBrightness / 100f) * width);
        globalFill.setLayoutParams(lp);
    }

    private void initChannel(View root, int index, int seekId, int fillId, int labelId, int decId, int incId) {
        SeekBar seek  = root.findViewById(seekId);
        View fill     = root.findViewById(fillId);
        TextView label = root.findViewById(labelId);
        View decBtn   = root.findViewById(decId);
        View incBtn   = root.findViewById(incId);

        seekBars[index]  = seek;
        fillViews[index] = fill;
        labels[index]    = label;

        label.setText(brightnessValues[index] + "%");

        seek.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                seek.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                updateSliderGeometry(index);
            }
        });

        seek.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    startX = event.getX();
                    isDragging = false;
                    preTouchValue = brightnessValues[index];
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (!isDragging && Math.abs(event.getX() - startX) > touchSlop) isDragging = true;
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (!isDragging) {
                        brightnessValues[index] = preTouchValue;
                        updateUi(index);
                    }
                    isDragging = false;
                    break;
            }
            return false;
        });

        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                if (!fromUser) return;
                if (isDragging) {
                    brightnessValues[index] = progress;
                    updateUi(index);
                    scheduleCommand();
                } else {
                    s.setProgress(preTouchValue);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {
                if (isDragging) {
                    brightnessValues[index] = s.getProgress();
                    updateUi(index);
                    fireCommandNow();
                }
            }
        });

        decBtn.setOnClickListener(v -> {
            if (brightnessValues[index] > 0) {
                brightnessValues[index]--;
                updateUi(index);
                fireCommandNow();
            }
        });

        incBtn.setOnClickListener(v -> {
            if (brightnessValues[index] < 100) {
                brightnessValues[index]++;
                updateUi(index);
                fireCommandNow();
            }
        });
    }

    private void updateUi(int index) {
        seekBars[index].setProgress(brightnessValues[index]);
        labels[index].setText(brightnessValues[index] + "%");
        updateSliderGeometry(index);
    }

    private void updateSliderGeometry(int index) {
        SeekBar seek = seekBars[index];
        View fill    = fillViews[index];
        int width    = seek.getWidth();
        if (width <= 0) return;
        ViewGroup.LayoutParams lp = fill.getLayoutParams();
        lp.width = Math.round((brightnessValues[index] / 100f) * width);
        fill.setLayoutParams(lp);
    }

    private void scheduleCommand() {
        debounceHandler.removeCallbacks(sendRunnable);
        debounceHandler.postDelayed(sendRunnable, DEBOUNCE_MS);
    }

    private void fireCommandNow() {
        debounceHandler.removeCallbacks(sendRunnable);
        sendNodeCommand();
    }

    private void sendNodeCommand() {
        int address = getTargetDeviceAddress();
        if (address == -1) return;
        int[] data = new int[8];
        for (int i = 0; i < 4; i++) data[i] = brightnessValues[i];
        ApplicationKey appKey = getFirstAppKey();
        if (appKey == null) return;
        int tid = getNextTid();
        try {
            GenericLightSet msg = new GenericLightSet(appKey, CMD_LENGTH, CMD_COMMAND, data, tid);
            mViewModel.getMeshManagerApi().createMeshPdu(address, msg);
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
