package no.nordicsemi.android.swaromesh.dialog;

import androidx.annotation.NonNull;
import no.nordicsemi.android.swaromesh.NetworkKey;

public interface NetKeyListener {

    void onKeyUpdated(@NonNull final NetworkKey key);

    void onKeyNameUpdated(@NonNull final String nodeName);
}
