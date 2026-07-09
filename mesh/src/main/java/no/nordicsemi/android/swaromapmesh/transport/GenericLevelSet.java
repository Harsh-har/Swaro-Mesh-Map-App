package no.nordicsemi.android.swaromapmesh.transport;

import no.nordicsemi.android.swaromapmesh.logger.MeshLogger;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import androidx.annotation.NonNull;
import no.nordicsemi.android.swaromapmesh.ApplicationKey;
import no.nordicsemi.android.swaromapmesh.opcodes.ApplicationMessageOpCodes;
import no.nordicsemi.android.swaromapmesh.utils.SecureUtils;

/**
 * To be used as a wrapper class when creating a GenericLevelSet message.
 */
@SuppressWarnings("unused")
public class GenericLevelSet extends ApplicationMessage {

    private static final String TAG = GenericLevelSet.class.getSimpleName();
    private static final int OP_CODE = ApplicationMessageOpCodes.GENERIC_LEVEL_SET;

    // level (2 bytes) + tid (1 byte) + command (1 byte)
    private static final int GENERIC_LEVEL_SET_PARAMS_LENGTH = 4;
    private final int mCommand;
    private final int mState;
    private final int tId;


    /**
     * Constructs GenericLevelSet message.
     *
     * @param appKey  {@link ApplicationKey} key for this message
     * @param command Command ID
     * @param state   Level value (-32768 to 32767)
     * @param tId     Transaction ID
     *
     */
    public GenericLevelSet(@NonNull final ApplicationKey appKey,
                           final int command,
                           final int state,
                           final int tId
    ) {
        super(appKey);

        if (state < Short.MIN_VALUE || state > Short.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "Generic level value must be between -32768 to 32767"
            );
        }

        this.mCommand = command;
        this.mState = state;
        this.tId = tId;


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
        MeshLogger.verbose(TAG, "Level: " + mState);
        MeshLogger.verbose(TAG, "TID: " + tId);


        ByteBuffer paramsBuffer = ByteBuffer
                .allocate(GENERIC_LEVEL_SET_PARAMS_LENGTH)
                .order(ByteOrder.LITTLE_ENDIAN);

        paramsBuffer.put((byte) mCommand);      // 1 byte
        paramsBuffer.putShort((short) mState); // 2 bytes
        paramsBuffer.put((byte) tId);          // 1 byte


        mParameters = paramsBuffer.array();
    }
}
