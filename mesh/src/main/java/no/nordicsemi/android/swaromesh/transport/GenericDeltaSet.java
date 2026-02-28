package no.nordicsemi.android.swaromesh.transport;

import androidx.annotation.NonNull;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import no.nordicsemi.android.swaromesh.ApplicationKey;
import no.nordicsemi.android.swaromesh.logger.MeshLogger;
import no.nordicsemi.android.swaromesh.opcodes.ApplicationMessageOpCodes;
import no.nordicsemi.android.swaromesh.utils.SecureUtils;

/**
 * Generic Level Delta Set (UNACKED style wrapper)
 *
 * Parameters:
 *  - Delta (int16)
 *  - TID (uint8)
 *  - Command (uint8)  <-- custom
 */
@SuppressWarnings("unused")
public class GenericDeltaSet extends ApplicationMessage {

    private static final String TAG = GenericDeltaSet.class.getSimpleName();
    private static final int OP_CODE = ApplicationMessageOpCodes.GENERIC_DELTA_SET;

    // delta(2) + tid(1) + command(1) = 4 bytes
    private static final int PARAMS_LENGTH = 4;
    private final int mCommand;
    private final int mState;
    private final int mTid;


    /**
     * Constructor (NO transition params)
     *
     * @param appKey   {@link ApplicationKey}
     * @param command  Custom command ID
     * @param state    Level delta value
     * @param tId      Transaction ID
     *
     */
    public GenericDeltaSet(@NonNull final ApplicationKey appKey,
                           final int command,
                           final int state,
                           final int tId
    ) {

        super(appKey);
        this.mCommand = command;
        this.mState = state;
        this.mTid = tId;

        assembleMessageParameters();
    }

    @Override
    public int getOpCode() {
        return OP_CODE;
    }

    @Override
    void assembleMessageParameters() {
        mAid = SecureUtils.calculateK4(mAppKey.getKey());

        MeshLogger.verbose(TAG, "Command: " + mCommand);
        MeshLogger.verbose(TAG, "State: " + mState);
        MeshLogger.verbose(TAG, "TID: " + mTid);


        final ByteBuffer buffer = ByteBuffer
                .allocate(PARAMS_LENGTH)
                .order(ByteOrder.LITTLE_ENDIAN);


        buffer.put((byte) mCommand);        // uint8
        buffer.putShort((short) mState);   // int16
        buffer.put((byte) mTid);            // uint8


        mParameters = buffer.array();
    }
}
