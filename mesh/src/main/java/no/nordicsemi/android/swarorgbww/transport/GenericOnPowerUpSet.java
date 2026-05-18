package no.nordicsemi.android.swarorgbww.transport;

import androidx.annotation.NonNull;

import no.nordicsemi.android.swarorgbww.ApplicationKey;
import no.nordicsemi.android.swarorgbww.data.OnPowerUpState;
import no.nordicsemi.android.swarorgbww.opcodes.ApplicationMessageOpCodes;
import no.nordicsemi.android.swarorgbww.utils.ArrayUtils;
import no.nordicsemi.android.swarorgbww.utils.BitWriter;
import no.nordicsemi.android.swarorgbww.utils.SecureUtils;

public class GenericOnPowerUpSet extends ApplicationMessage {

    private static final int OP_CODE = ApplicationMessageOpCodes.GENERIC_ON_POWER_UP_SET;
    private static final int GENERIC_ON_POWER_UP_SET_PARAMS_LENGTH = 8;

    private final OnPowerUpState onPowerUpState;

    public GenericOnPowerUpSet(@NonNull ApplicationKey appKey, OnPowerUpState onPowerUpState) {
        super(appKey);
        this.onPowerUpState = onPowerUpState;
        assembleMessageParameters();
    }

    @Override
    void assembleMessageParameters() {
        mAid = SecureUtils.calculateK4(mAppKey.getKey());
        BitWriter bitWriter = new BitWriter(GENERIC_ON_POWER_UP_SET_PARAMS_LENGTH);
        bitWriter.write(onPowerUpState.getValue(), GENERIC_ON_POWER_UP_SET_PARAMS_LENGTH);
        mParameters = ArrayUtils.reverseArray(bitWriter.toByteArray());
    }

    @Override
    public int getOpCode() {
        return OP_CODE;
    }
}
