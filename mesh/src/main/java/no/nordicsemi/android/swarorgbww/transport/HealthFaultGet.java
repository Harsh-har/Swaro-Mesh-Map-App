package no.nordicsemi.android.swarorgbww.transport;

import androidx.annotation.NonNull;

import no.nordicsemi.android.swarorgbww.ApplicationKey;
import no.nordicsemi.android.swarorgbww.logger.MeshLogger;
import no.nordicsemi.android.swarorgbww.opcodes.ApplicationMessageOpCodes;
import no.nordicsemi.android.swarorgbww.utils.SecureUtils;

public class HealthFaultGet extends ApplicationMessage {

    private static final String TAG = HealthFaultGet.class.getSimpleName();
    private static final int OP_CODE = ApplicationMessageOpCodes.HEALTH_FAULT_GET;
    private final int mCompanyId;

    /**
     * Constructs HealthFaultGet message
     */
    public HealthFaultGet(@NonNull final ApplicationKey appKey, final int companyId) {
        super(appKey);
        this.mCompanyId = companyId;
        assembleMessageParameters();
    }

    @Override
    public int getOpCode() {
        return OP_CODE;
    }

    @Override
    void assembleMessageParameters() {
        MeshLogger.verbose(TAG, "Company ID: " + mCompanyId);
        mAid = SecureUtils.calculateK4(mAppKey.getKey());
        mParameters = new byte[]{(byte) mCompanyId, (byte) (mCompanyId >> 8)};
    }
}
