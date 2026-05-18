package no.nordicsemi.android.swarorgbww.transport;

import androidx.annotation.NonNull;

import no.nordicsemi.android.swarorgbww.ApplicationKey;
import no.nordicsemi.android.swarorgbww.opcodes.ApplicationMessageOpCodes;
import no.nordicsemi.android.swarorgbww.utils.SecureUtils;

public class TimeGet extends ApplicationMessage {

    public TimeGet(@NonNull final ApplicationKey appKey) {
        super(appKey);
        assembleMessageParameters();
    }

    @Override
    void assembleMessageParameters() {
        mAid = SecureUtils.calculateK4(mAppKey.getKey());
    }

    @Override
    public int getOpCode() {
        return ApplicationMessageOpCodes.TIME_GET;
    }
}
