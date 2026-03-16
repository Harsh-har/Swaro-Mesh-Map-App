package no.nordicsemi.android.swaromapmesh.node;

import static no.nordicsemi.android.swaromapmesh.utils.MeshAddress.formatAddress;
import static no.nordicsemi.android.swaromapmesh.utils.MeshAddress.isValidGroupAddress;
import static no.nordicsemi.android.swaromapmesh.utils.MeshAddress.isValidVirtualAddress;
import static no.nordicsemi.android.swaromapmesh.utils.Utils.BIND_APP_KEY;
import static no.nordicsemi.android.swaromapmesh.utils.Utils.EXTRA_DATA;
import static no.nordicsemi.android.swaromapmesh.utils.Utils.MESSAGE_TIME_OUT;
import static no.nordicsemi.android.swaromapmesh.utils.Utils.RESULT_KEY;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import no.nordicsemi.android.swaromapmesh.ApplicationKey;
import no.nordicsemi.android.swaromapmesh.Group;
import no.nordicsemi.android.swaromapmesh.MeshNetwork;
import no.nordicsemi.android.swaromapmesh.models.ConfigurationClientModel;
import no.nordicsemi.android.swaromapmesh.models.ConfigurationServerModel;
import no.nordicsemi.android.swaromapmesh.models.SigModel;
import no.nordicsemi.android.swaromapmesh.models.SigModelParser;
import no.nordicsemi.android.swaromapmesh.transport.ConfigModelAppBind;
import no.nordicsemi.android.swaromapmesh.transport.ConfigModelAppUnbind;
import no.nordicsemi.android.swaromapmesh.transport.ConfigModelPublicationGet;
import no.nordicsemi.android.swaromapmesh.transport.ConfigModelPublicationSet;
import no.nordicsemi.android.swaromapmesh.transport.ConfigModelSubscriptionAdd;
import no.nordicsemi.android.swaromapmesh.transport.ConfigModelSubscriptionDelete;
import no.nordicsemi.android.swaromapmesh.transport.ConfigModelSubscriptionVirtualAddressAdd;
import no.nordicsemi.android.swaromapmesh.transport.ConfigModelSubscriptionVirtualAddressDelete;
import no.nordicsemi.android.swaromapmesh.transport.ConfigSigModelAppGet;
import no.nordicsemi.android.swaromapmesh.transport.ConfigSigModelSubscriptionGet;
import no.nordicsemi.android.swaromapmesh.transport.ConfigVendorModelAppGet;
import no.nordicsemi.android.swaromapmesh.transport.ConfigVendorModelSubscriptionGet;
import no.nordicsemi.android.swaromapmesh.transport.Element;
import no.nordicsemi.android.swaromapmesh.transport.GenericLightSet;
import no.nordicsemi.android.swaromapmesh.transport.GenericOnOffSet;
import no.nordicsemi.android.swaromapmesh.transport.GenericSceneSet;
import no.nordicsemi.android.swaromapmesh.transport.GenericStatureSet;
import no.nordicsemi.android.swaromapmesh.transport.MeshMessage;
import no.nordicsemi.android.swaromapmesh.transport.MeshModel;
import no.nordicsemi.android.swaromapmesh.transport.ProvisionedMeshNode;
import no.nordicsemi.android.swaromapmesh.transport.PublicationSettings;
import no.nordicsemi.android.swaromapmesh.utils.CompositionDataParser;
import no.nordicsemi.android.swaromapmesh.GroupCallbacks;
import no.nordicsemi.android.swaromapmesh.R;
import no.nordicsemi.android.swaromapmesh.adapter.GroupAddressAdapter;
import no.nordicsemi.android.swaromapmesh.databinding.ActivityModelConfigurationBinding;
import no.nordicsemi.android.swaromapmesh.dialog.DialogFragmentConfigStatus;
import no.nordicsemi.android.swaromapmesh.dialog.DialogFragmentDisconnected;
import no.nordicsemi.android.swaromapmesh.dialog.DialogFragmentError;
import no.nordicsemi.android.swaromapmesh.dialog.DialogFragmentGroupSubscription;
import no.nordicsemi.android.swaromapmesh.dialog.DialogFragmentTransactionStatus;
import no.nordicsemi.android.swaromapmesh.keys.AppKeysActivity;
import no.nordicsemi.android.swaromapmesh.keys.adapter.BoundAppKeysAdapter;
import no.nordicsemi.android.swaromapmesh.viewmodels.BaseActivity;
import no.nordicsemi.android.swaromapmesh.viewmodels.ModelConfigurationViewModel;
import no.nordicsemi.android.swaromapmesh.widgets.ItemTouchHelperAdapter;
import no.nordicsemi.android.swaromapmesh.widgets.RemovableItemTouchHelperCallback;
import no.nordicsemi.android.swaromapmesh.widgets.RemovableViewHolder;

public abstract class BaseModelConfigurationActivity extends BaseActivity implements
        GroupCallbacks,
        ItemTouchHelperAdapter,
        DialogFragmentDisconnected.DialogFragmentDisconnectedListener,
        SwipeRefreshLayout.OnRefreshListener {

    // ─────────────────────────────────────────────────────────────────────────
    // Log Tags — filter in Logcat by these tags
    // ─────────────────────────────────────────────────────────────────────────
    private static final String TAG         = "BaseModelConfig";
    private static final String TAG_STATURE = "STATURE_CMD";
    private static final String TAG_SCENE   = "SCENE_CMD";
    private static final String TAG_ONOFF   = "ONOFF_CMD";
    private static final String TAG_LIGHT   = "LIGHT_CMD";
    private static final String TAG_TID     = "TID";
    private static final String TAG_MODEL   = "MODEL_CARD";


    // ─────────────────────────────────────────────────────────────────────────
    // ✅ FIXED:  separate model IDs (was duplicate GENERIC_ONOFF_CLIENT before)
    // ─────────────────────────────────────────────────────────────────────────
    private static final int GENERIC_ONOFF_SERVER = 0x1000; // → Short + Long commands
    private static final int GENERIC_ONOFF_CLIENT = 0x1001; // → Scene + Stature commands

    private static final String DIALOG_FRAGMENT_CONFIGURATION_STATUS = "DIALOG_FRAGMENT_CONFIGURATION_STATUS";
    private static final String PROGRESS_BAR_STATE = "PROGRESS_BAR_STATE";

    private static final int DEFAULT_BRIGHTNESS_VALUE = 30;
    private static final int MIN_BRIGHTNESS = 0;
    private static final int MAX_BRIGHTNESS = 255;

    // ✅ UPDATED: Length range from 0-255 (was 1-8)
    private static final int MIN_LENGTH = 0;
    private static final int MAX_LENGTH = 255;

    private static final int MAX_TID = 255;

    private static final String PRESS_TYPE_SINGLE = "Single";
    private static final String PRESS_TYPE_DOUBLE = "Double";
    private static final String PRESS_TYPE_LONG   = "Long";

    // TID counters
    private final AtomicInteger genericOnOffTidCounter = new AtomicInteger(0);
    private final AtomicInteger genericLightTidCounter = new AtomicInteger(0);
    private final AtomicInteger sceneTidCounter        = new AtomicInteger(0);

    protected ActivityModelConfigurationBinding binding;

    // toh

    // Command containers
    private LinearLayout mContainerShortLongCommands;
    private LinearLayout mContainerSceneCommands;
    private View mGenericStatureCard;  // Added for Generic Stature card

    CoordinatorLayout mContainer;
    View    mContainerAppKeyBinding;
    Button  mActionBindAppKey;
    TextView mAppKeyView;
    TextView mUnbindHint;
    View    mContainerPublication;
    Button  mActionSetPublication;
    Button  mActionClearPublication;
    TextView mPublishAddressView;
    View    mContainerSubscribe;
    Button  mActionSubscribe;
    TextView mSubscribeAddressView;
    TextView mSubscribeHint;
    ProgressBar mProgressbar;
    SwipeRefreshLayout mSwipe;

    protected List<Integer> mGroupAddress = new ArrayList<>();
    protected List<Integer> mKeyIndexes   = new ArrayList<>();
    protected GroupAddressAdapter mSubscriptionAdapter;
    protected BoundAppKeysAdapter mBoundAppKeyAdapter;
    protected Button mActionRead;
    protected Button mActionSetRelayState;
    protected Button mSetNetworkTransmitStateButton;

    // Short Command
    protected Button mSendButton;
    protected TextInputEditText mCommandEditText;
    protected TextInputEditText mStateEditText;

    // Long Command
    protected Button mLongSendButton;
    protected Button mLongReadButton;
    protected TextInputEditText mLengthEditText;
    protected TextInputEditText mLongAddressEditText;
    protected List<TextInputLayout>   mLongDataFields    = new ArrayList<>();
    protected List<TextInputEditText> mLongDataEditTexts = new ArrayList<>();

    // Scene Command
    protected TextInputEditText mSceneIdEditText;
    protected TextInputEditText mTypeEditText;
    protected TextInputEditText mPressEditText;
    protected TextInputEditText mModeEditText;
    protected TextInputEditText mDeviceEditText;
    protected TextInputEditText mSceneStateEditText;
    protected Button mBtnPressSingle;
    protected Button mBtnPressDouble;
    protected Button mBtnPressLong;
    protected Button mSceneSendButton;

    // Stature Command
    protected TextInputEditText mStatureIncDecEditText;
    protected TextInputEditText mStatureUpdateEditText;
    protected TextInputEditText mStatureDeviceCategoryEditText;
    protected TextInputEditText mStatureValueEditText;
    protected Button mStatureSendButton;

    private RecyclerView recyclerViewBoundKeys, recyclerViewSubscriptions;

    // ─────────────────────────────────────────────────────────────────────────
    // Activity Result Launchers
    // ─────────────────────────────────────────────────────────────────────────
    private final ActivityResultLauncher<Intent> appKeySelector = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    final ApplicationKey appKey = result.getData().getParcelableExtra(RESULT_KEY);
                    if (appKey != null) bindAppKey(appKey.getKeyIndex());
                }
            });

    private final ActivityResultLauncher<Intent> publicationSettings = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    final ApplicationKey appKey = result.getData().getParcelableExtra(RESULT_KEY);
                    if (appKey != null) bindAppKey(appKey.getKeyIndex());
                }
            });

    // ─────────────────────────────────────────────────────────────────────────
    // onCreate
    // ─────────────────────────────────────────────────────────────────────────
    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityModelConfigurationBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        Log.d(TAG, "onCreate ▶ start");

        // Containers
        mContainerShortLongCommands = findViewById(R.id.container_short_long_commands);
        mContainerSceneCommands     = findViewById(R.id.container_scene_commands);
        mGenericStatureCard          = findViewById(R.id.generic_stature_card);  // Initialize Stature card

        // Base views
        mContainer              = binding.container;
        mContainerAppKeyBinding = binding.appKeyCard;
        mActionBindAppKey       = binding.actionBindAppKey;
        mAppKeyView             = binding.boundKeys;
        mUnbindHint             = binding.unbindHint;
        mContainerPublication   = binding.publishAddressCard;
        mActionSetPublication   = binding.actionSetPublication;
        mActionClearPublication = binding.actionClearPublication;
        mPublishAddressView     = binding.publishAddress;
        mContainerSubscribe     = binding.subscriptionAddressCard;
        mActionSubscribe        = binding.actionSubscribeAddress;
        mSubscribeAddressView   = binding.subscribeAddresses;
        mSubscribeHint          = binding.subscribeHint;
        mProgressbar            = binding.configurationProgressBar;
        mSwipe                  = binding.swipeRefresh;

        // Short command
        mCommandEditText = binding.etCommand;
        mStateEditText   = binding.etState;
        mSendButton      = binding.actionOn;

        // Long command
        mLongSendButton      = binding.actionLongSend;
        mLongReadButton      = binding.actionLongReadState;
        mLengthEditText      = binding.etElementAddress;
        mLongAddressEditText = binding.etLongCommand;
        // ✅ Set default length to 0
        mLengthEditText.setText(String.valueOf(MIN_LENGTH));

        // Scene command
        mSceneIdEditText    = binding.etSceneId;
        mTypeEditText       = binding.etType;
        mPressEditText      = binding.etPress;
        mModeEditText       = binding.etMode;
        mDeviceEditText     = binding.etDevice;
        mSceneStateEditText = binding.etSceneState;
        mBtnPressSingle     = binding.btnPressSingle;
        mBtnPressDouble     = binding.btnPressDouble;
        mBtnPressLong       = binding.btnPressLong;
        mSceneSendButton    = binding.btnSend;

        // Stature command
        mStatureIncDecEditText        = binding.etStatureIncDec;
        mStatureUpdateEditText        = binding.etStatureUpdate;
        mStatureDeviceCategoryEditText = binding.etStatureDeviceCategory;
        mStatureValueEditText         = binding.etStatureValue;
        mStatureSendButton            = binding.actionSendGenericStature;

        mStatureSendButton.setOnClickListener(v -> sendGenericStatureCommand());

        Log.d(TAG, "onCreate ▶ all views bound");

        // Init controls
        initializeLongDataFields();
        initializeSceneControls();

        mViewModel = new ViewModelProvider(this).get(ModelConfigurationViewModel.class);
        initialize();

        final MeshModel meshModel = mViewModel.getSelectedModel().getValue();
        if (meshModel != null) {
            setSupportActionBar(binding.toolbar);
            if (getSupportActionBar() != null) {
                getSupportActionBar().setDisplayHomeAsUpEnabled(true);
                getSupportActionBar().setTitle(meshModel.getModelName());
                getSupportActionBar().setSubtitle(getString(R.string.model_id,
                        CompositionDataParser.formatModelIdentifier(meshModel.getModelId(), true)));
            }

            // RecyclerViews
            recyclerViewSubscriptions = findViewById(R.id.recycler_view_subscriptions);
            recyclerViewSubscriptions.setLayoutManager(new LinearLayoutManager(this));
            new ItemTouchHelper(new RemovableItemTouchHelperCallback(this))
                    .attachToRecyclerView(recyclerViewSubscriptions);
            mSubscriptionAdapter = new GroupAddressAdapter(this,
                    mViewModel.getNetworkLiveData().getMeshNetwork(), mViewModel.getSelectedModel());
            recyclerViewSubscriptions.setAdapter(mSubscriptionAdapter);

            recyclerViewBoundKeys = findViewById(R.id.recycler_view_bound_keys);
            recyclerViewBoundKeys.setLayoutManager(new LinearLayoutManager(this));
            recyclerViewBoundKeys.setItemAnimator(null);
            new ItemTouchHelper(new RemovableItemTouchHelperCallback(this))
                    .attachToRecyclerView(recyclerViewBoundKeys);
            mBoundAppKeyAdapter = new BoundAppKeysAdapter(this,
                    mViewModel.getNetworkLiveData().getAppKeys(), mViewModel.getSelectedModel());
            recyclerViewBoundKeys.setAdapter(mBoundAppKeyAdapter);

            // Listeners
            mActionBindAppKey.setOnClickListener(v -> {
                final ProvisionedMeshNode node = mViewModel.getSelectedMeshNode().getValue();
                if (node != null && !node.isExist(SigModelParser.CONFIGURATION_SERVER)) return;
                if (!checkConnectivity(mContainer)) return;
                final Intent i = new Intent(BaseModelConfigurationActivity.this, AppKeysActivity.class);
                i.putExtra(EXTRA_DATA, BIND_APP_KEY);
                appKeySelector.launch(i);
            });

            mSendButton.setOnClickListener(v -> sendGenericOnOffCommand());
            mLongSendButton.setOnClickListener(v -> sendLongBrightnessCommand());
            mLongReadButton.setOnClickListener(v -> readLongCommand());
            mSceneSendButton.setOnClickListener(v -> sendSceneCommand());

            mPublishAddressView.setText(R.string.none);
            mActionSetPublication.setOnClickListener(v -> navigateToPublication());
            mActionClearPublication.setOnClickListener(v -> clearPublication());

            mActionSubscribe.setOnClickListener(v -> {
                if (!checkConnectivity(mContainer)) return;
                final ArrayList<Group> groups = new ArrayList<>(
                        mViewModel.getNetworkLiveData().getMeshNetwork().getGroups());
                DialogFragmentGroupSubscription.newInstance(groups)
                        .show(getSupportFragmentManager(), null);
            });

            mViewModel.getTransactionStatus().observe(this, status -> {
                if (status != null) {
                    hideProgressBar();
                    DialogFragmentTransactionStatus
                            .newInstance("Transaction Failed", getString(R.string.operation_timed_out))
                            .show(getSupportFragmentManager(), null);
                }
            });
        }

        // Observers
        mViewModel.getSelectedMeshNode().observe(this, node -> tryAutoBind());
        mViewModel.getSelectedElement().observe(this, element -> tryAutoBind());
        mViewModel.getSelectedModel().observe(this, model -> {
            tryAutoBind();
            updateCommandCardsBasedOnModel(model);
            if (model != null && getSupportActionBar() != null) {
                getSupportActionBar().setTitle(model.getModelName());
                getSupportActionBar().setSubtitle(getString(R.string.model_id,
                        CompositionDataParser.formatModelIdentifier(model.getModelId(), true)));
            }
        });

        MeshModel init = mViewModel.getSelectedModel().getValue();
        if (init != null) updateCommandCardsBasedOnModel(init);

        Log.d(TAG, "onCreate ▶ done");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ✅ UPDATED: updateCommandCardsBasedOnModel — Shows Stature card only for Client model
    // ─────────────────────────────────────────────────────────────────────────
    private void updateCommandCardsBasedOnModel(MeshModel model) {
        if (model == null) {
            Log.w(TAG_MODEL, "model=null → all cards GONE");
            mContainerShortLongCommands.setVisibility(View.GONE);
            mContainerSceneCommands.setVisibility(View.GONE);
            mGenericStatureCard.setVisibility(View.GONE);  // Hide stature card
            return;
        }

        int modelId = model.getModelId();
        Log.d(TAG_MODEL, "══════════════════════════════");
        Log.d(TAG_MODEL, String.format("Model: %s  ID=0x%04X (%d)",
                model.getModelName(), modelId, modelId));

        // Define which models get which cards
        boolean showShortLong = (modelId == GENERIC_ONOFF_SERVER);
        boolean showScene = (modelId == GENERIC_ONOFF_CLIENT);
        boolean showStature = (modelId == GENERIC_ONOFF_CLIENT); // Only client model gets stature

        // Apply visibility
        mContainerShortLongCommands.setVisibility(showShortLong ? View.VISIBLE : View.GONE);
        mContainerSceneCommands.setVisibility(showScene ? View.VISIBLE : View.GONE);
        mGenericStatureCard.setVisibility(showStature ? View.VISIBLE : View.GONE);

        // Log what's being shown
        Log.d(TAG_MODEL, "→ Card visibility:");
        Log.d(TAG_MODEL, "   Short/Long: " + (showShortLong ? "VISIBLE" : "GONE"));
        Log.d(TAG_MODEL, "   Scene: " + (showScene ? "VISIBLE" : "GONE"));
        Log.d(TAG_MODEL, "   Stature: " + (showStature ? "VISIBLE" : "GONE"));
        Log.d(TAG_MODEL, "══════════════════════════════");
    }

    // Helper method to hide all cards
    private void hideAllCommandCards() {
        mContainerShortLongCommands.setVisibility(View.GONE);
        mContainerSceneCommands.setVisibility(View.GONE);
        mGenericStatureCard.setVisibility(View.GONE);
    }

    // Binary helpers
    private static String toBin8(int v) {
        return String.format("%8s", Integer.toBinaryString(v & 0xFF)).replace(' ', '0');
    }

    private static String toBin6(int v) {
        return String.format("%6s", Integer.toBinaryString(v & 0x3F)).replace(' ', '0');
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Scene Controls
    // ─────────────────────────────────────────────────────────────────────────
    private void initializeSceneControls() {
        mSceneIdEditText.setText("1");   mTypeEditText.setText("1");
        mPressEditText.setText(PRESS_TYPE_SINGLE);
        mModeEditText.setText("2");      mDeviceEditText.setText("1");
        mSceneStateEditText.setText("0");

        mBtnPressSingle.setOnClickListener(v -> {
            mPressEditText.setText(PRESS_TYPE_SINGLE);
            Toast.makeText(this, "Single Press", Toast.LENGTH_SHORT).show();
        });
        mBtnPressDouble.setOnClickListener(v -> {
            mPressEditText.setText(PRESS_TYPE_DOUBLE);
            Toast.makeText(this, "Double Press", Toast.LENGTH_SHORT).show();
        });
        mBtnPressLong.setOnClickListener(v -> {
            mPressEditText.setText(PRESS_TYPE_LONG);
            Toast.makeText(this, "Long Press", Toast.LENGTH_SHORT).show();
        });
        addSceneValidationListeners();
    }

    private void addSceneValidationListeners() {
        mSceneIdEditText.addTextChangedListener(new SimpleTextWatcher(this::validateSceneId));
        mTypeEditText.addTextChangedListener(new SimpleTextWatcher(this::validateType));
        mModeEditText.addTextChangedListener(new SimpleTextWatcher(this::validateMode));
        mDeviceEditText.addTextChangedListener(new SimpleTextWatcher(this::validateDevice));
        mSceneStateEditText.addTextChangedListener(new SimpleTextWatcher(this::validateSceneState));
    }

    private void validateSceneId()   { validateRange(mSceneIdEditText,    1, 240, "Scene ID must be 1–240"); }
    private void validateType()      { validateRange(mTypeEditText,        1, 39,  "Type must be 1–39");     }
    private void validateMode()      { validateRange(mModeEditText,        1, 8,   "Mode must be 1–8");      }
    private void validateDevice()    { validateRange(mDeviceEditText,      1, 8,   "no.nordicsemi.android.swaromesh.Device must be 1–8");    }
    private void validateSceneState(){ validateRange(mSceneStateEditText,  0, 3,   "State must be 0–3");     }

    private void validateRange(TextInputEditText et, int min, int max, String err) {
        try {
            String t = et.getText() != null ? et.getText().toString().trim() : "";
            if (!t.isEmpty()) et.setError((Integer.parseInt(t) < min || Integer.parseInt(t) > max) ? err : null);
        } catch (NumberFormatException e) { et.setError("Invalid value"); }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Auto Bind
    // ─────────────────────────────────────────────────────────────────────────
    private boolean isAutoBindTriggered = false;

    private void tryAutoBind() {
        if (isAutoBindTriggered) return;
        final ProvisionedMeshNode node    = mViewModel.getSelectedMeshNode().getValue();
        final Element             element = mViewModel.getSelectedElement().getValue();
        final MeshModel           model   = mViewModel.getSelectedModel().getValue();
        if (node == null || element == null || model == null) return;
        if (!node.isExist(SigModelParser.CONFIGURATION_SERVER)) return;
        if (!checkConnectivity(mContainer)) return;
        if (model.getBoundAppKeyIndexes() != null && !model.getBoundAppKeyIndexes().isEmpty()) return;
        final List<ApplicationKey> keys = mViewModel.getNetworkLiveData().getAppKeys();
        if (keys == null || keys.isEmpty()) return;
        isAutoBindTriggered = true;
        Log.d(TAG, "tryAutoBind ▶ binding key index " + keys.get(0).getKeyIndex());
        sendAcknowledgedMessage(node.getUnicastAddress(),
                new ConfigModelAppBind(element.getElementAddress(), model.getModelId(), keys.get(0).getKeyIndex()));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ✅ UPDATED: Long Data Fields - Always show all 8 fields
    // ─────────────────────────────────────────────────────────────────────────
    private void initializeLongDataFields() {
        mLongDataFields.add(binding.layoutLongData1);
        mLongDataFields.add(binding.layoutLongData2);
        mLongDataFields.add(binding.layoutLongData3);
        mLongDataFields.add(binding.layoutLongData4);
        mLongDataFields.add(binding.layoutLongData5);
        mLongDataFields.add(binding.layoutLongData6);
        mLongDataFields.add(binding.layoutLongData7);
        mLongDataFields.add(binding.layoutLongData8);

        mLongDataEditTexts.add(binding.etLongData1);
        mLongDataEditTexts.add(binding.etLongData2);
        mLongDataEditTexts.add(binding.etLongData3);
        mLongDataEditTexts.add(binding.etLongData4);
        mLongDataEditTexts.add(binding.etLongData5);
        mLongDataEditTexts.add(binding.etLongData6);
        mLongDataEditTexts.add(binding.etLongData7);
        mLongDataEditTexts.add(binding.etLongData8);

        // ✅ Always show all 8 brightness fields
        for (int i = 0; i < 8; i++) {
            mLongDataEditTexts.get(i).setText(String.valueOf(DEFAULT_BRIGHTNESS_VALUE));
            final int idx = i;
            mLongDataEditTexts.get(i).addTextChangedListener(
                    new SimpleTextWatcher(() -> validateBrightnessField(idx)));

            // Set IME options
            if (i < 7) {
                mLongDataEditTexts.get(i).setImeOptions(EditorInfo.IME_ACTION_NEXT);
            } else {
                mLongDataEditTexts.get(i).setImeOptions(EditorInfo.IME_ACTION_DONE);
            }

            // ✅ Always visible
            mLongDataFields.get(i).setVisibility(View.VISIBLE);
        }
    }

    private void validateBrightnessField(int idx) {
        try {
            String t = mLongDataEditTexts.get(idx).getText().toString().trim();
            if (!t.isEmpty()) {
                int b = Integer.parseInt(t);
                mLongDataFields.get(idx).setError((b < 0 || b > 255) ? "Brightness must be 0–255" : null);
            }
        } catch (NumberFormatException e) {
            mLongDataFields.get(idx).setError("Invalid value");
        }
    }

    private void readLongCommand() {
        Log.d(TAG_LIGHT, "readLongCommand ▶ TODO not yet implemented");
        mViewModel.displaySnackBar(this, mContainer, "Reading from device...", Snackbar.LENGTH_SHORT);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────
    @Override protected void onStart() { super.onStart(); mViewModel.setActivityVisible(true); }
    @Override protected void onStop()  {
        super.onStop(); mViewModel.setActivityVisible(false);
        if (isFinishing()) mHandler.removeCallbacksAndMessages(null);
    }
    @Override protected void onSaveInstanceState(@NonNull Bundle out) {
        super.onSaveInstanceState(out);
        out.putBoolean(PROGRESS_BAR_STATE, mProgressbar.getVisibility() == View.VISIBLE);
    }
    @Override protected void onRestoreInstanceState(@NonNull Bundle in) {
        super.onRestoreInstanceState(in);
        if (in.getBoolean(PROGRESS_BAR_STATE)) { mProgressbar.setVisibility(View.VISIBLE); disableClickableViews(); }
        else { mProgressbar.setVisibility(View.INVISIBLE); enableClickableViews(); }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GroupCallbacks
    // ─────────────────────────────────────────────────────────────────────────
    @Override public Group createGroup(@NonNull String name) {
        MeshNetwork n = mViewModel.getNetworkLiveData().getMeshNetwork();
        return n.createGroup(n.getSelectedProvisioner(), name);
    }
    @Override public Group createGroup(@NonNull UUID uuid, String name) {
        MeshNetwork n = mViewModel.getNetworkLiveData().getMeshNetwork();
        return n.createGroup(uuid, null, name);
    }
    @Override public boolean onGroupAdded(@NonNull String name, int address) {
        MeshNetwork n = mViewModel.getNetworkLiveData().getMeshNetwork();
        Group g = n.createGroup(n.getSelectedProvisioner(), address, name);
        if (n.addGroup(g)) { subscribe(g); return true; } return false;
    }
    @Override public boolean onGroupAdded(@NonNull Group group) {
        MeshNetwork n = mViewModel.getNetworkLiveData().getMeshNetwork();
        if (n.addGroup(group)) { subscribe(group); return true; } return false;
    }
    @Override public void subscribe(Group group) {
        ProvisionedMeshNode node = mViewModel.getSelectedMeshNode().getValue(); if (node == null) return;
        Element el = mViewModel.getSelectedElement().getValue(); if (el == null) return;
        MeshModel m = mViewModel.getSelectedModel().getValue(); if (m == null) return;
        MeshMessage msg = group.getAddressLabel() == null
                ? new ConfigModelSubscriptionAdd(el.getElementAddress(), group.getAddress(), m.getModelId())
                : new ConfigModelSubscriptionVirtualAddressAdd(el.getElementAddress(), group.getAddressLabel(), m.getModelId());
        sendAcknowledgedMessage(node.getUnicastAddress(), msg);
    }
    @Override public void subscribe(int address) {
        ProvisionedMeshNode node = mViewModel.getSelectedMeshNode().getValue(); if (node == null) return;
        Element el = mViewModel.getSelectedElement().getValue(); if (el == null) return;
        MeshModel m = mViewModel.getSelectedModel().getValue(); if (m == null) return;
        sendAcknowledgedMessage(node.getUnicastAddress(),
                new ConfigModelSubscriptionAdd(el.getElementAddress(), address, m.getModelId()));
    }
    @Override public void onItemDismiss(RemovableViewHolder vh) {
        int pos = vh.getAbsoluteAdapterPosition();
        if (vh instanceof BoundAppKeysAdapter.ViewHolder) unbindAppKey(pos);
        else if (vh instanceof GroupAddressAdapter.ViewHolder) deleteSubscription(pos);
    }
    @Override public void onItemDismissFailed(RemovableViewHolder vh) {}
    @Override public void onDisconnected() { finish(); }

    // ─────────────────────────────────────────────────────────────────────────
    // onRefresh
    // ─────────────────────────────────────────────────────────────────────────
    @Override public void onRefresh() {
        final MeshModel model = mViewModel.getSelectedModel().getValue();
        if (!checkConnectivity(mContainer) || model == null) { mSwipe.setRefreshing(false); return; }
        final ProvisionedMeshNode node = mViewModel.getSelectedMeshNode().getValue();
        final Element element = mViewModel.getSelectedElement().getValue();
        if (node == null || element == null) return;
        if (model instanceof SigModel) {
            if (!(model instanceof ConfigurationServerModel) && !(model instanceof ConfigurationClientModel)) {
                mViewModel.displaySnackBar(this, mContainer, getString(R.string.listing_model_configuration), Snackbar.LENGTH_LONG);
                mViewModel.getMessageQueue().add(new ConfigSigModelAppGet(element.getElementAddress(), model.getModelId()));
                if (model.getModelId() != SigModelParser.SCENE_SETUP_SERVER) {
                    mViewModel.getMessageQueue().add(new ConfigSigModelSubscriptionGet(element.getElementAddress(), model.getModelId()));
                    queuePublicationGetMessage(element.getElementAddress(), model.getModelId());
                }
                sendQueuedMessage(node.getUnicastAddress());
            } else { mSwipe.setRefreshing(false); }
        } else {
            mViewModel.displaySnackBar(this, mContainer, getString(R.string.listing_model_configuration), Snackbar.LENGTH_LONG);
            mViewModel.getMessageQueue().add(new ConfigVendorModelAppGet(element.getElementAddress(), model.getModelId()));
            mViewModel.getMessageQueue().add(new ConfigVendorModelSubscriptionGet(element.getElementAddress(), model.getModelId()));
            queuePublicationGetMessage(element.getElementAddress(), model.getModelId());
            sendQueuedMessage(node.getUnicastAddress());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Mesh Helpers
    // ─────────────────────────────────────────────────────────────────────────
    protected final void sendQueuedMessage(int address) {
        MeshMessage m = mViewModel.getMessageQueue().peek();
        if (m != null) sendAcknowledgedMessage(address, m);
    }
    protected void navigateToPublication() {
        MeshModel model = mViewModel.getSelectedModel().getValue();
        if (model != null && !model.getBoundAppKeyIndexes().isEmpty())
            publicationSettings.launch(new Intent(this, PublicationSettingsActivity.class));
        else mViewModel.displaySnackBar(this, mContainer, getString(R.string.error_no_app_keys_bound), Snackbar.LENGTH_LONG);
    }
    private void bindAppKey(int idx) {
        ProvisionedMeshNode node = mViewModel.getSelectedMeshNode().getValue(); if (node == null) return;
        Element el = mViewModel.getSelectedElement().getValue(); if (el == null) return;
        MeshModel m = mViewModel.getSelectedModel().getValue(); if (m == null) return;
        sendAcknowledgedMessage(node.getUnicastAddress(), new ConfigModelAppBind(el.getElementAddress(), m.getModelId(), idx));
    }
    private void unbindAppKey(int pos) {
        if (mBoundAppKeyAdapter.getItemCount() == 0) return;
        if (!checkConnectivity(mContainer)) { mBoundAppKeyAdapter.notifyItemChanged(pos); return; }
        ProvisionedMeshNode node = mViewModel.getSelectedMeshNode().getValue(); if (node == null) return;
        Element el = mViewModel.getSelectedElement().getValue(); if (el == null) return;
        MeshModel m = mViewModel.getSelectedModel().getValue(); if (m == null) return;
        sendAcknowledgedMessage(node.getUnicastAddress(), new ConfigModelAppUnbind(
                el.getElementAddress(), m.getModelId(), mBoundAppKeyAdapter.getAppKey(pos).getKeyIndex()));
    }
    private void clearPublication() {
        ProvisionedMeshNode node = mViewModel.getSelectedMeshNode().getValue(); if (node == null) return;
        Element el = mViewModel.getSelectedElement().getValue(); if (el == null) return;
        MeshModel m = mViewModel.getSelectedModel().getValue(); if (m == null) return;
        sendAcknowledgedMessage(node.getUnicastAddress(), new ConfigModelPublicationSet(el.getElementAddress(), m.getModelId()));
    }
    private void deleteSubscription(int pos) {
        if (mSubscriptionAdapter.getItemCount() == 0) return;
        if (!checkConnectivity(mContainer)) { mSubscriptionAdapter.notifyItemChanged(pos); return; }
        int addr = mGroupAddress.get(pos);
        ProvisionedMeshNode node = mViewModel.getSelectedMeshNode().getValue(); if (node == null) return;
        Element el = mViewModel.getSelectedElement().getValue(); if (el == null) return;
        MeshModel m = mViewModel.getSelectedModel().getValue(); if (m == null) return;
        MeshMessage msg = null;
        if (isValidGroupAddress(addr)) msg = new ConfigModelSubscriptionDelete(el.getElementAddress(), addr, m.getModelId());
        else { UUID u = m.getLabelUUID(addr); if (u != null) msg = new ConfigModelSubscriptionVirtualAddressDelete(el.getElementAddress(), u, m.getModelId()); }
        if (msg != null) sendAcknowledgedMessage(node.getUnicastAddress(), msg);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Progress Bar
    // ─────────────────────────────────────────────────────────────────────────
    @Override protected final void showProgressBar() {
        mHandler.postDelayed(mRunnableOperationTimeout, MESSAGE_TIME_OUT);
        disableClickableViews(); mProgressbar.setVisibility(View.VISIBLE);
    }
    @Override protected final void hideProgressBar() {
        mSwipe.setRefreshing(false); enableClickableViews();
        mProgressbar.setVisibility(View.INVISIBLE);
        mHandler.removeCallbacks(mRunnableOperationTimeout);
    }
    @Override protected void enableClickableViews() {
        mActionBindAppKey.setEnabled(true); mActionSetPublication.setEnabled(true);
        mActionClearPublication.setEnabled(true); mActionSubscribe.setEnabled(true);
        if (mActionSetRelayState != null) mActionSetRelayState.setEnabled(true);
        if (mSetNetworkTransmitStateButton != null) mSetNetworkTransmitStateButton.setEnabled(true);
        if (mActionRead != null && !mActionRead.isEnabled()) mActionRead.setEnabled(true);
    }
    @Override protected void disableClickableViews() {
        mActionBindAppKey.setEnabled(false); mActionSetPublication.setEnabled(false);
        mActionClearPublication.setEnabled(false); mActionSubscribe.setEnabled(false);
        if (mActionSetRelayState != null) mActionSetRelayState.setEnabled(false);
        if (mSetNetworkTransmitStateButton != null) mSetNetworkTransmitStateButton.setEnabled(false);
        if (mActionRead != null) mActionRead.setEnabled(false);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UI Updates
    // ─────────────────────────────────────────────────────────────────────────
    protected void updateAppStatusUi(MeshModel mm) {
        List<Integer> keys = mm.getBoundAppKeyIndexes(); mKeyIndexes.clear(); mKeyIndexes.addAll(keys);
        if (!keys.isEmpty()) { mUnbindHint.setVisibility(View.VISIBLE); mAppKeyView.setVisibility(View.GONE); recyclerViewBoundKeys.setVisibility(View.VISIBLE); }
        else { mUnbindHint.setVisibility(View.GONE); mAppKeyView.setVisibility(View.VISIBLE); recyclerViewBoundKeys.setVisibility(View.GONE); }
    }
    protected void updatePublicationUi(MeshModel mm) {
        PublicationSettings ps = mm.getPublicationSettings();
        if (ps != null) {
            int addr = ps.getPublishAddress();
            if (isValidVirtualAddress(addr)) { UUID u = ps.getLabelUUID(); mPublishAddressView.setText(u != null ? u.toString().toUpperCase(Locale.US) : formatAddress(addr, true)); }
            else mPublishAddressView.setText(formatAddress(addr, true));
            mActionClearPublication.setVisibility(View.VISIBLE);
        } else { mPublishAddressView.setText(R.string.none); mActionClearPublication.setVisibility(View.GONE); }
    }
    protected void updateSubscriptionUi(MeshModel mm) {
        List<Integer> subs = mm.getSubscribedAddresses(); mGroupAddress.clear(); mGroupAddress.addAll(subs);
        if (!subs.isEmpty()) { mSubscribeHint.setVisibility(View.VISIBLE); mSubscribeAddressView.setVisibility(View.GONE); recyclerViewSubscriptions.setVisibility(View.VISIBLE); }
        else { mSubscribeHint.setVisibility(View.GONE); mSubscribeAddressView.setVisibility(View.VISIBLE); recyclerViewSubscriptions.setVisibility(View.GONE); }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Message Senders
    // ─────────────────────────────────────────────────────────────────────────
    protected void sendMessage(@NonNull MeshMessage msg) {
        try {
            if (!checkConnectivity(mContainer)) return;
            ProvisionedMeshNode node = mViewModel.getSelectedMeshNode().getValue();
            if (node != null) { mViewModel.getMeshManagerApi().createMeshPdu(node.getUnicastAddress(), msg); showProgressBar(); }
        } catch (IllegalArgumentException ex) {
            hideProgressBar();
            DialogFragmentError.newInstance(getString(R.string.title_error), ex.getMessage() == null ? getString(R.string.unknwon_error) : ex.getMessage())
                    .show(getSupportFragmentManager(), null);
        }
    }
    protected boolean handleStatuses() {
        MeshMessage m = mViewModel.getMessageQueue().peek();
        if (m != null) { sendMessage(m); return true; }
        mViewModel.displaySnackBar(this, mContainer, getString(R.string.operation_success), Snackbar.LENGTH_SHORT);
        return false;
    }
    protected void sendAcknowledgedMessage(int address, @NonNull MeshMessage msg) {
        try {
            if (!checkConnectivity(mContainer)) return;
            mViewModel.getMeshManagerApi().createMeshPdu(address, msg); showProgressBar();
        } catch (IllegalArgumentException ex) {
            hideProgressBar();
            DialogFragmentError.newInstance(getString(R.string.title_error), ex.getMessage() == null ? getString(R.string.unknwon_error) : ex.getMessage())
                    .show(getSupportFragmentManager(), null);
        }
    }
    protected void sendUnacknowledgedMessage(int address, @NonNull MeshMessage msg) {
        try {
            if (!checkConnectivity(mContainer)) return;
            mViewModel.getMeshManagerApi().createMeshPdu(address, msg);
        } catch (IllegalArgumentException ex) {
            DialogFragmentError.newInstance(getString(R.string.title_error), ex.getMessage() == null ? getString(R.string.unknwon_error) : ex.getMessage())
                    .show(getSupportFragmentManager(), null);
        }
    }
    protected void updateClickableViews() {
        MeshModel m = mViewModel.getSelectedModel().getValue();
        if (m != null && m.getModelId() == SigModelParser.CONFIGURATION_CLIENT) disableClickableViews();
    }
    protected void queuePublicationGetMessage(int address, int modelId) {
        mViewModel.getMessageQueue().add(new ConfigModelPublicationGet(address, modelId));
    }
    protected void displayStatusDialogFragment(@NonNull String title, @NonNull String message) {
        if (mViewModel.isActivityVisible())
            DialogFragmentConfigStatus.newInstance(title, message)
                    .show(getSupportFragmentManager(), DIALOG_FRAGMENT_CONFIGURATION_STATUS);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TID Counters
    // ─────────────────────────────────────────────────────────────────────────
    private int getNextGenericOnOffTid() {
        int c = genericOnOffTidCounter.getAndIncrement();
        if (c > MAX_TID) { genericOnOffTidCounter.set(0); c = 0; }
        Log.d(TAG_TID, "OnOff TID=" + c); return c;
    }
    private int getNextGenericLightTid() {
        int c = genericLightTidCounter.getAndIncrement();
        if (c > MAX_TID) { genericLightTidCounter.set(0); c = 0; }
        Log.d(TAG_TID, "Light TID=" + c); return c;
    }
    private int getNextSceneTid() {
        int c = sceneTidCounter.getAndIncrement();
        if (c > MAX_TID) { sceneTidCounter.set(0); c = 0; }
        Log.d(TAG_TID, "Scene TID=" + c); return c;
    }
    public void resetTidCounters() {
        genericOnOffTidCounter.set(0); genericLightTidCounter.set(0); sceneTidCounter.set(0);
        Log.d(TAG_TID, "All TIDs reset to 0");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // sendGenericOnOffCommand
    // ─────────────────────────────────────────────────────────────────────────
    private void sendGenericOnOffCommand() {
        final ProvisionedMeshNode node  = mViewModel.getSelectedMeshNode().getValue();
        final MeshModel           model = mViewModel.getSelectedModel().getValue();
        if (node == null || model == null) {
            mViewModel.displaySnackBar(this, mContainer, "Node/Model not selected", Snackbar.LENGTH_SHORT);
            return;
        }
        final String cs = mCommandEditText.getText() != null ? mCommandEditText.getText().toString().trim() : "";
        final String ss = mStateEditText.getText()   != null ? mStateEditText.getText().toString().trim()   : "";
        if (cs.isEmpty() || ss.isEmpty()) {
            mViewModel.displaySnackBar(this, mContainer, "Enter command and state", Snackbar.LENGTH_SHORT);
            return;
        }
        try {
            int cmd = Integer.parseInt(cs), state = Integer.parseInt(ss);
            if (cmd < 0 || cmd > 255) {
                mViewModel.displaySnackBar(this, mContainer, "Command 0–255", Snackbar.LENGTH_SHORT);
                return;
            }
            if (state < 0 || state > 255) {
                mViewModel.displaySnackBar(this, mContainer, "State 0–255", Snackbar.LENGTH_SHORT);
                return;
            }
            List<Integer> keys = model.getBoundAppKeyIndexes();
            if (keys.isEmpty()) {
                mViewModel.displaySnackBar(this, mContainer, "Bind AppKey first", Snackbar.LENGTH_SHORT);
                return;
            }
            ApplicationKey appKey = null;
            for (ApplicationKey k : mViewModel.getNetworkLiveData().getAppKeys())
                if (k.getKeyIndex() == keys.get(0)) { appKey = k; break; }
            if (appKey == null) {
                mViewModel.displaySnackBar(this, mContainer, "AppKey not found", Snackbar.LENGTH_SHORT);
                return;
            }
            int tid = getNextGenericOnOffTid();
            Log.d(TAG_ONOFF, String.format("══ GenericOnOffSet cmd=0x%02X state=0x%02X tid=%d ══", cmd, state, tid));
            sendAcknowledgedMessage(node.getUnicastAddress(), new GenericOnOffSet(appKey, cmd, state, tid));
        } catch (NumberFormatException e) {
            mViewModel.displaySnackBar(this, mContainer, "Invalid number", Snackbar.LENGTH_SHORT);
        }
    }

// ─────────────────────────────────────────────────────────────────────────
// ✅ UPDATED: sendLongBrightnessCommand - Always send 8 brightness values
// ─────────────────────────────────────────────────────────────────────────
    private void sendLongBrightnessCommand() {
        final ProvisionedMeshNode node    = mViewModel.getSelectedMeshNode().getValue();
        final Element             element = mViewModel.getSelectedElement().getValue();
        final MeshModel           model   = mViewModel.getSelectedModel().getValue();
        if (node == null || element == null || model == null) {
            mViewModel.displaySnackBar(this, mContainer, "Node/Element/Model not selected", Snackbar.LENGTH_SHORT);
            return;
        }
        try {
            String ls = mLengthEditText.getText().toString().trim();
            if (ls.isEmpty()) {
                mViewModel.displaySnackBar(this, mContainer, "Enter length (0–255)", Snackbar.LENGTH_SHORT);
                return;
            }
            int length = Integer.parseInt(ls);

            // ✅ Length validation 0-255
            if (length < MIN_LENGTH || length > MAX_LENGTH) {
                mViewModel.displaySnackBar(this, mContainer, "Length must be 0–255", Snackbar.LENGTH_SHORT);
                return;
            }

            String cs = mLongAddressEditText.getText().toString().trim();
            if (cs.isEmpty()) {
                mViewModel.displaySnackBar(this, mContainer, "Enter command", Snackbar.LENGTH_SHORT);
                return;
            }
            int command = Integer.parseInt(cs);
            if (command < 0 || command > 255) {
                mViewModel.displaySnackBar(this, mContainer, "Command 0–255", Snackbar.LENGTH_SHORT);
                return;
            }

            // ✅ ALWAYS USE 8 BRIGHTNESS VALUES (hardcoded to 8)
            int[] brightness = new int[8]; // Always 8 values

            // Read values from all 8 UI fields
            for (int i = 0; i < 8; i++) {
                String vs = mLongDataEditTexts.get(i).getText().toString().trim();
                if (vs.isEmpty()) {
                    mViewModel.displaySnackBar(this, mContainer,
                            "Enter brightness for field " + (i+1), Snackbar.LENGTH_SHORT);
                    return;
                }
                brightness[i] = Integer.parseInt(vs);

                if (brightness[i] < 0 || brightness[i] > 255) {
                    mViewModel.displaySnackBar(this, mContainer,
                            "Brightness for field " + (i+1) + " must be 0–255", Snackbar.LENGTH_SHORT);
                    return;
                }
            }

            List<Integer> bk = model.getBoundAppKeyIndexes();
            if (bk.isEmpty()) {
                mViewModel.displaySnackBar(this, mContainer, "No AppKey bound", Snackbar.LENGTH_SHORT);
                return;
            }
            ApplicationKey appKey = mViewModel.getNetworkLiveData().getMeshNetwork().getAppKey(bk.get(0));
            if (appKey == null) {
                mViewModel.displaySnackBar(this, mContainer, "AppKey not found", Snackbar.LENGTH_SHORT);
                return;
            }

            int tid = getNextGenericLightTid();

            // ✅ Send ALL 8 brightness values regardless of length
            // The length parameter tells how many of these values are actually used
            GenericLightSet msg = new GenericLightSet(appKey, length, command, brightness, tid);

            // Log message with clear information
            Log.d(TAG_LIGHT, String.format("══ GenericLightSet len=%d cmd=0x%02X ALL_8_VALUES=%s tid=%d ══",
                    length, command, Arrays.toString(brightness), tid));

            mViewModel.displaySnackBar(this, mContainer,
                    String.format("Sending LEN=%d CMD=0x%02X with all 8 brightness values TID=%d",
                            length, command, tid), Snackbar.LENGTH_LONG);

            sendAcknowledgedMessage(node.getUnicastAddress(), msg);

        } catch (Exception e) {
            Log.e(TAG_LIGHT, "Error", e);
            mViewModel.displaySnackBar(this, mContainer, "Failed: " + e.getMessage(), Snackbar.LENGTH_SHORT);
        }
    }
    // ─────────────────────────────────────────────────────────────────────────
    // sendSceneCommand
    // ─────────────────────────────────────────────────────────────────────────
    private void sendSceneCommand() {
        final ProvisionedMeshNode node    = mViewModel.getSelectedMeshNode().getValue();
        final Element             element = mViewModel.getSelectedElement().getValue();
        final MeshModel           model   = mViewModel.getSelectedModel().getValue();
        if (node == null || element == null || model == null) {
            mViewModel.displaySnackBar(this, mContainer, "Node/Element/Model not selected", Snackbar.LENGTH_SHORT);
            return;
        }
        try {
            int sceneId = parseAndValidateInt(mSceneIdEditText, "Scene ID", 0, 255);
            int type    = parseAndValidateInt(mTypeEditText,    "Type",     0, 63);
            int mode    = parseAndValidateInt(mModeEditText,    "Mode",     0, 7);
            int device  = parseAndValidateInt(mDeviceEditText,  "no.nordicsemi.android.swaromesh.Device",   0, 7);
            int state   = parseAndValidateInt(mSceneStateEditText, "State", 0, 3);
            String ps = mPressEditText.getText() != null ? mPressEditText.getText().toString().trim() : "";
            int pressCode = GenericSceneSet.getPressTypeCode(ps);
            if (pressCode < 0 || pressCode > 3) {
                mViewModel.displaySnackBar(this, mContainer, "Invalid press type", Snackbar.LENGTH_SHORT);
                return;
            }
            int tid = getNextSceneTid() & 0xFF;
            List<Integer> bk = model.getBoundAppKeyIndexes();
            if (bk == null || bk.isEmpty()) {
                mViewModel.displaySnackBar(this, mContainer, "No AppKey bound", Snackbar.LENGTH_SHORT);
                return;
            }
            ApplicationKey appKey = mViewModel.getNetworkLiveData().getMeshNetwork().getAppKey(bk.get(0));
            if (appKey == null) {
                mViewModel.displaySnackBar(this, mContainer, "AppKey not found", Snackbar.LENGTH_SHORT);
                return;
            }
            GenericSceneSet sceneMsg = new GenericSceneSet(appKey, sceneId, type, pressCode, mode, device, state, tid);
            if (!verifyMessageStructure(sceneMsg, sceneId, type, pressCode, mode, device, state, tid)) {
                mViewModel.displaySnackBar(this, mContainer, "Verification failed", Snackbar.LENGTH_SHORT);
                return;
            }
            logSceneCommand(sceneMsg, element.getElementAddress(), mViewModel.getNetworkLiveData().getMeshNetwork());
            sendUnacknowledgedMessage(element.getElementAddress(), sceneMsg);
            mViewModel.displaySnackBar(this, mContainer,
                    String.format("Scene sent → 0x%04X", element.getElementAddress()), Snackbar.LENGTH_LONG);
        } catch (NumberFormatException e) {
            mViewModel.displaySnackBar(this, mContainer, "Invalid number", Snackbar.LENGTH_SHORT);
        } catch (IllegalArgumentException e) {
            mViewModel.displaySnackBar(this, mContainer, e.getMessage(), Snackbar.LENGTH_SHORT);
        } catch (Exception e) {
            Log.e(TAG_SCENE, "Failed", e);
            mViewModel.displaySnackBar(this, mContainer, "Failed: " + e.getMessage(), Snackbar.LENGTH_SHORT);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // sendGenericStatureCommand - Correct bit shifting (1 bit, 1 bit, 6 bit)
    // ─────────────────────────────────────────────────────────────────────────
    private void sendGenericStatureCommand() {
        final ProvisionedMeshNode node    = mViewModel.getSelectedMeshNode().getValue();
        final Element             element = mViewModel.getSelectedElement().getValue();
        final MeshModel           model   = mViewModel.getSelectedModel().getValue();

        if (node == null || element == null || model == null) {
            mViewModel.displaySnackBar(
                    this,
                    mContainer,
                    "Node / Element / Model not selected",
                    Snackbar.LENGTH_SHORT
            );
            return;
        }

        try {
            // 1️⃣ Read and validate inputs from UI
            int incDec = parseAndValidateInt(
                    mStatureIncDecEditText,
                    "Increment/Decrement (0=Dec, 1=Inc)", 0, 1);

            int updateType = parseAndValidateInt(
                    mStatureUpdateEditText,
                    "Update Type (0=Category, 1=no.nordicsemi.android.swaromesh.Device)", 0, 1);

            int deviceCategory = parseAndValidateInt(
                    mStatureDeviceCategoryEditText,
                    "no.nordicsemi.android.swaromesh.Device/Category ID", 0, 63); // 6 bits

            int stepValue = parseAndValidateInt(
                    mStatureValueEditText,
                    "Value/Step", 0, 255); // 8 bits

            boolean isIncrement = (incDec == 1);
            boolean isUpdate    = (updateType == 1);

            // 2️⃣ Get AppKey
            List<Integer> keys = model.getBoundAppKeyIndexes();
            if (keys == null || keys.isEmpty()) {
                mViewModel.displaySnackBar(
                        this,
                        mContainer,
                        "No AppKey bound - bind an app key first",
                        Snackbar.LENGTH_LONG
                );
                return;
            }

            ApplicationKey appKey = mViewModel
                    .getNetworkLiveData()
                    .getMeshNetwork()
                    .getAppKey(keys.get(0));

            if (appKey == null) {
                mViewModel.displaySnackBar(
                        this,
                        mContainer,
                        "AppKey not found in network",
                        Snackbar.LENGTH_SHORT
                );
                return;
            }

            // 3️⃣ Create Generic Stature message
            GenericStatureSet msg = new GenericStatureSet(
                    appKey,
                    isIncrement,
                    isUpdate,
                    deviceCategory,
                    stepValue
            );

            // 4️⃣ Build control byte (for logging)
            int controlByte = 0;

            if (isIncrement) {
                controlByte |= (1 << 7); // Bit 7
            }

            if (isUpdate) {
                controlByte |= (1 << 6); // Bit 6
            }


            controlByte |= (deviceCategory & 0x3F); // Bits 0–5

            // 5️⃣ LOGGING (ELEMENT ADDRESS)
            int dst = element.getElementAddress();

            Log.d(TAG_STATURE, "══════════════════════════════════════");
            Log.d(TAG_STATURE, "🔷 GENERIC STATURE SET COMMAND");
            Log.d(TAG_STATURE, "══════════════════════════════════════");
            Log.d(TAG_STATURE, String.format(
                    "📌 Destination (Element): 0x%04X (%d)", dst, dst));
            Log.d(TAG_STATURE, String.format(
                    "📌 Model: %s (0x%04X)",
                    model.getModelName(), model.getModelId()));
            Log.d(TAG_STATURE, "══════════════════════════════════════");

            Log.d(TAG_STATURE, "📊 INPUT VALUES:");
            Log.d(TAG_STATURE, String.format(
                    "   • Increment/Decrement : %s (%d)",
                    isIncrement ? "INCREMENT (1)" : "DECREMENT (0)", incDec));
            Log.d(TAG_STATURE, String.format(
                    "   • Update Type         : %s (%d)",
                    isUpdate ? "DEVICE (1)" : "CATEGORY (0)", updateType));
            Log.d(TAG_STATURE, String.format(
                    "   • no.nordicsemi.android.swaromesh.Device/Category ID  : %d (0x%02X)",
                    deviceCategory, deviceCategory));
            Log.d(TAG_STATURE, String.format(
                    "   • Value/Step          : %d (0x%02X)",
                    stepValue, stepValue));

            Log.d(TAG_STATURE, "══════════════════════════════════════");
            Log.d(TAG_STATURE, "📦 MESSAGE PAYLOAD:");
            Log.d(TAG_STATURE, String.format(
                    "   BYTE 0 (Control) : %s (0x%02X)",
                    toBin8(controlByte), controlByte));
            Log.d(TAG_STATURE, String.format(
                    "   BYTE 1 (Value)   : %s (0x%02X)",
                    toBin8(stepValue), stepValue));
            Log.d(TAG_STATURE, String.format(
                    "   📦 Full Payload  : [0x%02X, 0x%02X]",
                    controlByte, stepValue));
            Log.d(TAG_STATURE, "══════════════════════════════════════");

            // 6️⃣ User feedback
            mViewModel.displaySnackBar(
                    this,
                    mContainer,
                    String.format(
                            "Sending to element 0x%04X → %s %s #%d by %d",
                            dst,
                            isIncrement ? "INC" : "DEC",
                            isUpdate ? "DEVICE" : "CATEGORY",
                            deviceCategory,
                            stepValue),
                    Snackbar.LENGTH_LONG
            );

            // 7️⃣ SEND MESSAGE → ELEMENT ADDRESS ✅
            sendAcknowledgedMessage(dst, msg);

        } catch (IllegalArgumentException e) {
            Log.e(TAG_STATURE, "Validation error", e);
            mViewModel.displaySnackBar(
                    this,
                    mContainer,
                    "Error: " + e.getMessage(),
                    Snackbar.LENGTH_LONG
            );
        } catch (Exception e) {
            Log.e(TAG_STATURE, "Failed to send Generic Stature command", e);
            mViewModel.displaySnackBar(
                    this,
                    mContainer,
                    "Failed to send: " + e.getMessage(),
                    Snackbar.LENGTH_SHORT
            );
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────
    private int parseAndValidateInt(TextInputEditText et, String name, int min, int max) {
        String t = et.getText() != null ? et.getText().toString().trim() : "";
        if (t.isEmpty()) throw new IllegalArgumentException(name + " cannot be empty");
        int v = Integer.parseInt(t);
        if (v < min || v > max) throw new IllegalArgumentException(String.format("%s must be %d–%d", name, min, max));
        return v;
    }

    private boolean verifyMessageStructure(GenericSceneSet msg, int sId, int type, int press, int mode, int dev, int state, int tid) {
        byte[] p = msg.getParameters();
        if (p == null || p.length != 4) { Log.e(TAG_SCENE, "Invalid params"); return false; }
        if ((p[0]&0xFF) != sId) { Log.e(TAG_SCENE, "SceneID mismatch"); return false; }
        if (((p[1]>>2)&0x3F) != type || (p[1]&0x03) != press) { Log.e(TAG_SCENE, "Type/Press mismatch"); return false; }
        if (((p[2]>>5)&0x07) != mode || ((p[2]>>2)&0x07) != dev || (p[2]&0x03) != state) { Log.e(TAG_SCENE, "Mode/Dev/State mismatch"); return false; }
        if ((p[3]&0xFF) != tid) { Log.e(TAG_SCENE, "TID mismatch"); return false; }
        Log.d(TAG_SCENE, "Verify PASSED ✓"); return true;
    }

    private void logSceneCommand(GenericSceneSet msg, int addr, MeshNetwork net) {
        byte[] p = msg.getParameters();
        Log.d(TAG_SCENE, "══ GenericSceneSet ══");
        Log.d(TAG_SCENE, String.format("Dest=0x%04X  Full=[%02X %02X %02X %02X]",
                addr, p[0]&0xFF, p[1]&0xFF, p[2]&0xFF, p[3]&0xFF));
        Log.d(TAG_SCENE, String.format("SceneID=%d Type=%d Press=%d Mode=%d no.nordicsemi.android.swaromesh.Device=%d State=%d TID=%d",
                p[0]&0xFF, (p[1]>>2)&0x3F, p[1]&0x03,
                (p[2]>>5)&0x07, (p[2]>>2)&0x07, p[2]&0x03, p[3]&0xFF));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SimpleTextWatcher — boilerplate reducer
    // ─────────────────────────────────────────────────────────────────────────
    private static class SimpleTextWatcher implements TextWatcher {
        private final Runnable r;
        SimpleTextWatcher(Runnable r) { this.r = r; }
        @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
        @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
        @Override public void afterTextChanged(Editable s) { r.run(); }
    }
}