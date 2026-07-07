package no.nordicsemi.android.swaromapmesh;

import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.RadioGroup;

import androidx.activity.EdgeToEdge;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.lifecycle.ViewModelProvider;

import java.util.Objects;

import dagger.hilt.android.AndroidEntryPoint;
import no.nordicsemi.android.swaromapmesh.ble.adapter.DevicesAdapter;
import no.nordicsemi.android.swaromapmesh.viewmodels.SharedViewModel;

@AndroidEntryPoint
public class RssiFilterActivity extends AppCompatActivity {

    private SharedViewModel mSharedViewModel;

    private RadioGroup rgSignalStrength;
    private Button btnApply, btnReset;

    // TODO: SIGNAL_VERY_CLOSE / SIGNAL_50 logic temporarily disabled —
    // DevicesAdapter doesn't define these constants yet. Falling back to
    // SIGNAL_DEFAULT until final threshold values are decided.
    private int selectedSignal = DevicesAdapter.SIGNAL_DEFAULT;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_rssi_filter);

        mSharedViewModel = new ViewModelProvider(this).get(SharedViewModel.class);

        rgSignalStrength = findViewById(R.id.rgSignalStrength);
        btnApply         = findViewById(R.id.btnApply);
        btnReset         = findViewById(R.id.btnReset);

        Integer current = mSharedViewModel.getSignalThreshold().getValue();
        if (current != null) selectedSignal = current;

        setInitialSelection(selectedSignal);

        rgSignalStrength.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.rbSignalVeryStrong)
                selectedSignal = DevicesAdapter.SIGNAL_VERY_STRONG;
            else if (checkedId == R.id.rbSignalStrong)
                selectedSignal = DevicesAdapter.SIGNAL_STRONG;
            else if (checkedId == R.id.rbSignalMedium)
                selectedSignal = DevicesAdapter.SIGNAL_MEDIUM;
            else if (checkedId == R.id.rbSignalWeak)
                selectedSignal = DevicesAdapter.SIGNAL_WEAK;
            else
                selectedSignal = DevicesAdapter.SIGNAL_ALL;
        });

        btnApply.setOnClickListener(v -> {
            mSharedViewModel.setSignalThreshold(selectedSignal);
            finish();
        });

        btnReset.setOnClickListener(v -> {
            selectedSignal = DevicesAdapter.SIGNAL_ALL;
            mSharedViewModel.setSignalThreshold(selectedSignal);
            setInitialSelection(selectedSignal);
        });
    }

    private void setInitialSelection(int value) {
        if (value == DevicesAdapter.SIGNAL_VERY_STRONG)
            rgSignalStrength.check(R.id.rbSignalVeryStrong);
        else if (value == DevicesAdapter.SIGNAL_STRONG)
            rgSignalStrength.check(R.id.rbSignalStrong);
        else if (value == DevicesAdapter.SIGNAL_MEDIUM)
            rgSignalStrength.check(R.id.rbSignalMedium);
        else if (value == DevicesAdapter.SIGNAL_WEAK)
            rgSignalStrength.check(R.id.rbSignalWeak);
        else
            rgSignalStrength.check(R.id.rbSignalAll);
    }
}