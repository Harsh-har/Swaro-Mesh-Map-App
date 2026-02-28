package no.nordicsemi.android.swaromesh.transport;

import static no.nordicsemi.android.swaromesh.transport.TimeStatus.SUB_SECOND_BIT_SIZE;
import static no.nordicsemi.android.swaromesh.transport.TimeStatus.TAI_SECONDS_BIT_SIZE;
import static no.nordicsemi.android.swaromesh.transport.TimeStatus.TIME_AUTHORITY_BIT_SIZE;
import static no.nordicsemi.android.swaromesh.transport.TimeStatus.TIME_BIT_SIZE;
import static no.nordicsemi.android.swaromesh.transport.TimeStatus.TIME_ZONE_OFFSET_BIT_SIZE;
import static no.nordicsemi.android.swaromesh.transport.TimeStatus.TIME_ZONE_START_RANGE;
import static no.nordicsemi.android.swaromesh.transport.TimeStatus.UNCERTAINTY_BIT_SIZE;
import static no.nordicsemi.android.swaromesh.transport.TimeStatus.UTC_DELTA_BIT_SIZE;
import static no.nordicsemi.android.swaromesh.transport.TimeStatus.UTC_DELTA_START_RANGE;

import androidx.annotation.NonNull;

import no.nordicsemi.android.swaromesh.ApplicationKey;
import no.nordicsemi.android.swaromesh.MeshTAITime;
import no.nordicsemi.android.swaromesh.opcodes.ApplicationMessageOpCodes;
import no.nordicsemi.android.swaromesh.utils.ArrayUtils;
import no.nordicsemi.android.swaromesh.utils.BitWriter;
import no.nordicsemi.android.swaromesh.utils.SecureUtils;

public class TimeSet extends ApplicationMessage {

    private final MeshTAITime taiTime;

    /**
     * Time Set is an acknowledged message used to set the Time state of an element (see Section 5.1.1).
     * The response to the Time Set message is a Time Status message.
     *
     * @param taiTime The time in TAI format.
     */
    public TimeSet(@NonNull final ApplicationKey appKey, MeshTAITime taiTime) {
        super(appKey);
        this.taiTime = taiTime;
        assembleMessageParameters();
    }

    @Override
    void assembleMessageParameters() {
        mAid = SecureUtils.calculateK4(mAppKey.getKey());
        BitWriter bitWriter = new BitWriter(TIME_BIT_SIZE);

        // The state is a uint8 value representing the valid range of -64 through +191 (i.e., 0x40 represents a value of 0 and 0xFF represents a value of 191).
        bitWriter.write(taiTime.getTimeZoneOffset() + TIME_ZONE_START_RANGE, TIME_ZONE_OFFSET_BIT_SIZE);

        // The valid range is -255 through +32512 (i.e., 0x00FF represents a value of 0 and 0x7FFF represents a value of 32512).
        bitWriter.write(taiTime.getUtcDelta() + UTC_DELTA_START_RANGE, UTC_DELTA_BIT_SIZE);
        if (taiTime.isTimeAuthority()) {
            bitWriter.write(1, TIME_AUTHORITY_BIT_SIZE);
        } else {
            bitWriter.write(0, TIME_AUTHORITY_BIT_SIZE);
        }
        bitWriter.write(taiTime.getUncertainty(), UNCERTAINTY_BIT_SIZE);
        bitWriter.write(taiTime.getSubSecond(), SUB_SECOND_BIT_SIZE);
        bitWriter.write(taiTime.getTaiSeconds(), TAI_SECONDS_BIT_SIZE);

        mParameters = ArrayUtils.reverseArray(bitWriter.toByteArray());
    }

    @Override
    public int getOpCode() {
        return ApplicationMessageOpCodes.TIME_SET;
    }
}
