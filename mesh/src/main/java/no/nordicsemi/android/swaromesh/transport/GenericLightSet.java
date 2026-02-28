package no.nordicsemi.android.swaromesh.transport;

import androidx.annotation.NonNull;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

import no.nordicsemi.android.swaromesh.ApplicationKey;
import no.nordicsemi.android.swaromesh.opcodes.ApplicationMessageOpCodes;
import no.nordicsemi.android.swaromesh.utils.SecureUtils;
import no.nordicsemi.android.swaromesh.logger.MeshLogger;

public class GenericLightSet extends ApplicationMessage {

    private static final String TAG = GenericLightSet.class.getSimpleName();
    private static final int OP_CODE = ApplicationMessageOpCodes.GENERIC_LIGHT_CONTROL_OPCODE;

    // Length range from 0-255
    private static final int MIN_LENGTH = 0;
    private static final int MAX_LENGTH = 255;

    // ✅ Always expect 8 brightness values
    private static final int EXPECTED_BRIGHTNESS_COUNT = 8;

    private static final int MIN_VALUE = 0;
    private static final int MAX_VALUE = 255;

    private final int length;
    private final int command;
    private final int[] brightness; // Always 8 values
    private final int tid;

    /**
     * Message Structure (VARIABLE LENGTH but brightness always 8 bytes):
     *
     * Byte 0  : length (0-255) - indicates how many brightness values are actually used
     * Byte 1  : command
     * Byte 2-9: brightness[8] (always 8 bytes)
     * Byte 10 : tid
     *
     * Total size = 1 + 1 + 8 + 1 = 11 bytes
     */
    public GenericLightSet(@NonNull final ApplicationKey appKey,
                           final int length,
                           final int command,
                           @NonNull final int[] brightness,
                           final int tid) {
        super(appKey);

        validate(length, command, brightness, tid);

        this.length = length;
        this.command = command;
        this.tid = tid;

        // ✅ Always store all 8 brightness values
        if (brightness.length >= EXPECTED_BRIGHTNESS_COUNT) {
            this.brightness = Arrays.copyOf(brightness, EXPECTED_BRIGHTNESS_COUNT);
        } else {
            throw new IllegalArgumentException("Brightness array must have at least " +
                    EXPECTED_BRIGHTNESS_COUNT + " values");
        }

        assembleMessageParameters();
    }

    private void validate(final int length,
                          final int command,
                          final int[] brightness,
                          final int tid) {

        // Validate length range 0-255
        if (length < MIN_LENGTH || length > MAX_LENGTH) {
            throw new IllegalArgumentException("Invalid length: " + length + " (must be 0–255)");
        }

        if (command < MIN_VALUE || command > MAX_VALUE) {
            throw new IllegalArgumentException("Invalid command: " + command + " (must be 0–255)");
        }

        // ✅ Validate that we have exactly 8 brightness values
        if (brightness == null || brightness.length < EXPECTED_BRIGHTNESS_COUNT) {
            throw new IllegalArgumentException("Brightness array must have " +
                    EXPECTED_BRIGHTNESS_COUNT + " values");
        }

        // Validate all brightness values are in range 0-255
        for (int i = 0; i < EXPECTED_BRIGHTNESS_COUNT; i++) {
            if (brightness[i] < MIN_VALUE || brightness[i] > MAX_VALUE) {
                throw new IllegalArgumentException(
                        "Invalid brightness[" + i + "] = " + brightness[i] + " (must be 0–255)");
            }
        }

        if (tid < MIN_VALUE || tid > MAX_VALUE) {
            throw new IllegalArgumentException("Invalid TID: " + tid + " (must be 0–255)");
        }
    }

    @Override
    public int getOpCode() {
        return OP_CODE;
    }

    public int getLength() {
        return length;
    }

    public int getCommand() {
        return command;
    }

    public int[] getBrightness() {
        return Arrays.copyOf(brightness, EXPECTED_BRIGHTNESS_COUNT);
    }

    public int getTid() {
        return tid;
    }

    public int getMessageSize() {
        // Size = length byte + command byte + 8 brightness bytes + tid byte
        return 1 + 1 + EXPECTED_BRIGHTNESS_COUNT + 1;
    }

    @Override
    void assembleMessageParameters() {

        mAid = SecureUtils.calculateK4(mAppKey.getKey());

        final ByteBuffer buffer = ByteBuffer
                .allocate(getMessageSize())
                .order(ByteOrder.LITTLE_ENDIAN);

        buffer.put((byte) length);
        buffer.put((byte) command);

        // ✅ Always add all 8 brightness bytes
        for (int i = 0; i < EXPECTED_BRIGHTNESS_COUNT; i++) {
            buffer.put((byte) brightness[i]);
        }

        buffer.put((byte) tid);

        mParameters = buffer.array();

        logMessageParameters();
    }

    private void logMessageParameters() {
        MeshLogger.verbose(TAG,
                "Len=" + length +
                        ", Cmd=" + command +
                        " (0x" + String.format("%02X", command) + ")" +
                        ", Brightness=" + Arrays.toString(brightness) +
                        ", TID=" + tid +
                        " (0x" + String.format("%02X", tid) + ")"
        );
    }

    public byte[] toByteArray() {
        return Arrays.copyOf(mParameters, mParameters.length);
    }

    public static GenericLightSet fromByteArray(@NonNull ApplicationKey appKey,
                                                @NonNull byte[] data) {

        // ✅ Expect exactly 11 bytes (1 length + 1 command + 8 brightness + 1 tid)
        if (data.length != 11) {
            throw new IllegalArgumentException("Invalid data length: " + data.length +
                    " (expected 11 bytes)");
        }

        int length = data[0] & 0xFF;
        int command = data[1] & 0xFF;

        // Read all 8 brightness values
        int[] brightness = new int[EXPECTED_BRIGHTNESS_COUNT];
        for (int i = 0; i < EXPECTED_BRIGHTNESS_COUNT; i++) {
            brightness[i] = data[2 + i] & 0xFF;
        }

        int tid = data[10] & 0xFF; // Last byte (index 10)

        return new GenericLightSet(appKey, length, command, brightness, tid);
    }

    @Override
    public String toString() {
        return "GenericLightSet{" +
                "length=" + length +
                ", command=" + command +
                ", brightness=" + Arrays.toString(brightness) +
                ", tid=" + tid +
                '}';
    }
}