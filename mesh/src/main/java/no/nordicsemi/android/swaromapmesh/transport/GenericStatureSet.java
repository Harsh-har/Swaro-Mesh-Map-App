package no.nordicsemi.android.swaromapmesh.transport;

import android.util.Log;

import androidx.annotation.NonNull;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import no.nordicsemi.android.swaromapmesh.ApplicationKey;
import no.nordicsemi.android.swaromapmesh.opcodes.ApplicationMessageOpCodes;
import no.nordicsemi.android.swaromapmesh.utils.SecureUtils;

/**
 * GenericStatureSet — 2 Byte Mesh Message
 *
 * BYTE 0 (Control)
 * bit 7 : Increment / Decrement (1 = Inc, 0 = Dec)
 * bit 6 : Update Device/Category
 * bits5-0 : Device/Category ID (0–63)
 *
 * BYTE 1
 * Value / Step (0–255)
 */
public class GenericStatureSet extends ApplicationMessage {

    private static final String TAG = "GenericStatureSet";

    private static final int OP_CODE = ApplicationMessageOpCodes.GENERIC_ENCODER_OPCODE_STATUS;
    private static final int MESSAGE_SIZE = 2;

    // Bit masks
    private static final int MASK_INCREMENT     = 0x80; // bit 7
    private static final int MASK_UPDATE        = 0x40; // bit 6
    private static final int MASK_CATEGORY      = 0x3F; // bits 0–5

    private static final int MAX_CATEGORY = 63;
    private static final int MAX_VALUE    = 255;

    private final boolean isIncrement;
    private final boolean isUpdate;
    private final int category;
    private final int value;

    // ─────────────────────────────────────────────
    // Constructor (User Inputs 4 values)
    // ─────────────────────────────────────────────
    public GenericStatureSet(@NonNull ApplicationKey appKey,
                             boolean isIncrement,
                             boolean isUpdate,
                             int category,
                             int value) {

        super(appKey);

        validate(category, value);

        this.isIncrement = isIncrement;
        this.isUpdate    = isUpdate;
        this.category    = category;
        this.value       = value;

        assembleMessageParameters();
    }

    // ─────────────────────────────────────────────
    // Constructor (Packed Control Byte)
    // ─────────────────────────────────────────────
    public GenericStatureSet(@NonNull ApplicationKey appKey,
                             int controlByte,
                             int value) {

        super(appKey);

        this.isIncrement = (controlByte & MASK_INCREMENT) != 0;
        this.isUpdate    = (controlByte & MASK_UPDATE) != 0;
        this.category    = controlByte & MASK_CATEGORY;
        this.value       = value;

        validate(this.category, this.value);
        assembleMessageParameters();
    }

    @Override
    public int getOpCode() {
        return OP_CODE;
    }

    // ─────────────────────────────────────────────
    // Assemble 2-Byte Payload
    // ─────────────────────────────────────────────
    @Override
    protected void assembleMessageParameters() {

        mAid = SecureUtils.calculateK4(mAppKey.getKey());

        int controlByte = 0;

        if (isIncrement) controlByte |= MASK_INCREMENT;
        if (isUpdate)    controlByte |= MASK_UPDATE;

        controlByte |= (category & MASK_CATEGORY);

        mParameters = ByteBuffer.allocate(MESSAGE_SIZE)
                .order(ByteOrder.BIG_ENDIAN)
                .put((byte) controlByte)
                .put((byte) value)
                .array();

        logPayload(controlByte);
    }

    // ─────────────────────────────────────────────
    // Validation
    // ─────────────────────────────────────────────
    private void validate(int category, int value) {

        if (category < 0 || category > MAX_CATEGORY) {
            throw new IllegalArgumentException(
                    "Category must be 0–63 (got " + category + ")");
        }

        if (value < 0 || value > MAX_VALUE) {
            throw new IllegalArgumentException(
                    "Value must be 0–255 (got " + value + ")");
        }
    }

    // ─────────────────────────────────────────────
    // Logging
    // ─────────────────────────────────────────────
    private void logPayload(int controlByte) {

        Log.d(TAG, "══════════════════════════════════════");
        Log.d(TAG, "GenericStatureSet Payload (2 Bytes)");
        Log.d(TAG, "BYTE0 Control : " + toBinary8(controlByte)
                + " 0x" + String.format("%02X", controlByte));
        Log.d(TAG, "  bit7 Increment : " + isIncrement);
        Log.d(TAG, "  bit6 Update    : " + isUpdate);
        Log.d(TAG, "  bits5-0 Cat    : " + category);
        Log.d(TAG, "BYTE1 Value   : " + value
                + " 0x" + String.format("%02X", value));
        Log.d(TAG, "══════════════════════════════════════");
    }

    // ─────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────
    private static String toBinary8(int v) {
        return String.format("%8s",
                Integer.toBinaryString(v & 0xFF)).replace(' ', '0');
    }

    // ─────────────────────────────────────────────
    // Getters
    // ─────────────────────────────────────────────
    public boolean isIncrement() { return isIncrement; }
    public boolean isUpdate()    { return isUpdate; }
    public int getCategory()     { return category; }
    public int getValue()        { return value; }

    public int getControlByte() {
        int b = 0;
        if (isIncrement) b |= MASK_INCREMENT;
        if (isUpdate)    b |= MASK_UPDATE;
        b |= (category & MASK_CATEGORY);
        return b;
    }

    public byte[] toByteArray() {
        return mParameters.clone();
    }

    @Override
    public String toString() {
        return "GenericStatureSet{" +
                "inc=" + isIncrement +
                ", update=" + isUpdate +
                ", category=" + category +
                ", value=" + value +
                '}';
    }
}
