package no.nordicsemi.android.swaromapmesh.mqtt;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;
import no.nordicsemi.android.swaromapmesh.databinding.ActivityMqttSettingsBinding;

@AndroidEntryPoint
public class MqttSettingsActivity extends AppCompatActivity {

    public static final String PREFS_MQTT        = "mqtt_prefs";
    public static final String KEY_BROKER_HOST   = "broker_host";
    public static final String KEY_BROKER_PORT   = "broker_port";
    public static final String KEY_USERNAME      = "username";
    public static final String KEY_PASSWORD      = "password";

    private static final String TAG = "MqttSettingsActivity";

    @Inject
    MqttManager mqttManager;

    private ActivityMqttSettingsBinding binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        binding = ActivityMqttSettingsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        ViewCompat.setOnApplyWindowInsetsListener(binding.getRoot(), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        setSupportActionBar(binding.toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("MQTT Settings");
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        binding.toolbar.setNavigationOnClickListener(v -> finish());

        loadSettings();
        binding.btnSaveMqtt.setOnClickListener(v -> saveSettings());

        if (mqttManager != null) {
            mqttManager.getIsConnected().observe(this, connected -> {
                Log.d(TAG, "MQTT Status changed: " + connected);
                updateStatusUi(connected);
            });
        }
    }

    private void updateStatusUi(Boolean connected) {
        if (binding == null) return;
        
        int colorRes = (connected != null && connected) ? 
                android.R.color.holo_green_dark : android.R.color.holo_red_dark;
        String statusText = (connected != null && connected) ? "Connected" : "Disconnected";
        
        binding.mqttStatusDot.setBackgroundTintList(ColorStateList.valueOf(
                ContextCompat.getColor(this, colorRes)));
        binding.tvMqttStatus.setText(statusText);
    }

    private void loadSettings() {
        try {
            SharedPreferences p = getSharedPreferences(PREFS_MQTT, Context.MODE_PRIVATE);
            String host = p.getString(KEY_BROKER_HOST, "192.168.1.200");
            int port = p.getInt(KEY_BROKER_PORT, 1883);
            String username = p.getString(KEY_USERNAME, "Swajahome");
            String password = p.getString(KEY_PASSWORD, "12345678");

            binding.etBrokerHost.setText(host);
            binding.etBrokerPort.setText(String.valueOf(port));
            binding.etUsername.setText(username);
            binding.etPassword.setText(password);
        } catch (Exception e) {
            Log.e(TAG, "Error loading settings", e);
        }
    }

    private void saveSettings() {
        try {
            binding.tilBrokerHost.setError(null);
            binding.tilBrokerPort.setError(null);

            String host = binding.etBrokerHost.getText() != null ? binding.etBrokerHost.getText().toString().trim() : "";
            String portS = binding.etBrokerPort.getText() != null ? binding.etBrokerPort.getText().toString().trim() : "";
            String username = binding.etUsername.getText() != null ? binding.etUsername.getText().toString().trim() : "";
            String password = binding.etPassword.getText() != null ? binding.etPassword.getText().toString().trim() : "";

            boolean hasError = false;

            if (TextUtils.isEmpty(host)) {
                binding.tilBrokerHost.setError("Required");
                hasError = true;
            }

            int port = 1883;
            if (TextUtils.isEmpty(portS)) {
                binding.tilBrokerPort.setError("Required");
                hasError = true;
            } else {
                try {
                    port = Integer.parseInt(portS);
                    if (port <= 0 || port > 65535) {
                        binding.tilBrokerPort.setError("Invalid Port");
                        hasError = true;
                    }
                } catch (NumberFormatException e) {
                    binding.tilBrokerPort.setError("Invalid Number");
                    hasError = true;
                }
            }

            if (hasError) return;

            if (mqttManager == null) {
                Toast.makeText(this, "Service not ready", Toast.LENGTH_SHORT).show();
                return;
            }

            getSharedPreferences(PREFS_MQTT, Context.MODE_PRIVATE).edit()
                    .putString(KEY_BROKER_HOST, host)
                    .putInt(KEY_BROKER_PORT, port)
                    .putString(KEY_USERNAME, username)
                    .putString(KEY_PASSWORD, password)
                    .apply();

            mqttManager.connect();
            Toast.makeText(this, "Settings Saved", Toast.LENGTH_SHORT).show();
            finish();
        } catch (Exception e) {
            Log.e(TAG, "Error saving settings", e);
            Toast.makeText(this, "Save Failed", Toast.LENGTH_SHORT).show();
        }
    }

    public static String getOnValueForName(String name) {
        if (name == null) return "1";
        String lower = name.toLowerCase();
        
        if (lower.contains("lc node") || lower.contains("strip node") || lower.contains("dimmer")) {
            return "80";
        } else if (lower.contains("fan node")) {
            return "8";
        } else if (lower.contains("exhaust node")) {
            return "4";
        } else if (lower.contains("ac node")) {
            return "20";
        } else if (lower.contains("relay node") || lower.contains("switch")) {
            return "1";
        } else if (lower.contains("control node")) {
            return "70";
        }
        return "1";
    }

    public static String getOnValue(SharedPreferences prefs, String relationDeviceName) {
        return getOnValueForName(relationDeviceName);
    }

    public static String buildOnCommand(String elementId, String onValue) {
        return "#*2*" + elementId + "*2*" + onValue + "*#";
    }

    public static String buildOffCommand(String elementId) {
        return "#*2*" + elementId + "*2*0*#";
    }
}
