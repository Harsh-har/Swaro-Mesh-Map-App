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
    public static final String KEY_PUBLISH_TOPIC = "publish_topic";

    // Only ON values stored — element ID comes from intent at runtime
    public static final String KEY_RL01_ON_VALUE  = "rl01_on_value";
    public static final String KEY_RL02_ON_VALUE  = "rl02_on_value";
    public static final String KEY_RL03_ON_VALUE  = "rl03_on_value";
    public static final String KEY_CLF01_ON_VALUE = "clf01_on_value";
    public static final String KEY_CLE02_ON_VALUE = "cle02_on_value";
    public static final String KEY_CLC03_ON_VALUE = "clc03_on_value";

    public static final String DEVICE_RL01  = "SW-RL01-006";
    public static final String DEVICE_RL02  = "SW-RL02-012";
    public static final String DEVICE_RL03  = "SW-RL03-016";
    public static final String DEVICE_CLF01 = "Relay Node";
    public static final String DEVICE_CLE02 = "SW-CLE02-050";
    public static final String DEVICE_CLC03 = "SW-CLC03-150";

    private TextInputLayout   tilBrokerHost, tilBrokerPort, tilPublishTopic;
    private TextInputEditText etBrokerHost, etBrokerPort, etPublishTopic;
    private TextInputEditText etUsername, etPassword;

    private TextInputLayout   tilRl01OnValue, tilRl02OnValue, tilRl03OnValue;
    private TextInputLayout   tilClf01OnValue, tilCle02OnValue, tilClc03OnValue;

    private TextInputEditText etRl01OnValue, etRl02OnValue, etRl03OnValue;
    private TextInputEditText etClf01OnValue, etCle02OnValue, etClc03OnValue;

    private MaterialButton btnSave;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_mqtt_settings);

        tilBrokerHost   = findViewById(R.id.til_broker_host);
        tilBrokerPort   = findViewById(R.id.til_broker_port);
        tilPublishTopic = findViewById(R.id.til_publish_topic);
        etBrokerHost    = findViewById(R.id.et_broker_host);
        etBrokerPort    = findViewById(R.id.et_broker_port);
        etPublishTopic  = findViewById(R.id.et_publish_topic);
        etUsername      = findViewById(R.id.et_username);
        etPassword      = findViewById(R.id.et_password);


        btnSave = findViewById(R.id.btn_save_mqtt);

        loadSettings();
        btnSave.setOnClickListener(v -> saveSettings());
    }

    private void loadSettings() {
        SharedPreferences p = getSharedPreferences(PREFS_MQTT, Context.MODE_PRIVATE);

        etBrokerHost.setText(p.getString(KEY_BROKER_HOST, ""));
        int savedPort = p.getInt(KEY_BROKER_PORT, 1883);
        etBrokerPort.setText(savedPort > 0 ? String.valueOf(savedPort) : "");
        etPublishTopic.setText(p.getString(KEY_PUBLISH_TOPIC, "mesh/device/cmd"));
        etUsername.setText(p.getString(KEY_USERNAME, ""));
        etPassword.setText(p.getString(KEY_PASSWORD, ""));

        etRl01OnValue.setText(p.getString(KEY_RL01_ON_VALUE, ""));
        etRl02OnValue.setText(p.getString(KEY_RL02_ON_VALUE, ""));
        etRl03OnValue.setText(p.getString(KEY_RL03_ON_VALUE, ""));
        etClf01OnValue.setText(p.getString(KEY_CLF01_ON_VALUE, ""));
        etCle02OnValue.setText(p.getString(KEY_CLE02_ON_VALUE, ""));
        etClc03OnValue.setText(p.getString(KEY_CLC03_ON_VALUE, ""));
    }

    private void saveSettings() {
        tilBrokerHost.setError(null);
        tilBrokerPort.setError(null);
        tilPublishTopic.setError(null);
        tilRl01OnValue.setError(null);
        tilRl02OnValue.setError(null);
        tilRl03OnValue.setError(null);
        tilClf01OnValue.setError(null);
        tilCle02OnValue.setError(null);
        tilClc03OnValue.setError(null);

        String host  = getText(etBrokerHost);
        String portS = getText(etBrokerPort);
        String topic = getText(etPublishTopic);

        String rl01On  = getText(etRl01OnValue);
        String rl02On  = getText(etRl02OnValue);
        String rl03On  = getText(etRl03OnValue);
        String clf01On = getText(etClf01OnValue);
        String cle02On = getText(etCle02OnValue);
        String clc03On = getText(etClc03OnValue);

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

        if (TextUtils.isEmpty(topic)) {
            tilPublishTopic.setError("Required");
            if (!hasError) { etPublishTopic.requestFocus(); hasError = true; }
        }

        if (TextUtils.isEmpty(rl01On)) {
            tilRl01OnValue.setError("Required");
            if (!hasError) { etRl01OnValue.requestFocus(); hasError = true; }
        }
        if (TextUtils.isEmpty(rl02On)) {
            tilRl02OnValue.setError("Required");
            if (!hasError) { etRl02OnValue.requestFocus(); hasError = true; }
        }
        if (TextUtils.isEmpty(rl03On)) {
            tilRl03OnValue.setError("Required");
            if (!hasError) { etRl03OnValue.requestFocus(); hasError = true; }
        }
        if (TextUtils.isEmpty(clf01On)) {
            tilClf01OnValue.setError("Required");
            if (!hasError) { etClf01OnValue.requestFocus(); hasError = true; }
        }
        if (TextUtils.isEmpty(cle02On)) {
            tilCle02OnValue.setError("Required");
            if (!hasError) { etCle02OnValue.requestFocus(); hasError = true; }
        }
        if (TextUtils.isEmpty(clc03On)) {
            tilClc03OnValue.setError("Required");
            if (!hasError) { etClc03OnValue.requestFocus(); hasError = true; }
        }

        if (hasError) return;

        SharedPreferences.Editor e =
                getSharedPreferences(PREFS_MQTT, Context.MODE_PRIVATE).edit();
        e.putString(KEY_BROKER_HOST,   host);
        e.putInt(KEY_BROKER_PORT,      port);
        e.putString(KEY_PUBLISH_TOPIC, topic);
        e.putString(KEY_USERNAME,      username);
        e.putString(KEY_PASSWORD,      password);
        e.putString(KEY_RL01_ON_VALUE,  rl01On);
        e.putString(KEY_RL02_ON_VALUE,  rl02On);
        e.putString(KEY_RL03_ON_VALUE,  rl03On);
        e.putString(KEY_CLF01_ON_VALUE, clf01On);
        e.putString(KEY_CLE02_ON_VALUE, cle02On);
        e.putString(KEY_CLC03_ON_VALUE, clc03On);
        e.apply();

        Toast.makeText(this, "MQTT settings saved!", Toast.LENGTH_SHORT).show();
        finish();
    }

    // ── Called from TestProvisionActivity ─────────────────────────────────────
    // Returns saved ON value for a given deviceCode, or "" if not found
    public static String getOnValue(SharedPreferences prefs, String deviceCode) {
        switch (deviceCode) {
            case DEVICE_RL01:  return prefs.getString(KEY_RL01_ON_VALUE, "");
            case DEVICE_RL02:  return prefs.getString(KEY_RL02_ON_VALUE, "");
            case DEVICE_RL03:  return prefs.getString(KEY_RL03_ON_VALUE, "");
            case DEVICE_CLF01: return prefs.getString(KEY_CLF01_ON_VALUE, "");
            case DEVICE_CLE02: return prefs.getString(KEY_CLE02_ON_VALUE, "");
            case DEVICE_CLC03: return prefs.getString(KEY_CLC03_ON_VALUE, "");
            default:           return "";
        }
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