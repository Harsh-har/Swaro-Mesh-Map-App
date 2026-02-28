package no.nordicsemi.android.swaromesh.transport;

import androidx.annotation.NonNull;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import no.nordicsemi.android.swaromesh.ApplicationKey;
import no.nordicsemi.android.swaromesh.logger.MeshLogger;
import no.nordicsemi.android.swaromesh.opcodes.ApplicationMessageOpCodes;
import no.nordicsemi.android.swaromesh.utils.SecureUtils;

public class GenericOnOffSetUnacknowledged extends ApplicationMessage {

    private static final String TAG =
            GenericOnOffSetUnacknowledged.class.getSimpleName();

    private static final int OP_CODE =
            ApplicationMessageOpCodes.GENERIC_ON_OFF_SET_UNACKNOWLEDGED;

    // State (1) + TID (1) + Command (1)
    private static final int PARAM_LENGTH = 3;
    private final int mCommand;
    private final int mState;
    private final int mTid;


    public GenericOnOffSetUnacknowledged(
            @NonNull ApplicationKey appKey,
            int command,
            int state,
            int tid

    ) {
        super(appKey);
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


        ByteBuffer buffer = ByteBuffer
                .allocate(PARAM_LENGTH)
                .order(ByteOrder.LITTLE_ENDIAN);

        buffer.put((byte) mCommand);
        buffer.put((byte) mState);
        buffer.put((byte) mTid);


        mParameters = buffer.array();
    }
}
