package no.nordicsemi.android.swaromapmesh.transport;

import androidx.annotation.NonNull;
import no.nordicsemi.android.swaromapmesh.ApplicationKey;
import no.nordicsemi.android.swaromapmesh.opcodes.ApplicationMessageOpCodes;
import no.nordicsemi.android.swaromapmesh.transport.SensorMessage.SensorCadence;
import no.nordicsemi.android.swaromapmesh.utils.SecureUtils;

/**
 * SensorCadenceSet message.
 */
public class SensorCadenceSetUnacknowledged extends ApplicationMessage {

    private static final String TAG = SensorCadenceSetUnacknowledged.class.getSimpleName();
    private static final int OP_CODE = ApplicationMessageOpCodes.SENSOR_CADENCE_SET_UNACKNOWLEDGED;

    private final SensorCadence cadence;

    /**
     * Constructs SensorCadenceSet message.
     *
     * @param appKey  {@link ApplicationKey} key for this message.
     * @param cadence {@link SensorCadence} Sensor Cadence.
     * @throws IllegalArgumentException if any illegal arguments are passed
     */
    public SensorCadenceSetUnacknowledged(@NonNull final ApplicationKey appKey,
                                          @NonNull final SensorCadence cadence) {
        super(appKey);
        this.cadence = cadence;
        assembleMessageParameters();
    }

    @Override
    public int getOpCode() {
        return OP_CODE;
    }

    @Override
    void assembleMessageParameters() {
        mAid = SecureUtils.calculateK4(mAppKey.getKey());
        mParameters = cadence.toBytes();
    }
}
