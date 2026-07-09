package no.nordicsemi.android.swaromapmesh.viewmodels;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;
import no.nordicsemi.android.swaromapmesh.Provisioner;
import no.nordicsemi.android.swaromapmesh.keys.AppKeysActivity;

/**
 * ViewModel for {@link AppKeysActivity}
 */
@HiltViewModel
public class AddProvisionerViewModel extends BaseViewModel {

    @Inject
    AddProvisionerViewModel(@NonNull final NrfMeshRepository nrfMeshRepository) {
        super(nrfMeshRepository);
        mNrfMeshRepository.clearTransactionStatus();
    }

    public void setSelectedProvisioner(@NonNull final Provisioner provisioner) {
        mNrfMeshRepository.setSelectedProvisioner(provisioner);
    }

    public LiveData<Provisioner> getSelectedProvisioner() {
        return mNrfMeshRepository.getSelectedProvisioner();
    }
}
