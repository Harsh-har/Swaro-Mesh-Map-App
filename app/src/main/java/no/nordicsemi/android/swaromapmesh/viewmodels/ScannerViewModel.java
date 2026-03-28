package no.nordicsemi.android.swaromapmesh.viewmodels;

import androidx.annotation.NonNull;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;
import no.nordicsemi.android.swaromapmesh.ble.ScannerActivity;

/**
 * ViewModel for {@link ScannerActivity}
 */
@HiltViewModel
public class ScannerViewModel extends BaseViewModel {

    private final ScannerRepository mScannerRepository;

    @Inject
    ScannerViewModel(@NonNull final NrfMeshRepository nrfMeshRepository,
                     @NonNull final ScannerRepository scannerRepository) {
        super(nrfMeshRepository);
        this.mScannerRepository = scannerRepository;
        scannerRepository.registerBroadcastReceivers();
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        mScannerRepository.unregisterBroadcastReceivers();
    }

    /**
     * Returns an instance of the scanner repository
     */
    public ScannerRepository getScannerRepository() {
        return mScannerRepository;
    }

    /**
     * ✅ NEW: Returns NrfMeshRepository so ScannerActivity can call markSetupRequired()
     */
    public NrfMeshRepository getMeshRepository() {
        return getNrfMeshRepository();
    }
}