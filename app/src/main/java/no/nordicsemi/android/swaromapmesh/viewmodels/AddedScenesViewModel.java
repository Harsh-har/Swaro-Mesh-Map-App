package no.nordicsemi.android.swaromapmesh.viewmodels;

import androidx.annotation.NonNull;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;
import no.nordicsemi.android.swaromapmesh.keys.AppKeysActivity;

/**
 * ViewModel for {@link AppKeysActivity}
 */
@HiltViewModel
public class AddedScenesViewModel extends BaseViewModel {

    @Inject
    AddedScenesViewModel(@NonNull final NrfMeshRepository nrfMeshRepository) {
        super(nrfMeshRepository);
    }
}
