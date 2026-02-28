package no.nordicsemi.android.swaromesh.node;

import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import com.google.android.material.slider.Slider;
import com.google.android.material.snackbar.Snackbar;
import java.util.Random;
import dagger.hilt.android.AndroidEntryPoint;
import no.nordicsemi.android.swaromesh.ApplicationKey;
import no.nordicsemi.android.swaromesh.models.GenericLevelServerModel;
import no.nordicsemi.android.swaromesh.transport.Element;
import no.nordicsemi.android.swaromesh.transport.GenericLevelGet;
import no.nordicsemi.android.swaromesh.transport.GenericLevelSet;
import no.nordicsemi.android.swaromesh.transport.GenericLevelStatus;
import no.nordicsemi.android.swaromesh.transport.MeshMessage;
import no.nordicsemi.android.swaromesh.transport.MeshModel;
import no.nordicsemi.android.swaromesh.transport.ProvisionedMeshNode;
import no.nordicsemi.android.swaromesh.utils.MeshAddress;
import no.nordicsemi.android.swaromesh.utils.MeshParserUtils;
import no.nordicsemi.android.swaromesh.R;
import no.nordicsemi.android.swaromesh.databinding.LayoutGenericLevelBinding;

@AndroidEntryPoint
public class GenericLevelServerActivity extends ModelConfigurationActivity {

    private static final String TAG = GenericLevelServerActivity.class.getSimpleName();

    private TextView level;
    private TextView time;
    private TextView remainingTime;
    private Slider mTransitionTimeSlider;
    private Slider mDelaySlider;
    private Slider mLevelSlider;

    private int mTransitionStepResolution;
    private int mTransitionSteps;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mSwipe.setOnRefreshListener(this);

        final MeshModel model = mViewModel.getSelectedModel().getValue();
        if (model instanceof GenericLevelServerModel) {

            final LayoutGenericLevelBinding nodeControlsContainer =
                    LayoutGenericLevelBinding.inflate(
                            getLayoutInflater(),
                            binding.nodeControlsContainer,
                            true
                    );

            time = nodeControlsContainer.transitionTime;
            remainingTime = nodeControlsContainer.transitionState;

            mTransitionTimeSlider = nodeControlsContainer.transitionSlider;
            mTransitionTimeSlider.setValueFrom(0);
            mTransitionTimeSlider.setValueTo(230);
            mTransitionTimeSlider.setValue(0);
            mTransitionTimeSlider.setStepSize(1);

            mDelaySlider = nodeControlsContainer.delaySlider;
            mDelaySlider.setValueFrom(0);
            mDelaySlider.setValueTo(255);
            mDelaySlider.setValue(0);
            mDelaySlider.setStepSize(1);
            final TextView delayTime = nodeControlsContainer.delayTime;

            level = nodeControlsContainer.level;
            mLevelSlider = nodeControlsContainer.levelSeekBar;
            mLevelSlider.setValueFrom(0);
            mLevelSlider.setValueTo(100);
            mLevelSlider.setValue(0);
            mLevelSlider.setStepSize(1);

            mActionRead = nodeControlsContainer.actionRead;
            mActionRead.setOnClickListener(v -> sendGenericLevelGet());

            mTransitionTimeSlider.addOnChangeListener((slider, value, fromUser) -> {
                final int progress = (int) value;
                mTransitionSteps = progress;
                mTransitionStepResolution = 0;
                time.setText(
                        getString(
                                R.string.transition_time_interval,
                                String.valueOf(progress / 10.0),
                                "s"
                        )
                );
            });

            mDelaySlider.addOnChangeListener((slider, value, fromUser) ->
                    delayTime.setText(
                            getString(
                                    R.string.transition_time_interval,
                                    String.valueOf((int) value * MeshParserUtils.GENERIC_ON_OFF_5_MS),
                                    "ms"
                            )
                    )
            );

            mLevelSlider.addOnChangeListener((slider, value, fromUser) ->
                    level.setText(
                            getString(R.string.generic_level_percent, (int) value)
                    )
            );

            mLevelSlider.addOnSliderTouchListener(new Slider.OnSliderTouchListener() {
                @Override
                public void onStartTrackingTouch(@NonNull Slider slider) { }

                @Override
                public void onStopTrackingTouch(@NonNull Slider slider) {
                    final int levelPercent = (int) slider.getValue();
                    final int genericLevel = ((levelPercent * 65535) / 100) - 32768;
                    sendGenericLevel(genericLevel);
                }
            });

            mViewModel.getSelectedModel().observe(this, meshModel -> {
                if (meshModel != null) {
                    updateAppStatusUi(meshModel);
                    updatePublicationUi(meshModel);
                    updateSubscriptionUi(meshModel);
                }
            });
        }
    }

    @Override
    protected void updateMeshMessage(final MeshMessage meshMessage) {
        super.updateMeshMessage(meshMessage);

        if (meshMessage instanceof GenericLevelStatus) {
            final GenericLevelStatus status = (GenericLevelStatus) meshMessage;
            hideProgressBar();

            final int presentLevel = status.getPresentLevel();
            final Integer targetLevel = status.getTargetLevel();
            final int levelPercent;

            if (targetLevel == null) {
                levelPercent = ((presentLevel + 32768) * 100) / 65535;
                remainingTime.setVisibility(View.GONE);
            } else {
                levelPercent = ((targetLevel + 32768) * 100) / 65535;
                remainingTime.setVisibility(View.VISIBLE);
            }

            level.setText(getString(R.string.generic_level_percent, levelPercent));
            mLevelSlider.setValue(levelPercent);
        }
    }

    /**
     * Send Generic Level Get
     */
    public void sendGenericLevelGet() {
        if (!checkConnectivity(mContainer)) return;

        final Element element = mViewModel.getSelectedElement().getValue();
        final MeshModel model = mViewModel.getSelectedModel().getValue();

        if (element != null && model != null && !model.getBoundAppKeyIndexes().isEmpty()) {

            final int appKeyIndex = model.getBoundAppKeyIndexes().get(0);
            final ApplicationKey appKey =
                    mViewModel.getNetworkLiveData().getMeshNetwork().getAppKey(appKeyIndex);

            final int address = element.getElementAddress();
            Log.v(TAG, "Sending GenericLevelGet to: " +
                    MeshAddress.formatAddress(address, true));

            sendAcknowledgedMessage(address, new GenericLevelGet(appKey));
        }
    }

    /**
     * Send Generic Level Set (UPDATED for new constructor)
     */
    public void sendGenericLevel(final int levelValue) {
        if (!checkConnectivity(mContainer)) return;

        final ProvisionedMeshNode node = mViewModel.getSelectedMeshNode().getValue();
        if (node == null) return;

        final Element element = mViewModel.getSelectedElement().getValue();
        final MeshModel model = mViewModel.getSelectedModel().getValue();
        if (element == null || model == null) return;

        if (!model.getBoundAppKeyIndexes().isEmpty()) {

            final int appKeyIndex = model.getBoundAppKeyIndexes().get(0);
            final ApplicationKey appKey = mViewModel.getNetworkLiveData().getMeshNetwork().getAppKey(appKeyIndex);

            final int address = element.getElementAddress();
            final int tid = new Random().nextInt(256); // TID must be 0-255
            final int command = 0x01; // custom command

            // FIXED: Using the correct constructor order
            // GenericLevelSet(ApplicationKey appKey, int command, int level, int tid)
            final GenericLevelSet genericLevelSet = new GenericLevelSet(appKey, command, levelValue, tid);

            // Alternatively, you can use the Builder pattern:
            // final GenericLevelSet genericLevelSet = new GenericLevelSet.Builder(appKey)
            //         .withCommand(command)
            //         .withLevel(levelValue)
            //         .withTid(tid)
            //         .build();

            sendAcknowledgedMessage(address, genericLevelSet);

        } else {
            mViewModel.displaySnackBar(
                    this,
                    mContainer,
                    getString(R.string.error_no_app_keys_bound),
                    Snackbar.LENGTH_LONG
            );
        }
    }


}