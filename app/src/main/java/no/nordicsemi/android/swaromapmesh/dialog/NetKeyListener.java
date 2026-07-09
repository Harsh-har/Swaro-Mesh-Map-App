package no.nordicsemi.android.swaromapmesh.dialog;

import androidx.annotation.NonNull;
import no.nordicsemi.android.swaromapmesh.NetworkKey;

public interface NetKeyListener {

    void onKeyUpdated(@NonNull final NetworkKey key);

    void onKeyNameUpdated(@NonNull final String nodeName);
}
