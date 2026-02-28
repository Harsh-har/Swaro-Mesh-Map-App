

package no.nordicsemi.android.swaromesh.transport;

import android.os.Parcel;
import android.os.Parcelable;
import no.nordicsemi.android.swaromesh.logger.MeshLogger;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import androidx.annotation.NonNull;
import no.nordicsemi.android.swaromesh.opcodes.ApplicationMessageOpCodes;
import no.nordicsemi.android.swaromesh.utils.MeshAddress;
import no.nordicsemi.android.swaromesh.utils.MeshParserUtils;

/**
 * To be used as a wrapper class for when creating the GenericOnOffStatus Message.
 */
@SuppressWarnings({"WeakerAccess"})
public final class GenericOnOffStatus extends ApplicationStatusMessage implements Parcelable {

    private static final String TAG = GenericOnOffStatus.class.getSimpleName();
    private static final int OP_CODE = ApplicationMessageOpCodes.GENERIC_ON_OFF_STATUS;
    private static final int GENERIC_ON_OFF_STATE_ON = 0x01;
    private boolean mPresentOn;
    private Boolean mTargetOn;
    private int mRemainingTime;
    private int mTransitionSteps;
    private int mTransitionResolution;

    private static final Creator<GenericOnOffStatus> CREATOR = new Creator<GenericOnOffStatus>() {
        @Override
        public GenericOnOffStatus createFromParcel(Parcel in) {
            final AccessMessage message = in.readParcelable(AccessMessage.class.getClassLoader());
            return new GenericOnOffStatus(message);
        }

        @Override
        public GenericOnOffStatus[] newArray(int size) {
            return new GenericOnOffStatus[size];
        }
    };

    /**
     * Constructs the GenericOnOffStatus mMessage.
     *
     * @param message Access Message
     */
    public GenericOnOffStatus(@NonNull final AccessMessage message) {
        super(message);
        this.mParameters = message.getParameters();
        parseStatusParameters();
    }

    @Override
    void parseStatusParameters() {
        MeshLogger.verbose(TAG, "Received generic on off status from: " +
                MeshAddress.formatAddress(mMessage.getSrc(), true));

        // FIXED: Added null check and handle empty parameters
        if (mParameters == null || mParameters.length == 0) {
            MeshLogger.error(TAG, "Empty status parameters");
            mPresentOn = false;
            mTargetOn = null;
            return;
        }

        final ByteBuffer buffer = ByteBuffer.wrap(mParameters).order(ByteOrder.LITTLE_ENDIAN);

        try {
            // First byte is always present state
            if (buffer.remaining() >= 1) {
                mPresentOn = buffer.get() == GENERIC_ON_OFF_STATE_ON;
                MeshLogger.verbose(TAG, "Present on: " + mPresentOn);
            }

            // FIXED: Check if we have target state (2nd byte)
            if (buffer.remaining() >= 1) {
                mTargetOn = buffer.get() == GENERIC_ON_OFF_STATE_ON;
                MeshLogger.verbose(TAG, "Target on: " + mTargetOn);

                // FIXED: Check if we have remaining time (3rd byte)
                if (buffer.remaining() >= 1) {
                    mRemainingTime = buffer.get() & 0xFF;
                    mTransitionSteps = (mRemainingTime & 0x3F);
                    mTransitionResolution = (mRemainingTime >> 6);
                    MeshLogger.verbose(TAG, "Remaining time, transition number of steps: " + mTransitionSteps);
                    MeshLogger.verbose(TAG, "Remaining time, transition number of step resolution: " + mTransitionResolution);
                    MeshLogger.verbose(TAG, "Remaining time: " + MeshParserUtils.getRemainingTime(mRemainingTime));
                }
            }
        } catch (Exception e) {
            MeshLogger.error(TAG, "Error parsing status parameters: " + e.getMessage());
            // Set defaults to avoid crashes
            mPresentOn = false;
            mTargetOn = null;
        }
    }

    @Override
    public int getOpCode() {
        return OP_CODE;
    }

    /**
     * Returns the present state of the GenericOnOffModel
     *
     * @return true if on and false other wise
     */
    public final boolean getPresentState() {
        return mPresentOn;
    }

    /**
     * Returns the target state of the GenericOnOffModel
     *
     * @return true if on and false other wise
     */
    public final Boolean getTargetState() {
        return mTargetOn;
    }

    /**
     * Returns the transition steps.
     *
     * @return transition steps
     */
    public int getTransitionSteps() {
        return mTransitionSteps;
    }

    /**
     * Returns the transition resolution.
     *
     * @return transition resolution
     */
    public int getTransitionResolution() {
        return mTransitionResolution;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(final Parcel dest, final int flags) {
        final AccessMessage message = (AccessMessage) mMessage;
        dest.writeParcelable(message, flags);
    }
}