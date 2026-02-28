package no.nordicsemi.android.swaromesh.node.dialog;

import androidx.annotation.NonNull;

import no.nordicsemi.android.swaromesh.Group;

/**
 * Publication destination callbacks.
 */
public interface DestinationAddressCallbacks {

    /**
     * Invoked when publish address set.
     *
     * @param address publish address
     */
    void onDestinationAddressSet(int address);

    /**
     * Invoked when publish address set.
     *
     * @param group Group
     */
    void onDestinationAddressSet(@NonNull Group group);
}
