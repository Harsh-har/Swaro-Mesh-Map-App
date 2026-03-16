package no.nordicsemi.android.swaromapmesh.ble;

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
import no.nordicsemi.android.swaromapmesh.R;
import no.nordicsemi.android.swaromapmesh.adapter.ExtendedBluetoothDevice;
import no.nordicsemi.android.swaromapmesh.databinding.ActivityReconnectBinding;
import no.nordicsemi.android.swaromapmesh.utils.Utils;
import no.nordicsemi.android.swaromapmesh.viewmodels.ReconnectViewModel;

@AndroidEntryPoint
public class ReconnectActivity extends AppCompatActivity {

    public static final int REQUEST_DEVICE_READY = 1122;
    private ReconnectViewModel mReconnectViewModel;

    private ActivityReconnectBinding binding;
    private boolean mSilentConnect = false;

    @Override
    protected void onCreate(@Nullable final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // ✅ Check silent BEFORE setContentView to prevent any flash
        final Intent intent = getIntent();
        if (intent == null) { finish(); return; }

        mSilentConnect = intent.getBooleanExtra(Utils.EXTRA_SILENT_CONNECT, false);

        if (mSilentConnect) {
            // Apply transparent window BEFORE setContentView
            getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            getWindow().setDimAmount(0f);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
            overridePendingTransition(0, 0);
        }

        binding = ActivityReconnectBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        mReconnectViewModel = new ViewModelProvider(this).get(ReconnectViewModel.class);

        final ExtendedBluetoothDevice device = intent.getParcelableExtra(Utils.EXTRA_DEVICE);
        if (device == null) { finish(); return; }

        final String deviceName    = device.getName();
        final String deviceAddress = device.getAddress();

        if (mSilentConnect) {
            // ✅ Hide all UI elements — completely invisible to user
            binding.toolbar.setVisibility(View.GONE);

            final View connectionStateView = findViewById(R.id.connection_state);
            if (connectionStateView != null) connectionStateView.setVisibility(View.GONE);

            // ✅ Hide progress bar
            binding.progressBar.setVisibility(View.GONE);

            // ✅ Hide entire container to be safe
            binding.connectivityProgressContainer.setVisibility(View.GONE);

        } else {
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

        mReconnectViewModel.connect(this, device, true);

        mReconnectViewModel.isConnected().observe(this, isConnected -> {
            if (!isConnected) {
                finish();
                if (mSilentConnect) overridePendingTransition(0, 0);
            }
        });

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