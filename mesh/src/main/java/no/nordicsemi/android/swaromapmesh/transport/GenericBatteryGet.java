package no.nordicsemi.android.swaromapmesh.transport;


import androidx.annotation.NonNull;
import no.nordicsemi.android.swaromapmesh.ApplicationKey;
import no.nordicsemi.android.swaromapmesh.opcodes.ApplicationMessageOpCodes;
import no.nordicsemi.android.swaromapmesh.utils.SecureUtils;

public class GenericBatteryGet extends ApplicationMessage {

    private static final String TAG = GenericBatteryGet.class.getSimpleName();
    private static final int OP_CODE = ApplicationMessageOpCodes.GENERIC_BATTERY_GET;

    /**
     * Constructs a Generic Battery Get message
     *
     * @param appKey application key
     */
    public GenericBatteryGet(@NonNull ApplicationKey appKey) {
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
