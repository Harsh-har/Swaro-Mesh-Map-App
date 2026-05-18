package no.nordicsemi.android.swarorgbww.transport;


import androidx.annotation.NonNull;
import no.nordicsemi.android.swarorgbww.ApplicationKey;
import no.nordicsemi.android.swarorgbww.opcodes.ApplicationMessageOpCodes;
import no.nordicsemi.android.swarorgbww.utils.SecureUtils;

/**
 * To be used as a wrapper class when creating a GenericOnOffGet message.
 */
public class GenericOnOffGet extends ApplicationMessage {

    private static final String TAG = GenericOnOffGet.class.getSimpleName();
    private static final int OP_CODE = ApplicationMessageOpCodes.GENERIC_ON_OFF_GET;

    /**
     * Constructs GenericOnOffGet message.
     *
     * @param appKey application key for this message
     * @throws IllegalArgumentException if any illegal arguments are passed
     */
    public GenericOnOffGet(@NonNull final ApplicationKey appKey) throws IllegalArgumentException {
        super(appKey);
        assembleMessageParameters();
    }

    @Override
    public int getOpCode() {
        return OP_CODE;
    }

    @Override
    void assembleMessageParameters() {
        mAid = SecureUtils.calculateK4(mAppKey.getKey());
    }
}
