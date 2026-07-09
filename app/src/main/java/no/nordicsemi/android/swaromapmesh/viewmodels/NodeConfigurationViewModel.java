/*
 * Copyright (c) 2018, Nordic Semiconductor
 * All rights reserved.
 */

package no.nordicsemi.android.swaromapmesh.viewmodels;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import android.util.Log;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;
import no.nordicsemi.android.swaromapmesh.transport.ProvisionedMeshNode;
import no.nordicsemi.android.swaromapmesh.node.NodeConfigurationActivity;

/**
 * View model class for {@link NodeConfigurationActivity}
 */
@HiltViewModel
public class NodeConfigurationViewModel extends BaseViewModel {

    private static final String TAG = "NodeConfigVM";

    /* ---------------------------------------------------
     * 🔥 GLOBAL ELEMENT ADDRESS
     * --------------------------------------------------- */
    private final MutableLiveData<Integer> selectedElementAddress =
            new MutableLiveData<>();

    @Inject
    NodeConfigurationViewModel(
            @NonNull final NrfMeshRepository nrfMeshRepository) {
        super(nrfMeshRepository);
    }

    /* ---------------------------------------------------
     * ✅ SAVE ELEMENT ADDRESS
     * (CALL WHEN ELEMENT CLICKED)
     * --------------------------------------------------- */
    public void setSelectedElementAddress(final int address) {
        selectedElementAddress.setValue(address);

        Log.d(TAG,
                "Selected Element Address = 0x"
                        + String.format("%04X", address));
    }

    /* ---------------------------------------------------
     * ✅ GET ELEMENT ADDRESS (GLOBAL ACCESS)
     * --------------------------------------------------- */
    @NonNull
    public LiveData<Integer> getSelectedElementAddress() {
        return selectedElementAddress;
    }

    /* ---------------------------------------------------
     * ✅ SAFE DIRECT VALUE ACCESS (OPTIONAL)
     * --------------------------------------------------- */
    public int getSelectedElementAddressValue() {
        final Integer value = selectedElementAddress.getValue();
        return value != null ? value : -1;
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        mNrfMeshRepository.clearTransactionStatus();
    }

    /* ---------------------------------------------------
     * EXISTING FEATURE CHECK
     * --------------------------------------------------- */
    public boolean isProxyFeatureEnabled() {
        final ProvisionedMeshNode meshNode =
                getSelectedMeshNode().getValue();

        return meshNode != null &&
                meshNode.getNodeFeatures() != null &&
                meshNode.getNodeFeatures().isProxyFeatureEnabled();
    }
}
