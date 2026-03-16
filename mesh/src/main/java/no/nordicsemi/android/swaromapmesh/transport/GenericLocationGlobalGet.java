package no.nordicsemi.android.swaromapmesh.transport;


import no.nordicsemi.android.swaromapmesh.logger.MeshLogger;

import androidx.annotation.NonNull;

import no.nordicsemi.android.swaromapmesh.ApplicationKey;
import no.nordicsemi.android.swaromapmesh.opcodes.ApplicationMessageOpCodes;
import no.nordicsemi.android.swaromapmesh.utils.SecureUtils;

/**
 * To be used as a wrapper class when creating a GenericLocationGlobalGet message.
 */
public class GenericLocationGlobalGet extends ApplicationMessage {

    private static final String TAG = GenericLocationGlobalGet.class.getSimpleName();
    private static final int OP_CODE = ApplicationMessageOpCodes.GENERIC_LOCATION_GLOBAL_GET;

    /**
     * Constructs GenericLocationGlobalGet message.
     *
     * @param appKey application key for this message
     * @throws IllegalArgumentException if any illegal arguments are passed
     */
    public GenericLocationGlobalGet(@NonNull final ApplicationKey appKey) throws IllegalArgumentException {
        super(appKey);
        assembleMessageParameters();
    }

    @Override
    public int getOpCode() {
        return OP_CODE;
    }

    @Override
    void assembleMessageParameters() {
        MeshLogger.verbose(TAG, "Creating message");
        mAid = SecureUtils.calculateK4(mAppKey.getKey());
    }
}
