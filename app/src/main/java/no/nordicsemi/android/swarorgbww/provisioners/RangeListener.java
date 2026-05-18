package no.nordicsemi.android.swarorgbww.provisioners;

import androidx.annotation.NonNull;

import no.nordicsemi.android.swarorgbww.Range;

public interface RangeListener {

    void addRange(@NonNull final Range range);

    void updateRange(@NonNull final Range range, final Range newRange);
}
