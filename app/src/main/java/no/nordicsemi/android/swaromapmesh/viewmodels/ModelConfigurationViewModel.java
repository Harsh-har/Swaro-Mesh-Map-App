

package no.nordicsemi.android.swaromapmesh.viewmodels;

import androidx.annotation.NonNull;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;
import no.nordicsemi.android.swaromapmesh.ApplicationKey;
import no.nordicsemi.android.swaromapmesh.NetworkKey;
import no.nordicsemi.android.swaromapmesh.models.SigModelParser;
import no.nordicsemi.android.swaromapmesh.transport.ConfigBeaconGet;
import no.nordicsemi.android.swaromapmesh.transport.ConfigFriendGet;
import no.nordicsemi.android.swaromapmesh.transport.ConfigHeartbeatPublicationGet;
import no.nordicsemi.android.swaromapmesh.transport.ConfigHeartbeatSubscriptionGet;
import no.nordicsemi.android.swaromapmesh.transport.ConfigNetworkTransmitGet;
import no.nordicsemi.android.swaromapmesh.transport.ConfigNodeIdentityGet;
import no.nordicsemi.android.swaromapmesh.transport.ConfigRelayGet;
import no.nordicsemi.android.swaromapmesh.transport.MeshModel;
import no.nordicsemi.android.swaromapmesh.transport.SceneGet;
//import no.nordicsemi.android.node.swaromesh.GenericLevelServerActivity;
//import no.nordicsemi.android.node.swaromesh.GenericOnOffServerActivity;


import no.nordicsemi.android.swaromapmesh.utils.FeedbackManager;

///**
// * Generic View Model class for {@link ConfigurationServerActivity},{@link ConfigurationClientActivity},
// * {@link GenericOnOffServerActivity}, {@link GenericLevelServerActivity}, {@link VendorModelActivity},
// * {@link GenericModelConfigurationActivity}
// */
@HiltViewModel
public class ModelConfigurationViewModel extends BaseViewModel {

    private final FeedbackManager mFeedbackManager;

    @Inject
    ModelConfigurationViewModel(@NonNull final NrfMeshRepository nrfMeshRepository,
                                @NonNull final FeedbackManager feedbackManager) {
        super(nrfMeshRepository);
        this.mFeedbackManager = feedbackManager;
    }

    public void performSuccessFeedback(String message) {
        mFeedbackManager.performSuccessFeedback(message);
    }

    public void performLongHapticWithBeep() {
        mFeedbackManager.performLongHapticWithBeep();
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
