package no.nordicsemi.android.swaromesh.ble;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.lifecycle.ViewModelProvider;

import dagger.hilt.android.AndroidEntryPoint;
import no.nordicsemi.android.swaromesh.R;
import no.nordicsemi.android.swaromesh.adapter.ExtendedBluetoothDevice;
import no.nordicsemi.android.swaromesh.databinding.ActivityReconnectBinding;
import no.nordicsemi.android.swaromesh.utils.Utils;
import no.nordicsemi.android.swaromesh.viewmodels.ReconnectViewModel;

@AndroidEntryPoint
public class ReconnectActivity extends AppCompatActivity {

    public static final int REQUEST_DEVICE_READY = 1122; //Random number
    private ReconnectViewModel mReconnectViewModel;

    private ActivityReconnectBinding binding;
    private boolean mSilentConnect = false;

    @Override
    protected void onCreate(@Nullable final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityReconnectBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // ViewModel
        mReconnectViewModel = new ViewModelProvider(this).get(ReconnectViewModel.class);

        final Intent intent = getIntent();
        if (intent == null) {
            finish();
            return;
        }

        // Silent connect flag (AUTO PROXY CONNECT)
        mSilentConnect = intent.getBooleanExtra(Utils.EXTRA_SILENT_CONNECT, false);

        final ExtendedBluetoothDevice device = intent.getParcelableExtra(Utils.EXTRA_DEVICE);
        if (device == null) {
            finish();
            return;
        }

        final String deviceName = device.getName();
        final String deviceAddress = device.getAddress();

        // ------------------ SILENT MODE (BACKGROUND) ------------------
        if (mSilentConnect) {
            // Hide complete UI
            binding.toolbar.setVisibility(View.GONE);

            // Hide any other views if exist in layout
            final View connectionStateView = findViewById(R.id.connection_state);
            if (connectionStateView != null) connectionStateView.setVisibility(View.GONE);

            // Make activity transparent + no touch (looks like nothing opened)
            getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            getWindow().setDimAmount(0f);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);

            // No animation (prevents screen blink)
            overridePendingTransition(0, 0);

        } else {
            // ------------------ NORMAL MODE (MANUAL) ------------------
            final Toolbar toolbar = binding.toolbar;
            setSupportActionBar(toolbar);

            if (getSupportActionBar() != null) {
                getSupportActionBar().setDisplayHomeAsUpEnabled(true);
                getSupportActionBar().setTitle(deviceName);
                getSupportActionBar().setSubtitle(deviceAddress);
            }

            final TextView connectionState = findViewById(R.id.connection_state);
            if (connectionState != null) {
                mReconnectViewModel.getConnectionState().observe(this, connectionState::setText);
            }
        }

        // Connect (works for both silent/manual)
        mReconnectViewModel.connect(this, device, true);

        // If disconnected -> close
        mReconnectViewModel.isConnected().observe(this, isConnected -> {
            if (!isConnected) {
                finish();
                if (mSilentConnect) overridePendingTransition(0, 0);
            }
        });

        // When device ready -> return OK
        mReconnectViewModel.isDeviceReady().observe(this, deviceReady -> {
            if (mReconnectViewModel.getBleMeshManager().isDeviceReady()) {
                final Intent returnIntent = new Intent();
                returnIntent.putExtra(Utils.EXTRA_DATA, true);
                setResult(Activity.RESULT_OK, returnIntent);
                finish();
                if (mSilentConnect) overridePendingTransition(0, 0);
            }
        });
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull final MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            onBackPressed();
            return true;
        }
        return false;
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
        mReconnectViewModel.disconnect();
    }

    @Override
    protected void onStop() {
        super.onStop();
    }
}
