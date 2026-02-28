package no.nordicsemi.android.swaromesh.control;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import no.nordicsemi.android.swaromesh.Features;
import no.nordicsemi.android.swaromesh.logger.MeshLogger;
import no.nordicsemi.android.swaromesh.transport.ControlMessage;
import no.nordicsemi.android.swaromesh.utils.DeviceFeatureUtils;
import no.nordicsemi.android.swaromesh.utils.MeshAddress;

/**
 * Heartbeat message
 */
public class HeartbeatMessage extends TransportControlMessage {

    private static final String TAG = HeartbeatMessage.class.getSimpleName();

    private int initTtl;
    private Features features;

    /**
     * Constructs the Heartbeat message
     *
     * @param message control message
     */
    public HeartbeatMessage(final ControlMessage message) {
        MeshLogger.verbose(TAG, "Received Heartbeat message from: " + MeshAddress.formatAddress(message.getSrc(), false));
        final ByteBuffer buffer = ByteBuffer.wrap(message.getTransportControlPdu()).order(ByteOrder.BIG_ENDIAN);
        this.initTtl = buffer.get();
        final int featuresInt = buffer.getShort();
        this.features = new Features(DeviceFeatureUtils.getFriendFeature(featuresInt),
                DeviceFeatureUtils.getLowPowerFeature(featuresInt),
                DeviceFeatureUtils.getProxyFeature(featuresInt),
                DeviceFeatureUtils.getRelayFeature(featuresInt));
        MeshLogger.verbose(TAG, "Initial TTL: " + initTtl);
        MeshLogger.verbose(TAG, "Features: " + features);
    }

    public int getInitTtl() {
        return initTtl;
    }

    public Features getFeatures() {
        return features;
    }

    @Override
    public TransportControlMessageState getState() {
        return TransportControlMessageState.LOWER_TRANSPORT_HEARTBEAT_MESSAGE;
    }
}
