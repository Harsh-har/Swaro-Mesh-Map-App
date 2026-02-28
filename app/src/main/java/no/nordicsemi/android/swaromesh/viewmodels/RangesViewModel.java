package no.nordicsemi.android.swaromesh.viewmodels;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;
import no.nordicsemi.android.swaromesh.Provisioner;

@HiltViewModel
public class RangesViewModel extends BaseViewModel {

    /**
     * Constructs {@link BaseViewModel}
     *
     * @param nRfMeshRepository Mesh Repository {@link NrfMeshRepository}
     */
    @Inject
    RangesViewModel(@NonNull final NrfMeshRepository nRfMeshRepository) {
        super(nRfMeshRepository);
    }

    public LiveData<Provisioner> getSelectedProvisioner() {
        return mNrfMeshRepository.getSelectedProvisioner();
    }

    public void setSelectedProvisioner(@NonNull final Provisioner provisioner) {
        mNrfMeshRepository.setSelectedProvisioner(provisioner);
    }
}
