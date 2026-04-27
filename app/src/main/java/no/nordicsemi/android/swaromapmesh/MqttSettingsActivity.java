package no.nordicsemi.android.swaromapmesh;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Toast;
import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public class MqttSettingsActivity extends AppCompatActivity {

    public static final String PREFS_MQTT        = "mqtt_prefs";
    public static final String KEY_BROKER_HOST   = "broker_host";
    public static final String KEY_BROKER_PORT   = "broker_port";
    public static final String KEY_USERNAME      = "username";
    public static final String KEY_PASSWORD      = "password";

    private TextInputLayout   tilBrokerHost, tilBrokerPort;
    private TextInputEditText etBrokerHost, etBrokerPort;
    private TextInputEditText etUsername, etPassword;
    private MaterialButton    btnSave;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_mqtt_settings);

        tilBrokerHost = findViewById(R.id.til_broker_host);
        tilBrokerPort = findViewById(R.id.til_broker_port);
        etBrokerHost  = findViewById(R.id.et_broker_host);
        etBrokerPort  = findViewById(R.id.et_broker_port);
        etUsername    = findViewById(R.id.et_username);
        etPassword    = findViewById(R.id.et_password);
        btnSave       = findViewById(R.id.btn_save_mqtt);

        loadSettings();
        btnSave.setOnClickListener(v -> saveSettings());
    }

    private void loadSettings() {
        SharedPreferences p = getSharedPreferences(PREFS_MQTT, Context.MODE_PRIVATE);
        etBrokerHost.setText(p.getString(KEY_BROKER_HOST, ""));
        int savedPort = p.getInt(KEY_BROKER_PORT, 1883);
        etBrokerPort.setText(savedPort > 0 ? String.valueOf(savedPort) : "");
        etUsername.setText(p.getString(KEY_USERNAME, ""));
        etPassword.setText(p.getString(KEY_PASSWORD, ""));
    }

    private void saveSettings() {
        tilBrokerHost.setError(null);
        tilBrokerPort.setError(null);

        String host     = getText(etBrokerHost);
        String portS    = getText(etBrokerPort);
        String username = getText(etUsername);
        String password = getText(etPassword);

        boolean hasError = false;

        if (TextUtils.isEmpty(host)) {
            tilBrokerHost.setError("Required");
            etBrokerHost.requestFocus();
            hasError = true;
        }

        int port = 1883;
        if (TextUtils.isEmpty(portS)) {
            tilBrokerPort.setError("Required");
            if (!hasError) { etBrokerPort.requestFocus(); hasError = true; }
        } else {
            try {
                port = Integer.parseInt(portS);
                if (port <= 0 || port > 65535) {
                    tilBrokerPort.setError("1–65535");
                    if (!hasError) { etBrokerPort.requestFocus(); hasError = true; }
                }
            } catch (NumberFormatException e) {
                tilBrokerPort.setError("Invalid");
                if (!hasError) { etBrokerPort.requestFocus(); hasError = true; }
            }
        }

        if (hasError) return;

        SharedPreferences.Editor e =
                getSharedPreferences(PREFS_MQTT, Context.MODE_PRIVATE).edit();
        e.putString(KEY_BROKER_HOST, host);
        e.putInt(KEY_BROKER_PORT,    port);
        e.putString(KEY_USERNAME,    username);
        e.putString(KEY_PASSWORD,    password);
        e.apply();

        Toast.makeText(this, "MQTT settings saved!", Toast.LENGTH_SHORT).show();
        finish();
    }


    public static String extractDeviceTypeKey(String relationDeviceName) {
        if (relationDeviceName == null || relationDeviceName.isEmpty()) return "";
        String[] parts = relationDeviceName.split("_");
        if (parts.length >= 2) return parts[1].trim().toLowerCase();
        return "";
    }

    /**
     * Returns ON value for given type key.
     * s=250, st=250, d=100, f=10, ex=4, b=100, r=1
     */
    public static String getOnValueForType(String typeKey) {
        switch (typeKey) {
            case "s":  return "250";
            case "st": return "250";
            case "d":  return "100";
            case "f":  return "10";
            case "ex": return "4";
            case "b":  return "100";
            case "r":  return "1";
            default:   return "1"; // fallback
        }
    }

    /**
     * Main entry point — pass relationDeviceName directly.
     * Replaces old getOnValue(prefs, deviceCode).
     */
    public static String getOnValue(SharedPreferences prefs, String relationDeviceName) {
        String typeKey = extractDeviceTypeKey(relationDeviceName);
        return getOnValueForType(typeKey);
    }

    public static String buildOnCommand(String elementId, String onValue) {
        return "#*2*" + elementId + "*2*" + onValue + "*#";
    }

    public static String buildOffCommand(String elementId) {
        return "#*2*" + elementId + "*2*0*#";
    }

    private String getText(TextInputEditText et) {
        return et.getText() != null ? et.getText().toString().trim() : "";
    }
}