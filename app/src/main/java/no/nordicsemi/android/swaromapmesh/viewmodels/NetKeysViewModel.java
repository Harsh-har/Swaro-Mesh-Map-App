package no.nordicsemi.android.swaromapmesh.viewmodels;

import androidx.annotation.NonNull;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;
import no.nordicsemi.android.swaromapmesh.keys.AppKeysActivity;

/**
 * ViewModel for {@link AppKeysActivity}
 */
@HiltViewModel
public class NetKeysViewModel extends BaseViewModel {

    @Inject
    NetKeysViewModel(@NonNull final NrfMeshRepository nrfMeshRepository) {
        super(nrfMeshRepository);
    }
}
