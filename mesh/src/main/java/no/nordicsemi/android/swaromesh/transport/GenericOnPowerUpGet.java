package no.nordicsemi.android.swaromesh.transport;

import androidx.annotation.NonNull;

import no.nordicsemi.android.swaromesh.ApplicationKey;
import no.nordicsemi.android.swaromesh.opcodes.ApplicationMessageOpCodes;
import no.nordicsemi.android.swaromesh.utils.SecureUtils;

public class GenericOnPowerUpGet extends ApplicationMessage {

    private static final int OP_CODE = ApplicationMessageOpCodes.GENERIC_ON_POWER_UP_GET;

    public GenericOnPowerUpGet(@NonNull ApplicationKey appKey) {
        super(appKey);
        assembleMessageParameters();
    }

    @Override
    void assembleMessageParameters() {
        mAid = SecureUtils.calculateK4(mAppKey.getKey());
    }

    @Override
    public int getOpCode() {
        return OP_CODE;
    }
}
