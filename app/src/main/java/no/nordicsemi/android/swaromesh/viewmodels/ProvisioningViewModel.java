package no.nordicsemi.android.swaromesh.viewmodels;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import javax.inject.Inject;
import dagger.hilt.android.lifecycle.HiltViewModel;
import no.nordicsemi.android.swaromesh.adapter.ExtendedBluetoothDevice;
import no.nordicsemi.android.swaromesh.provisionerstates.ProvisioningCapabilities;
import no.nordicsemi.android.swaromesh.provisionerstates.UnprovisionedMeshNode;
import no.nordicsemi.android.swaromesh.utils.AlgorithmType;
import no.nordicsemi.android.swaromesh.utils.InputOOBAction;
import no.nordicsemi.android.swaromesh.utils.OutputOOBAction;
import no.nordicsemi.android.swaromesh.ProvisioningActivity;
import no.nordicsemi.android.swaromesh.R;

/**
 * ViewModel for {@link ProvisioningActivity}
 */
@HiltViewModel
public class ProvisioningViewModel extends BaseViewModel {
    private String mDeviceMacAddress;

    @Inject
    ProvisioningViewModel(@NonNull final NrfMeshRepository nrfMeshRepository) {
        super(nrfMeshRepository);
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        mNrfMeshRepository.clearProvisioningLiveData();
    }

    /**
     * Returns the LiveData {@link UnprovisionedMeshNode}
     */
    public LiveData<UnprovisionedMeshNode> getUnprovisionedMeshNode() {
        return mNrfMeshRepository.getUnprovisionedMeshNode();
    }

    /**
     * Returns true if reconnecting after provisioning is completed
     */
    public LiveData<Boolean> isReconnecting() {
        return mNrfMeshRepository.isReconnecting();
    }

    /**
     * Returns the provisioning status
     */
    public ProvisioningStatusLiveData getProvisioningStatus() {
        return mNrfMeshRepository.getProvisioningState();
    }

    /**
     * Returns true if provisioning has completed
     */
    public boolean isProvisioningComplete() {
        return mNrfMeshRepository.isProvisioningComplete();
    }

    /**
     * Returns true if the CompositionDataStatus is received
     */
    public boolean isCompositionDataStatusReceived() {
        return mNrfMeshRepository.isCompositionDataStatusReceived();
    }

    /**
     * Returns true if the DefaultTTLGet completed
     */
    public boolean isDefaultTtlReceived() {
        return mNrfMeshRepository.isDefaultTtlReceived();
    }

    /**
     * Returns true if the AppKeyAdd completed
     */
    public boolean isAppKeyAddCompleted() {
        return mNrfMeshRepository.isAppKeyAddCompleted();
    }

    /**
     * Returns true if the NetworkRetransmitSet is completed
     */
    public boolean isNetworkRetransmitSetCompleted() {
        return mNrfMeshRepository.isNetworkRetransmitSetCompleted();
    }

    /**
     * Set device MAC address
     */
    public void setDeviceMacAddress(String macAddress) {
        this.mDeviceMacAddress = macAddress;

        // Also set MAC address in UnprovisionedMeshNode if it exists
        UnprovisionedMeshNode node = getUnprovisionedMeshNode().getValue();
        if (node != null && macAddress != null) {
            node.setMacAddress(macAddress);
        }
    }

    /**
     * Get device MAC address
     */
    public String getDeviceMacAddress() {
        return mDeviceMacAddress;
    }

    /**
     * Connect to device and ensure MAC address is set
     */
    @Override
    public void connect(@NonNull final Context context,
                        @NonNull final ExtendedBluetoothDevice device,
                        final boolean connectToNetwork) {
        // Store MAC address
        setDeviceMacAddress(device.getAddress());

        // Call parent connect method
        super.connect(context, device, connectToNetwork);
    }

    /**
     * Get UnprovisionedMeshNode value directly (not LiveData)
     */
    public UnprovisionedMeshNode getUnprovisionedMeshNodeValue() {
        return getUnprovisionedMeshNode().getValue();
    }

    /**
     * Check if UnprovisionedMeshNode has MAC address
     */
    public boolean hasMacAddressInNode() {
        UnprovisionedMeshNode node = getUnprovisionedMeshNodeValue();
        return node != null && node.getMacAddress() != null && !node.getMacAddress().isEmpty();
    }

    /**
     * Set MAC address in UnprovisionedMeshNode if not set
     */
    public void ensureMacAddressInNode(String macAddress) {
        UnprovisionedMeshNode node = getUnprovisionedMeshNodeValue();
        if (node != null && (node.getMacAddress() == null || node.getMacAddress().isEmpty())) {
            node.setMacAddress(macAddress);
        }
    }

    public String parseAlgorithms(final ProvisioningCapabilities capabilities) {
        final StringBuilder algorithmTypes = new StringBuilder();
        int count = 0;
        for (AlgorithmType algorithmType : capabilities.getSupportedAlgorithmTypes()) {
            if (count == 0) {
                algorithmTypes.append(algorithmType.getName());
            } else {
                algorithmTypes.append(", ").append(algorithmType.getName());
            }
            count++;
        }
        return algorithmTypes.toString();
    }

    public String parseOutputOOBActions(@NonNull final Context context, @NonNull final ProvisioningCapabilities capabilities) {
        if (capabilities.getSupportedOutputOOBActions().isEmpty())
            return context.getString(R.string.output_oob_actions_unavailable);

        final StringBuilder outputOOBActions = new StringBuilder();
        int count = 0;
        for (OutputOOBAction outputOOBAction : capabilities.getSupportedOutputOOBActions()) {
            if (count == 0) {
                outputOOBActions.append(OutputOOBAction.getOutputOOBActionDescription(outputOOBAction));
            } else {
                outputOOBActions.append(", ").append(OutputOOBAction.getOutputOOBActionDescription(outputOOBAction));
            }
            count++;
        }
        return outputOOBActions.toString();
    }

    public String parseInputOOBActions(@NonNull final Context context, @NonNull final ProvisioningCapabilities capabilities) {
        if (capabilities.getSupportedInputOOBActions().isEmpty())
            return context.getString(R.string.input_oob_actions_unavailable);

        final StringBuilder inputOOBActions = new StringBuilder();
        int count = 0;
        for (InputOOBAction inputOOBAction : capabilities.getSupportedInputOOBActions()) {
            if (count == 0) {
                inputOOBActions.append(InputOOBAction.getInputOOBActionDescription(inputOOBAction));
            } else {
                inputOOBActions.append(", ").append(InputOOBAction.getInputOOBActionDescription(inputOOBAction));
            }
            count++;
        }
        return inputOOBActions.toString();
    }
}