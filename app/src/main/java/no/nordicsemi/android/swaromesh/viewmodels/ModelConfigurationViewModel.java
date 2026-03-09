

package no.nordicsemi.android.swaromesh.viewmodels;

import androidx.annotation.NonNull;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;
import no.nordicsemi.android.swaromesh.ApplicationKey;
import no.nordicsemi.android.swaromesh.NetworkKey;
import no.nordicsemi.android.swaromesh.models.SigModelParser;
import no.nordicsemi.android.swaromesh.transport.ConfigBeaconGet;
import no.nordicsemi.android.swaromesh.transport.ConfigFriendGet;
import no.nordicsemi.android.swaromesh.transport.ConfigHeartbeatPublicationGet;
import no.nordicsemi.android.swaromesh.transport.ConfigHeartbeatSubscriptionGet;
import no.nordicsemi.android.swaromesh.transport.ConfigNetworkTransmitGet;
import no.nordicsemi.android.swaromesh.transport.ConfigNodeIdentityGet;
import no.nordicsemi.android.swaromesh.transport.ConfigRelayGet;
import no.nordicsemi.android.swaromesh.transport.MeshModel;
import no.nordicsemi.android.swaromesh.transport.SceneGet;
//import no.nordicsemi.android.node.swaromesh.GenericLevelServerActivity;
//import no.nordicsemi.android.node.swaromesh.GenericOnOffServerActivity;


///**
// * Generic View Model class for {@link ConfigurationServerActivity},{@link ConfigurationClientActivity},
// * {@link GenericOnOffServerActivity}, {@link GenericLevelServerActivity}, {@link VendorModelActivity},
// * {@link GenericModelConfigurationActivity}
// */
@HiltViewModel
public class ModelConfigurationViewModel extends BaseViewModel {

    @Inject
    ModelConfigurationViewModel(@NonNull final NrfMeshRepository nrfMeshRepository) {
        super(nrfMeshRepository);
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        mNrfMeshRepository.clearTransactionStatus();
        messageQueue.clear();
    }

    public boolean isActivityVisible() {
        return isActivityVisible;
    }

    public void setActivityVisible(final boolean visible) {
        isActivityVisible = visible;
    }

    public void prepareMessageQueue() {
        final ApplicationKey key = getDefaultApplicationKey();
        switch (getSelectedModel().getValue().getModelId()) {
            case SigModelParser.CONFIGURATION_SERVER:
                messageQueue.add(new ConfigHeartbeatPublicationGet());
                messageQueue.add(new ConfigHeartbeatSubscriptionGet());
                messageQueue.add(new ConfigRelayGet());
                messageQueue.add(new ConfigNetworkTransmitGet());
                messageQueue.add(new ConfigBeaconGet());
                messageQueue.add(new ConfigFriendGet());
                final NetworkKey networkKey = getNetworkLiveData().getMeshNetwork().getPrimaryNetworkKey();
                if (networkKey != null) {
                    messageQueue.add(new ConfigNodeIdentityGet(networkKey));
                }
                break;
            case SigModelParser.SCENE_SERVER:
                if (key != null) {
                    messageQueue.add(new SceneGet(key));
                }
                break;
        }
    }

    public ApplicationKey getDefaultApplicationKey() {
        final MeshModel meshModel = getSelectedModel().getValue();
        if (meshModel != null && !meshModel.getBoundAppKeyIndexes().isEmpty()) {
            return getNetworkLiveData().getAppKeys().get(meshModel.getBoundAppKeyIndexes().get(0));
        }
        return null;
    }
}
