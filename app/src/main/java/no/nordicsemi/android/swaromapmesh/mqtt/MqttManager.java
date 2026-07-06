package no.nordicsemi.android.swaromapmesh.mqtt;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.inject.Inject;
import javax.inject.Singleton;

import dagger.hilt.android.qualifiers.ApplicationContext;
import no.nordicsemi.android.swaromapmesh.MqttSettingsActivity;

@Singleton
public class MqttManager {
    private static final String TAG = "MqttManager";
    private final Context context;
    private MqttClient mqttClient;
    private final MutableLiveData<Boolean> isConnected = new MutableLiveData<>(false);
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private boolean isConnecting = false;

    @Inject
    public MqttManager(@ApplicationContext Context context) {
        this.context = context.getApplicationContext();
        connect();
    }

    public LiveData<Boolean> getIsConnected() {
        return isConnected;
    }

    public synchronized void connect() {
        if (isConnecting) {
            Log.d(TAG, "MQTT: Connection already in progress");
            return;
        }
        
        Log.d(TAG, "MQTT: connect() called");
        isConnecting = true;
        isConnected.postValue(false);

        executor.execute(() -> {
            try {
                SharedPreferences prefs = context.getSharedPreferences(MqttSettingsActivity.PREFS_MQTT, Context.MODE_PRIVATE);
                String host = prefs.getString(MqttSettingsActivity.KEY_BROKER_HOST, "192.168.1.200");
                int port = prefs.getInt(MqttSettingsActivity.KEY_BROKER_PORT, 1883);
                String username = prefs.getString(MqttSettingsActivity.KEY_USERNAME, "Swajahome");
                String password = prefs.getString(MqttSettingsActivity.KEY_PASSWORD, "12345678");

                if (host == null || host.trim().isEmpty()) {
                    Log.d(TAG, "MQTT: Host is empty");
                    isConnecting = false;
                    return;
                }

                if (mqttClient != null) {
                    try {
                        if (mqttClient.isConnected()) {
                            mqttClient.disconnectForcibly();
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "MQTT: Error cleaning old client", e);
                    }
                    mqttClient = null;
                }

                String brokerUri = "tcp://" + host + ":" + port;
                String clientId = MqttClient.generateClientId();
                mqttClient = new MqttClient(brokerUri, clientId, new MemoryPersistence());
                
                MqttConnectOptions opts = new MqttConnectOptions();
                if (username != null && !username.isEmpty()) {
                    opts.setUserName(username);
                    if (password != null) {
                        opts.setPassword(password.toCharArray());
                    }
                }
                opts.setAutomaticReconnect(true);
                opts.setCleanSession(true);
                opts.setConnectionTimeout(10);
                opts.setKeepAliveInterval(60);

                mqttClient.setCallback(new MqttCallback() {
                    @Override
                    public void connectionLost(Throwable cause) {
                        Log.e(TAG, "MQTT: Connection lost");
                        isConnected.postValue(false);
                    }

                    @Override
                    public void messageArrived(String topic, MqttMessage message) {
                        Log.d(TAG, "MQTT: Msg arrived on " + topic);
                    }

                    @Override
                    public void deliveryComplete(IMqttDeliveryToken token) {}
                });

                Log.d(TAG, "MQTT: Connecting to " + brokerUri);
                mqttClient.connect(opts);
                isConnected.postValue(true);
                Log.d(TAG, "MQTT: Connected successfully");

            } catch (MqttException e) {
                Log.e(TAG, "MQTT: Connect failed: " + e.getMessage());
                isConnected.postValue(false);
            } catch (Exception e) {
                Log.e(TAG, "MQTT: Unexpected error", e);
                isConnected.postValue(false);
            } finally {
                isConnecting = false;
            }
        });
    }

    public synchronized void disconnect() {
        executor.execute(() -> {
            try {
                if (mqttClient != null && mqttClient.isConnected()) {
                    mqttClient.disconnect();
                }
            } catch (MqttException e) {
                Log.e(TAG, "MQTT: Disconnect error", e);
            } finally {
                isConnected.postValue(false);
            }
        });
    }

    public void publish(String topic, String payload) {
        if (topic == null || payload == null) return;
        
        executor.execute(() -> {
            if (mqttClient != null && mqttClient.isConnected()) {
                try {
                    mqttClient.publish(topic, new MqttMessage(payload.getBytes()));
                    Log.d(TAG, "MQTT: Published to " + topic);
                } catch (MqttException e) {
                    Log.e(TAG, "MQTT: Publish failed", e);
                }
            } else {
                Log.w(TAG, "MQTT: Not connected, cannot publish");
            }
        });
    }
}
