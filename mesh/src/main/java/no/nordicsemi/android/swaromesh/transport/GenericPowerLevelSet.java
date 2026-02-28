package no.nordicsemi.android.swaromesh.transport;

import androidx.annotation.NonNull;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import no.nordicsemi.android.swaromesh.ApplicationKey;
import no.nordicsemi.android.swaromesh.logger.MeshLogger;
import no.nordicsemi.android.swaromesh.opcodes.ApplicationMessageOpCodes;
import no.nordicsemi.android.swaromesh.utils.SecureUtils;

/**
 * Generic Power Level Set (Acknowledged)
 *
 * Parameters:
 *  - Power Level (uint16)
 *  - TID (uint8)
 *  - Command (uint8)
 */
@SuppressWarnings("unused")
public class GenericPowerLevelSet extends ApplicationMessage {

    private static final String TAG = GenericPowerLevelSet.class.getSimpleName();
    private static final int OP_CODE =
            ApplicationMessageOpCodes.GENERIC_POWER_LEVEL_SET;

    // power(2) + tid(1) + command(1) = 4 bytes
    private static final int PARAMS_LENGTH = 4;
    private final int mCommand;
    private final int mState;
    private final int mTid;


    /**
     * Constructor (NO transition params)
     *
     * @param appKey     {@link ApplicationKey}
     * @param command    Custom command ID
     * @param state      State Level (0–65535)
     * @param tid        Transaction ID
     *
     */
    public GenericPowerLevelSet(@NonNull final ApplicationKey appKey,
                                final int command,
                                final int state,
                                final int tid
    ) {

        super(appKey);

        if (state < 0 || state > 0xFFFF) {
            throw new IllegalArgumentException(
                    "Generic power level must be between 0 and 65535");
        }

        this.mCommand = command;
        this.mState = state;
        this.mTid = tid;


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

        buffer.put((byte) mCommand);          // uint8
        buffer.putShort((short) mState); // uint16
        buffer.put((byte) mTid);              // uint8


        mParameters = buffer.array();
    }
}
