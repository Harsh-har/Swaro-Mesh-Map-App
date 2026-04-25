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

    private int selectedSignal = DevicesAdapter.SIGNAL_100;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_rssi_filter);

        // ViewModel (Hilt handles injection)
        mSharedViewModel = new ViewModelProvider(this).get(SharedViewModel.class);

        rgSignalStrength = findViewById(R.id.rgSignalStrength);
        btnApply = findViewById(R.id.btnApply);
        btnReset = findViewById(R.id.btnReset);

        Integer current = mSharedViewModel.getSignalThreshold().getValue();
        if (current != null) selectedSignal = current;

        setInitialSelection(selectedSignal);

        rgSignalStrength.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.rbSignal100)
                selectedSignal = DevicesAdapter.SIGNAL_100;
            else if (checkedId == R.id.rbSignal50)
                selectedSignal = DevicesAdapter.SIGNAL_50;
            else if (checkedId == R.id.rbSignal20)
                selectedSignal = DevicesAdapter.SIGNAL_20;
            else
                selectedSignal = DevicesAdapter.SIGNAL_DEFAULT;
        });

        btnApply.setOnClickListener(v -> {
            mSharedViewModel.setSignalThreshold(selectedSignal);
            finish();
        });

        btnReset.setOnClickListener(v -> {
            selectedSignal = DevicesAdapter.SIGNAL_100;
            mSharedViewModel.setSignalThreshold(selectedSignal);
            setInitialSelection(selectedSignal);
        });
    }

    private void setInitialSelection(int value) {
        if (value == DevicesAdapter.SIGNAL_100)
            rgSignalStrength.check(R.id.rbSignal100);
        else if (value == DevicesAdapter.SIGNAL_50)
            rgSignalStrength.check(R.id.rbSignal50);
        else if (value == DevicesAdapter.SIGNAL_20)
            rgSignalStrength.check(R.id.rbSignal20);
        else
            rgSignalStrength.check(R.id.rbSignalDefault);
    }
}