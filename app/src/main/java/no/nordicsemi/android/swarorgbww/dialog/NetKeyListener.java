package no.nordicsemi.android.swarorgbww.dialog;

import androidx.annotation.NonNull;
import no.nordicsemi.android.swarorgbww.NetworkKey;

public interface NetKeyListener {

    void onKeyUpdated(@NonNull final NetworkKey key);

    void onKeyNameUpdated(@NonNull final String nodeName);
}
