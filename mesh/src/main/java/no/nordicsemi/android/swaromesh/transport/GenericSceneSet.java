package no.nordicsemi.android.swaromesh.transport;

import androidx.annotation.NonNull;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import no.nordicsemi.android.swaromesh.ApplicationKey;
import no.nordicsemi.android.swaromesh.opcodes.ApplicationMessageOpCodes;
import no.nordicsemi.android.swaromesh.utils.SecureUtils;
import no.nordicsemi.android.swaromesh.logger.MeshLogger;

public class GenericSceneSet extends ApplicationMessage {

    private static final String TAG = GenericSceneSet.class.getSimpleName();

    // Opcode - match with firmware
    private static final int OP_CODE = ApplicationMessageOpCodes.GENERIC_BUTTON_OPCODE_STATUS;

    // Fixed 4-byte message size
    private static final int MESSAGE_SIZE = 4;

    // Press type constants (2 bits)
    public static final int PRESS_SINGLE  = 0;  // 00
    public static final int PRESS_DOUBLE  = 1;  // 01
    public static final int PRESS_LONG    = 2;  // 10
    public static final int PRESS_RELEASE = 3;  // 11

    // Range validations based on bit sizes
    private static final int SCENE_ID_MAX = 0xFF;        // 8 bits
    private static final int TYPE_MAX = 0x3F;            // 6 bits
    private static final int PRESS_MAX = 0x03;           // 2 bits
    private static final int MODE_MAX = 0x07;            // 3 bits
    private static final int DEVICE_MAX = 0x07;          // 3 bits
    private static final int STATE_MAX = 0x03;           // 2 bits
    private static final int TID_MAX = 0xFF;             // 8 bits

    // Fields
    private final int sceneId;   // 8 bits
    private final int type;      // 6 bits
    private final int press;     // 2 bits
    private final int mode;      // 3 bits
    private final int device;    // 3 bits
    private final int state;     // 2 bits
    private final int tid;       // 8 bits

    /**
     * Constructs a GenericSceneSet message with 4-byte structure matching C firmware:
     *
     * Byte 0: scene_id (8 bits)
     * Byte 1: [type:6 (bits 7-2) | press:2 (bits 1-0)]
     * Byte 2: [mode:3 (bits 7-5) | device:3 (bits 4-2) | state:2 (bits 1-0)]
     * Byte 3: tid (8 bits)
     *
     * @param appKey  Application key for encryption
     * @param sceneId Scene ID (0-255)
     * @param type    Type (0-63, 6 bits)
     * @param press   Press type (0-3, 2 bits: 0=Single, 1=Double, 2=Long, 3=Release)
     * @param mode    Mode (0-7, 3 bits)
     * @param device  Device (0-7, 3 bits)
     * @param state   State (0-3, 2 bits)
     * @param tid     Transaction ID (0-255)
     * @throws IllegalArgumentException if any parameter is out of range
     */
    public GenericSceneSet(@NonNull final ApplicationKey appKey,
                           final int sceneId,
                           final int type,
                           final int press,
                           final int mode,
                           final int device,
                           final int state,
                           final int tid) {
        super(appKey);

        // Validate all parameters according to their bit sizes
        validateParameters(sceneId, type, press, mode, device, state, tid);

        // Store validated values (masking already done in validation)
        this.sceneId = sceneId;
        this.type = type;
        this.press = press;
        this.mode = mode;
        this.device = device;
        this.state = state;
        this.tid = tid;

        assembleMessageParameters();
    }

    /**
     * Validates all parameters against their bit size limits
     */
    private void validateParameters(int sceneId, int type, int press,
                                    int mode, int device, int state, int tid) {
        if (sceneId < 0 || sceneId > SCENE_ID_MAX) {
            throw new IllegalArgumentException(
                    String.format("Scene ID must be 0-%d (got %d)", SCENE_ID_MAX, sceneId));
        }

        if (type < 0 || type > TYPE_MAX) {
            throw new IllegalArgumentException(
                    String.format("Type must be 0-%d (got %d)", TYPE_MAX, type));
        }

        if (press < 0 || press > PRESS_MAX) {
            throw new IllegalArgumentException(
                    String.format("Press must be 0-%d (got %d)", PRESS_MAX, press));
        }

        if (mode < 0 || mode > MODE_MAX) {
            throw new IllegalArgumentException(
                    String.format("Mode must be 0-%d (got %d)", MODE_MAX, mode));
        }

        if (device < 0 || device > DEVICE_MAX) {
            throw new IllegalArgumentException(
                    String.format("Device must be 0-%d (got %d)", DEVICE_MAX, device));
        }

        if (state < 0 || state > STATE_MAX) {
            throw new IllegalArgumentException(
                    String.format("State must be 0-%d (got %d)", STATE_MAX, state));
        }

        if (tid < 0 || tid > TID_MAX) {
            throw new IllegalArgumentException(
                    String.format("TID must be 0-%d (got %d)", TID_MAX, tid));
        }
    }

    @Override
    public int getOpCode() {
        return OP_CODE;
    }

    @Override
    protected void assembleMessageParameters() {
        // Calculate AID from AppKey
        mAid = SecureUtils.calculateK4(mAppKey.getKey());

        /*
         * BIT PACKING STRUCTURE (matches C firmware):
         *
         * Byte 0: scene_id
         *   bits 7-0: sceneId (8 bits)
         *
         * Byte 1: type + press
         *   bits 7-2: type (6 bits) - shift left 2
         *   bits 1-0: press (2 bits) - no shift
         *
         * Byte 2: mode + device + state
         *   bits 7-5: mode (3 bits) - shift left 5
         *   bits 4-2: device (3 bits) - shift left 2
         *   bits 1-0: state (2 bits) - no shift
         *
         * Byte 3: tid
         *   bits 7-0: tid (8 bits)
         */

        ByteBuffer buffer = ByteBuffer.allocate(MESSAGE_SIZE)
                .order(ByteOrder.BIG_ENDIAN); // Mesh uses big-endian

        // Byte 0: scene_id
        buffer.put((byte) sceneId);

        // Byte 1: (type << 2) | press
        // type is 6 bits, shift left 2 to make room for press in lower 2 bits
        byte byte1 = (byte) ((type << 2) | (press & 0x03));
        buffer.put(byte1);

        // Byte 2: (mode << 5) | (device << 2) | state
        // mode: 3 bits -> shift left 5
        // device: 3 bits -> shift left 2
        // state: 2 bits -> no shift
        byte byte2 = (byte) ((mode << 5) | (device << 2) | (state & 0x03));
        buffer.put(byte2);

        // Byte 3: tid
        buffer.put((byte) tid);

        mParameters = buffer.array();

        // Log the assembled message for debugging
        logMessageDetails();
    }

    /**
     * Logs detailed message information for debugging
     */
    private void logMessageDetails() {
        if (mParameters == null || mParameters.length != MESSAGE_SIZE) {
            return;
        }

        MeshLogger.verbose(TAG, "=== GenericSceneSet Message ===");
        MeshLogger.verbose(TAG, String.format(
                "Raw Bytes   : %02X %02X %02X %02X",
                mParameters[0] & 0xFF,
                mParameters[1] & 0xFF,
                mParameters[2] & 0xFF,
                mParameters[3] & 0xFF
        ));

        // Byte 0: scene_id
        MeshLogger.verbose(TAG, String.format(
                "Byte 0 (scene_id): 0x%02X (%d)",
                mParameters[0] & 0xFF, mParameters[0] & 0xFF
        ));

        // Byte 1: type + press
        int extractedType = (mParameters[1] >> 2) & 0x3F;
        int extractedPress = mParameters[1] & 0x03;
        MeshLogger.verbose(TAG, String.format(
                "Byte 1: 0x%02X [type=0x%02X (%d), press=%d (%s)]",
                mParameters[1] & 0xFF,
                extractedType, extractedType,
                extractedPress, pressTypeToString(extractedPress)
        ));

        // Byte 2: mode + device + state
        int extractedMode = (mParameters[2] >> 5) & 0x07;
        int extractedDevice = (mParameters[2] >> 2) & 0x07;
        int extractedState = mParameters[2] & 0x03;
        MeshLogger.verbose(TAG, String.format(
                "Byte 2: 0x%02X [mode=%d, device=%d, state=%d]",
                mParameters[2] & 0xFF,
                extractedMode, extractedDevice, extractedState
        ));

        // Byte 3: tid
        MeshLogger.verbose(TAG, String.format(
                "Byte 3 (tid): 0x%02X (%d)",
                mParameters[3] & 0xFF, mParameters[3] & 0xFF
        ));

        MeshLogger.verbose(TAG, String.format(
                "Values      : sceneId=%d, type=%d, press=%d, mode=%d, device=%d, state=%d, tid=%d",
                sceneId, type, press, mode, device, state, tid
        ));
        MeshLogger.verbose(TAG, "===============================");
    }

    /**
     * Converts press type code to string representation
     */
    private String pressTypeToString(int pressCode) {
        switch (pressCode) {
            case PRESS_SINGLE:  return "Single";
            case PRESS_DOUBLE:  return "Double";
            case PRESS_LONG:    return "Long";
            case PRESS_RELEASE: return "Release";
            default:            return "Unknown";
        }
    }

    /**
     * Creates a GenericSceneSet message from a raw byte array
     * Useful for processing received messages
     *
     * @param appKey Application key
     * @param data Raw 4-byte message data
     * @return GenericSceneSet instance
     * @throws IllegalArgumentException if data is invalid
     */
    public static GenericSceneSet fromByteArray(@NonNull ApplicationKey appKey,
                                                @NonNull byte[] data) {
        if (data == null || data.length != MESSAGE_SIZE) {
            throw new IllegalArgumentException(
                    String.format("Invalid data length. Expected %d bytes, got %d",
                            MESSAGE_SIZE, data == null ? 0 : data.length));
        }

        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);

        // Extract fields according to bit packing
        int sceneId = buffer.get() & 0xFF;

        byte byte1 = buffer.get();
        int type = (byte1 >> 2) & 0x3F;      // Extract type (6 bits)
        int press = byte1 & 0x03;            // Extract press (2 bits)

        byte byte2 = buffer.get();
        int mode = (byte2 >> 5) & 0x07;      // Extract mode (3 bits)
        int device = (byte2 >> 2) & 0x07;    // Extract device (3 bits)
        int state = byte2 & 0x03;             // Extract state (2 bits)

        int tid = buffer.get() & 0xFF;

        return new GenericSceneSet(appKey, sceneId, type, press, mode, device, state, tid);
    }

    /**
     * Helper method to get press type code from string
     *
     * @param press String representation ("single", "double", "long", "release")
     * @return Press type code (0-3)
     */
    public static int getPressTypeCode(@NonNull final String press) {
        switch (press.trim().toLowerCase()) {
            case "single":
            case "single press":
            case "single_press":
                return PRESS_SINGLE;

            case "double":
            case "double press":
            case "double_press":
                return PRESS_DOUBLE;

            case "long":
            case "long press":
            case "long_press":
            case "hold":
                return PRESS_LONG;

            case "release":
            case "press release":
            case "press_release":
                return PRESS_RELEASE;

            default:
                return PRESS_SINGLE;
        }
    }

    // Getters
    public int getSceneId() { return sceneId; }
    public int getType() { return type; }
    public int getPress() { return press; }
    public int getMode() { return mode; }
    public int getDevice() { return device; }
    public int getState() { return state; }
    public int getTid() { return tid; }

    /**
     * Returns the press type as a string
     */
    public String getPressTypeString() {
        return pressTypeToString(press);
    }

    /**
     * Gets the raw message bytes
     */
    public byte[] toByteArray() {
        return mParameters == null ? null : mParameters.clone();
    }

    @Override
    public String toString() {
        return String.format(
                "GenericSceneSet{sceneId=%d, type=%d, press=%d(%s), mode=%d, device=%d, state=%d, tid=%d}",
                sceneId, type, press, getPressTypeString(), mode, device, state, tid
        );
    }
}