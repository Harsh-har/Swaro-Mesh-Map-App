package no.nordicsemi.android.swaromapmesh.node;

import android.os.Bundle;

import dagger.hilt.android.AndroidEntryPoint;
import no.nordicsemi.android.swaromapmesh.transport.MeshMessage;
@AndroidEntryPoint
public class GenericModelConfigurationActivity extends ModelConfigurationActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mSwipe.setOnRefreshListener(this);
        mViewModel.getSelectedModel().observe(this, model -> {
            if (model != null) {
                updateAppStatusUi(model);
                updatePublicationUi(model);
                updateSubscriptionUi(model);
            }
        });
    }

    @Override
    protected void updateMeshMessage(final MeshMessage meshMessage) {
        super.updateMeshMessage(meshMessage);
        hideProgressBar();
    }
}