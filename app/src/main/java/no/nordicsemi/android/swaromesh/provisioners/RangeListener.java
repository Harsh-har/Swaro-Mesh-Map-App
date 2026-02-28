package no.nordicsemi.android.swaromesh.provisioners;

import androidx.annotation.NonNull;

import no.nordicsemi.android.swaromesh.Range;

public interface RangeListener {

    void addRange(@NonNull final Range range);

    void updateRange(@NonNull final Range range, final Range newRange);
}
