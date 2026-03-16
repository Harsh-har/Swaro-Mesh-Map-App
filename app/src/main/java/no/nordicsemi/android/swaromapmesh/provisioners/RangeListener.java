package no.nordicsemi.android.swaromapmesh.provisioners;

import androidx.annotation.NonNull;

import no.nordicsemi.android.swaromapmesh.Range;

public interface RangeListener {

    void addRange(@NonNull final Range range);

    void updateRange(@NonNull final Range range, final Range newRange);
}
