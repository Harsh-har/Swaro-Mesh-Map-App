package no.nordicsemi.android.swarorgbww.transport;


import no.nordicsemi.android.swarorgbww.opcodes.ConfigMessageOpCodes;

/**
 * Creates the ConfigBeaconGet message.
 */
public class ConfigBeaconGet extends ConfigMessage {

    private static final String TAG = ConfigBeaconGet.class.getSimpleName();
    private static final int OP_CODE = ConfigMessageOpCodes.CONFIG_BEACON_GET;

    /**
     * Constructs ConfigBeaconGet message.
     */
    public ConfigBeaconGet() {
        assembleMessageParameters();
    }

    @Override
    public int getOpCode() {
        return OP_CODE;
    }

    @Override
    void assembleMessageParameters() {
        //Do nothing as ConfigNodeReset message does not have parameters
    }
}
