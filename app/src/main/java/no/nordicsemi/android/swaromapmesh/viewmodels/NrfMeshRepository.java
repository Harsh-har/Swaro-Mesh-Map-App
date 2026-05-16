package no.nordicsemi.android.swaromapmesh.viewmodels;

import static no.nordicsemi.android.swaromapmesh.opcodes.ApplicationMessageOpCodes.GENERIC_LEVEL_STATUS;
import static no.nordicsemi.android.swaromapmesh.opcodes.ApplicationMessageOpCodes.GENERIC_ON_OFF_STATUS;
import static no.nordicsemi.android.swaromapmesh.opcodes.ApplicationMessageOpCodes.SCENE_REGISTER_STATUS;
import static no.nordicsemi.android.swaromapmesh.opcodes.ApplicationMessageOpCodes.SCENE_STATUS;
import static no.nordicsemi.android.swaromapmesh.opcodes.ConfigMessageOpCodes.CONFIG_APPKEY_STATUS;
import static no.nordicsemi.android.swaromapmesh.opcodes.ConfigMessageOpCodes.CONFIG_COMPOSITION_DATA_STATUS;
import static no.nordicsemi.android.swaromapmesh.opcodes.ConfigMessageOpCodes.CONFIG_DEFAULT_TTL_STATUS;
import static no.nordicsemi.android.swaromapmesh.opcodes.ConfigMessageOpCodes.CONFIG_GATT_PROXY_STATUS;
import static no.nordicsemi.android.swaromapmesh.opcodes.ConfigMessageOpCodes.CONFIG_HEARTBEAT_PUBLICATION_STATUS;
import static no.nordicsemi.android.swaromapmesh.opcodes.ConfigMessageOpCodes.CONFIG_HEARTBEAT_SUBSCRIPTION_STATUS;
import static no.nordicsemi.android.swaromapmesh.opcodes.ConfigMessageOpCodes.CONFIG_MODEL_APP_STATUS;
import static no.nordicsemi.android.swaromapmesh.opcodes.ConfigMessageOpCodes.CONFIG_MODEL_PUBLICATION_STATUS;
import static no.nordicsemi.android.swaromapmesh.opcodes.ConfigMessageOpCodes.CONFIG_MODEL_SUBSCRIPTION_STATUS;
import static no.nordicsemi.android.swaromapmesh.opcodes.ConfigMessageOpCodes.CONFIG_NETWORK_TRANSMIT_STATUS;
import static no.nordicsemi.android.swaromapmesh.opcodes.ConfigMessageOpCodes.CONFIG_NODE_RESET_STATUS;
import static no.nordicsemi.android.swaromapmesh.opcodes.ConfigMessageOpCodes.CONFIG_RELAY_STATUS;
import static no.nordicsemi.android.swaromapmesh.ble.BleMeshManager.MESH_PROXY_UUID;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.inject.Inject;
import javax.inject.Singleton;
import no.nordicsemi.android.log.LogSession;
import no.nordicsemi.android.log.Logger;
import no.nordicsemi.android.swaromapmesh.ApplicationKey;
import no.nordicsemi.android.swaromapmesh.Group;
import no.nordicsemi.android.swaromapmesh.MeshManagerApi;
import no.nordicsemi.android.swaromapmesh.MeshManagerCallbacks;
import no.nordicsemi.android.swaromapmesh.MeshNetwork;
import no.nordicsemi.android.swaromapmesh.MeshProvisioningStatusCallbacks;
import no.nordicsemi.android.swaromapmesh.MeshStatusCallbacks;
import no.nordicsemi.android.swaromapmesh.NetworkKey;
import no.nordicsemi.android.swaromapmesh.Provisioner;
import no.nordicsemi.android.swaromapmesh.UnprovisionedBeacon;
import no.nordicsemi.android.swaromapmesh.models.SigModelParser;
import no.nordicsemi.android.swaromapmesh.opcodes.ProxyConfigMessageOpCodes;
import no.nordicsemi.android.swaromapmesh.provisionerstates.ProvisioningState;
import no.nordicsemi.android.swaromapmesh.provisionerstates.UnprovisionedMeshNode;
import no.nordicsemi.android.swaromapmesh.transport.ConfigAppKeyAdd;
import no.nordicsemi.android.swaromapmesh.transport.ConfigAppKeyStatus;
import no.nordicsemi.android.swaromapmesh.transport.ConfigCompositionDataGet;
import no.nordicsemi.android.swaromapmesh.transport.ConfigDefaultTtlGet;
import no.nordicsemi.android.swaromapmesh.transport.ConfigDefaultTtlStatus;
import no.nordicsemi.android.swaromapmesh.transport.ConfigModelAppBind;
import no.nordicsemi.android.swaromapmesh.transport.ConfigModelAppStatus;
import no.nordicsemi.android.swaromapmesh.transport.ConfigModelPublicationStatus;
import no.nordicsemi.android.swaromapmesh.transport.ConfigModelSubscriptionStatus;
import no.nordicsemi.android.swaromapmesh.transport.ControlMessage;
import no.nordicsemi.android.swaromapmesh.transport.Element;
import no.nordicsemi.android.swaromapmesh.transport.GenericLevelStatus;
import no.nordicsemi.android.swaromapmesh.transport.GenericOnOffStatus;
import no.nordicsemi.android.swaromapmesh.transport.MeshMessage;
import no.nordicsemi.android.swaromapmesh.transport.MeshModel;
import no.nordicsemi.android.swaromapmesh.transport.ProvisionedMeshNode;
import no.nordicsemi.android.swaromapmesh.transport.ProxyConfigFilterStatus;
import no.nordicsemi.android.swaromapmesh.transport.SceneRegisterStatus;
import no.nordicsemi.android.swaromapmesh.transport.SceneStatus;
import no.nordicsemi.android.swaromapmesh.transport.VendorModelMessageStatus;
import no.nordicsemi.android.swaromapmesh.adapter.ExtendedBluetoothDevice;
import no.nordicsemi.android.swaromapmesh.ble.BleMeshManager;
import no.nordicsemi.android.swaromapmesh.ble.BleMeshManagerCallbacks;
import no.nordicsemi.android.swaromapmesh.utils.ProvisionerStates;
import no.nordicsemi.android.swaromapmesh.utils.Utils;
import no.nordicsemi.android.support.v18.scanner.BluetoothLeScannerCompat;
import no.nordicsemi.android.support.v18.scanner.ScanCallback;
import no.nordicsemi.android.support.v18.scanner.ScanFilter;
import no.nordicsemi.android.support.v18.scanner.ScanRecord;
import no.nordicsemi.android.support.v18.scanner.ScanResult;
import no.nordicsemi.android.support.v18.scanner.ScanSettings;

@Singleton
public class NrfMeshRepository implements MeshProvisioningStatusCallbacks, MeshStatusCallbacks,
        MeshManagerCallbacks, BleMeshManagerCallbacks {

    private static final String TAG      = NrfMeshRepository.class.getSimpleName();
    private static final String TAG_BIND = "AUTO_BIND";

    private static final int ATTENTION_TIMER = 5;

    private static final long BIND_TIMEOUT_MS = 4_000;

    // ── SIG Model IDs ─────────────────────────────────────────────────────────
    // Application-layer models — these NEED an AppKey bound.
    private static final int MODEL_GENERIC_ONOFF_SERVER = 0x1000;
    private static final int MODEL_GENERIC_ONOFF_CLIENT = 0x1001;

    // Config/foundation models — these must NOT be app-key bound.
    private static final int MODEL_CONFIGURATION_SERVER = 0x0000;
    private static final int MODEL_CONFIGURATION_CLIENT = 0x0001;
    private static final int MODEL_HEALTH_SERVER        = 0x0002;
    private static final int MODEL_HEALTH_CLIENT        = 0x0003;

    // ── Export path (FIX #5) ──────────────────────────────────────────────────
    // Initialised lazily in getExportPath(context) to avoid deprecated static call.
    private String mExportPath;

    // ── Connection state ──────────────────────────────────────────────────────
    private final MutableLiveData<Boolean> mIsConnectedToProxy = new MutableLiveData<>();
    private       MutableLiveData<Boolean> mIsConnected;
    private final MutableLiveData<Void>   mOnDeviceReady   = new MutableLiveData<>();
    private final MutableLiveData<String> mConnectionState = new MutableLiveData<>();

    private final SingleLiveEvent<Boolean>               mIsReconnecting              = new SingleLiveEvent<>();
    private final MutableLiveData<UnprovisionedMeshNode> mUnprovisionedMeshNodeLiveData = new MutableLiveData<>();
    private final MutableLiveData<ProvisionedMeshNode>   mProvisionedMeshNodeLiveData   = new MutableLiveData<>();
    private final SingleLiveEvent<Integer>               mConnectedProxyAddress         = new SingleLiveEvent<>();

    private boolean mIsProvisioningComplete = false;

    // ── Selected items ────────────────────────────────────────────────────────
    private final MutableLiveData<ProvisionedMeshNode> mExtendedMeshNode      = new MutableLiveData<>();
    private final MutableLiveData<Element>             mSelectedElement       = new MutableLiveData<>();
    private final MutableLiveData<MeshModel>           mSelectedModel         = new MutableLiveData<>();
    private final MutableLiveData<Provisioner>         mSelectedProvisioner   = new MutableLiveData<>();
    private final MutableLiveData<Group>               mSelectedGroupLiveData = new MutableLiveData<>();

    // ── Network / messaging ───────────────────────────────────────────────────
    private final MeshNetworkLiveData                        mMeshNetworkLiveData = new MeshNetworkLiveData();
    private final SingleLiveEvent<String>                    mNetworkImportState  = new SingleLiveEvent<>();
    private final SingleLiveEvent<MeshMessage>               mMeshMessageLiveData = new SingleLiveEvent<>();
    private final MutableLiveData<List<ProvisionedMeshNode>> mProvisionedNodes    = new MutableLiveData<>();
    private final MutableLiveData<TransactionStatus>         mTransactionStatus   = new SingleLiveEvent<>();

    // ── Core objects ──────────────────────────────────────────────────────────
    private final MeshManagerApi mMeshManagerApi;
    private final BleMeshManager mBleMeshManager;
    private final Handler        mHandler;

    private UnprovisionedMeshNode      mUnprovisionedMeshNode;
    private ProvisionedMeshNode        mProvisionedMeshNode;
    private boolean                    mIsReconnectingFlag;
    private boolean                    mIsScanning;
    private boolean                    mSetupProvisionedNode;
    private ProvisioningStatusLiveData mProvisioningStateLiveData;
    private MeshNetwork                mMeshNetwork;

    // ── Provisioning state flags ──────────────────────────────────────────────
    private boolean mIsCompositionDataReceived;
    private boolean mIsDefaultTtlReceived;
    private boolean mIsAppKeyAddCompleted;
    private boolean mIsNetworkRetransmitSetCompleted;

    // ── Auto AppKey Bind state ────────────────────────────────────────────────
    private final List<int[]>   mPendingBindOperations = new ArrayList<>();
    private int                 mAutoBindIndex         = 0;
    private ProvisionedMeshNode mAutoBindNode          = null;
    private boolean             mIsBindingInProgress   = false;

    // ── Import callback ───────────────────────────────────────────────────────
    private volatile Runnable mOnNetworkImportedCallback = null;

    // ── Runnables ─────────────────────────────────────────────────────────────
    private final Runnable mReconnectRunnable = this::startScan;

    private final Runnable mScannerTimeout = () -> {
        stopScan();
        mIsReconnecting.postValue(false);
        Log.w(TAG, "Scanner timed out — no provisioned node found.");
    };

    // ── Auto setup progress ───────────────────────────────────────────────────
    private final MutableLiveData<Boolean> mIsAutoSetupInProgress = new MutableLiveData<>();

    private final Runnable mBindTimeoutRunnable = () -> {
        if (mIsBindingInProgress) {
            Log.w(TAG_BIND, "⚠️ Bind timeout → skipping model [" + mAutoBindIndex + "]");
            mIsBindingInProgress = false;
            mAutoBindIndex++;
            sendNextAutoBind();
        }
    };

    // =========================================================================
    // Constructor
    // =========================================================================

    @Inject
    public NrfMeshRepository(final MeshManagerApi meshManagerApi,
                             final BleMeshManager bleMeshManager) {
        mMeshManagerApi = meshManagerApi;
        mMeshManagerApi.setMeshManagerCallbacks(this);
        mMeshManagerApi.setProvisioningStatusCallbacks(this);
        mMeshManagerApi.setMeshStatusCallbacks(this);
        mMeshManagerApi.loadMeshNetwork();
        mBleMeshManager = bleMeshManager;
        mBleMeshManager.setGattCallbacks(this);
        mHandler = new Handler(Looper.getMainLooper());
    }

    // =========================================================================
    // Public API
    // =========================================================================

    public String getExportPath(@NonNull final Context context) {
        if (mExportPath == null) {
            final File dir = new File(context.getExternalFilesDir(null),
                    "Nordic Semiconductor" + File.separator + "nRF Mesh");
            if (!dir.exists()) {
                dir.mkdirs();
            }
            mExportPath = dir.getAbsolutePath() + File.separator;
        }
        return mExportPath;
    }

    public void setOnNetworkImportedCallback(@Nullable final Runnable callback) {
        mOnNetworkImportedCallback = callback;
    }

    // =========================================================================
    // Getters / Setters
    // =========================================================================

    LiveData<Void>    isDeviceReady()      { return mOnDeviceReady; }
    LiveData<String>  getConnectionState() { return mConnectionState; }
    LiveData<Boolean> isConnected()        { return mIsConnected; }
    LiveData<Boolean> isConnectedToProxy() { return mIsConnectedToProxy; }
    LiveData<Boolean> isReconnecting()     { return mIsReconnecting; }

    boolean isProvisioningComplete()          { return mIsProvisioningComplete; }
    boolean isCompositionDataStatusReceived() { return mIsCompositionDataReceived; }
    boolean isDefaultTtlReceived()            { return mIsDefaultTtlReceived; }
    boolean isAppKeyAddCompleted()            { return mIsAppKeyAddCompleted; }
    boolean isNetworkRetransmitSetCompleted() { return mIsNetworkRetransmitSetCompleted; }

    final MeshNetworkLiveData getMeshNetworkLiveData()  { return mMeshNetworkLiveData; }
    LiveData<List<ProvisionedMeshNode>> getNodes()      { return mProvisionedNodes; }
    LiveData<String>  getNetworkLoadState()             { return mNetworkImportState; }
    ProvisioningStatusLiveData getProvisioningState()   { return mProvisioningStateLiveData; }
    LiveData<TransactionStatus> getTransactionStatus()  { return mTransactionStatus; }
    MeshManagerApi getMeshManagerApi()                  { return mMeshManagerApi; }
    BleMeshManager getBleMeshManager()                  { return mBleMeshManager; }
    LiveData<MeshMessage> getMeshMessageLiveData()      { return mMeshMessageLiveData; }
    LiveData<Group>   getSelectedGroup()                { return mSelectedGroupLiveData; }
    LiveData<UnprovisionedMeshNode> getUnprovisionedMeshNode() { return mUnprovisionedMeshNodeLiveData; }
    LiveData<Integer> getConnectedProxyAddress()        { return mConnectedProxyAddress; }
    LiveData<ProvisionedMeshNode> getSelectedMeshNode() { return mExtendedMeshNode; }
    LiveData<Element>    getSelectedElement()           { return mSelectedElement; }
    LiveData<Provisioner> getSelectedProvisioner()      { return mSelectedProvisioner; }
    LiveData<MeshModel>  getSelectedModel()             { return mSelectedModel; }
    LiveData<Boolean> isAutoSetupInProgress()           { return mIsAutoSetupInProgress; }

    void clearTransactionStatus() {
        if (mTransactionStatus.getValue() != null) mTransactionStatus.postValue(null);
    }

    void setSelectedMeshNode(final ProvisionedMeshNode node) {
        mProvisionedMeshNode = node;
        mExtendedMeshNode.postValue(node);
    }

    void setSelectedElement(final Element element)  { mSelectedElement.postValue(element); }
    void setSelectedModel(final MeshModel model)    { mSelectedModel.postValue(model); }
    void setSelectedProvisioner(@NonNull final Provisioner p) { mSelectedProvisioner.postValue(p); }

    void setSelectedGroup(final int address) {
        if (mMeshNetwork == null) return;
        final Group group = mMeshNetwork.getGroup(address);
        if (group != null) mSelectedGroupLiveData.postValue(group);
    }

    // =========================================================================
    // ScannerActivity reconnect helpers
    // =========================================================================

    public ProvisionedMeshNode getLastProvisionedNode() { return mProvisionedMeshNode; }

    public void markSetupRequired(final int nodeUnicastAddress) {
        if (mMeshNetwork == null) {
            Log.e(TAG, "markSetupRequired: mMeshNetwork is null — abort");
            return;
        }
        final ProvisionedMeshNode node = mMeshNetwork.getNode(nodeUnicastAddress);
        if (node == null) {
            Log.e(TAG, "markSetupRequired: node not found for 0x"
                    + Integer.toHexString(nodeUnicastAddress));
            // Fallback: use the already-stored reference if addresses match.
            if (mProvisionedMeshNode != null
                    && mProvisionedMeshNode.getUnicastAddress() == nodeUnicastAddress) {
                Log.d(TAG, "markSetupRequired: using mProvisionedMeshNode fallback");
            } else {
                Log.e(TAG, "markSetupRequired: fallback also failed — abort");
                return;
            }
        } else {
            mProvisionedMeshNode = node;
            mProvisionedMeshNodeLiveData.postValue(node);
        }
        mSetupProvisionedNode            = true;
        mIsCompositionDataReceived       = false;
        mIsDefaultTtlReceived            = false;
        mIsAppKeyAddCompleted            = false;
        mIsNetworkRetransmitSetCompleted = false;
        Log.d(TAG, "markSetupRequired ✅ node=0x" + Integer.toHexString(nodeUnicastAddress));
    }

    // =========================================================================
    // Connection management
    // =========================================================================

    void resetMeshNetwork() {
        disconnect();
        mMeshManagerApi.resetMeshNetwork();
    }

    void connect(@NonNull final Context context,
                 @NonNull final ExtendedBluetoothDevice device,
                 final boolean connectToNetwork) {
        mMeshNetworkLiveData.setNodeName(device.getName());
        mIsProvisioningComplete          = false;
        mIsCompositionDataReceived       = false;
        mIsDefaultTtlReceived            = false;
        mIsAppKeyAddCompleted            = false;
        mIsNetworkRetransmitSetCompleted = false;
        final LogSession logSession = Logger.newSession(context, null,
                device.getAddress(), device.getName());
        mBleMeshManager.setLogger(logSession);
        initIsConnectedLiveData(connectToNetwork);
        mConnectionState.postValue("Connecting…");
        mBleMeshManager.connect(device.getDevice()).retry(3, 200).enqueue();
    }

    private void connectToProxy(@NonNull final ExtendedBluetoothDevice device) {
        initIsConnectedLiveData(true);
        mConnectionState.postValue("Connecting…");
        mBleMeshManager.connect(device.getDevice()).retry(3, 200).enqueue();
    }

    private void initIsConnectedLiveData(final boolean connectToNetwork) {
        mIsConnected = connectToNetwork ? new SingleLiveEvent<>() : new MutableLiveData<>();
    }

    void disconnect() {
        clearProvisioningLiveData();
        mIsProvisioningComplete = false;
        mBleMeshManager.disconnect().enqueue();
    }

    void clearProvisioningLiveData() {
        stopScan();
        mHandler.removeCallbacks(mReconnectRunnable);
        mSetupProvisionedNode = false;
        mIsReconnectingFlag   = false;
        mUnprovisionedMeshNodeLiveData.setValue(null);
        mProvisionedMeshNodeLiveData.setValue(null);
    }

    public void identifyNode(@NonNull final ExtendedBluetoothDevice device) {
        final UnprovisionedBeacon beacon = (UnprovisionedBeacon) device.getBeacon();
        if (beacon != null) {
            mMeshManagerApi.identifyNode(beacon.getUuid(), ATTENTION_TIMER);

        } else {
            final byte[] serviceData = Utils.getServiceData(
                    device.getScanResult(), BleMeshManager.MESH_PROVISIONING_UUID);
            if (serviceData != null) {
                final UUID uuid = mMeshManagerApi.getDeviceUuid(serviceData);
                Log.d(TAG, "✅✅✅Idenitify✅ ");
                mMeshManagerApi.identifyNode(uuid, ATTENTION_TIMER);
            }
        }
    }

    private void clearExtendedMeshNode() { mExtendedMeshNode.postValue(null); }

    // =========================================================================
    // BleMeshManagerCallbacks
    // =========================================================================

    @Override
    public void onDataReceived(@NonNull final BluetoothDevice bluetoothDevice,
                               final int mtu, @NonNull final byte[] pdu) {
        mMeshManagerApi.handleNotifications(mtu, pdu);
    }

    @Override
    public void onDataSent(@NonNull final BluetoothDevice device,
                           final int mtu, @NonNull final byte[] pdu) {
        mMeshManagerApi.handleWriteCallbacks(mtu, pdu);
    }

    @Override
    public void onDeviceConnecting(@NonNull final BluetoothDevice device) {
        mConnectionState.postValue("Connecting…");
    }

    @Override
    public void onDeviceConnected(@NonNull final BluetoothDevice device) {
        mIsConnected.postValue(true);
        mConnectionState.postValue("Discovering services…");
        mIsConnectedToProxy.postValue(true);
    }

    @Override
    public void onDeviceDisconnecting(@NonNull final BluetoothDevice device) {
        mConnectionState.postValue(mIsReconnectingFlag ? "Reconnecting…" : "Disconnecting…");
    }

    @Override
    public void onDeviceDisconnected(@NonNull final BluetoothDevice device) {
        mConnectionState.postValue("");

        if (mIsReconnectingFlag) {
            mIsReconnectingFlag = false;
            mIsReconnecting.postValue(false);
            mIsConnected.postValue(false);
            mIsConnectedToProxy.postValue(false);
        } else {
            // ── Normal disconnect ─────────────────────────────────────────────
            mIsConnected.postValue(false);
            mIsConnectedToProxy.postValue(false);
            if (mConnectedProxyAddress.getValue() != null) {
                final MeshNetwork network = mMeshManagerApi.getMeshNetwork();
                if (network != null) network.setProxyFilter(null);
            }
            // Safe to clear setup flag only on a true user-initiated disconnect.
            mSetupProvisionedNode = false;
        }

        mConnectedProxyAddress.postValue(null);
        mIsBindingInProgress = false;
    }

    @Override public void onLinkLossOccurred(@NonNull final BluetoothDevice device) {
        mIsConnected.postValue(false);
    }

    @Override public void onServicesDiscovered(@NonNull final BluetoothDevice device,
                                               final boolean optionalServicesFound) {
        mConnectionState.postValue("Initializing…");
    }

    @Override
    public void onDeviceReady(@NonNull final BluetoothDevice device) {
        mOnDeviceReady.postValue(null);
        if (mBleMeshManager.isProvisioningComplete()) {
            if (mSetupProvisionedNode) {
                if (mMeshNetwork.getSelectedProvisioner().getProvisionerAddress() != null) {
                    mHandler.postDelayed(() -> {
                        final ProvisionedMeshNode node = mProvisionedMeshNodeLiveData.getValue();
                        if (node != null) {
                            mMeshManagerApi.createMeshPdu(node.getUnicastAddress(),
                                    new ConfigCompositionDataGet());
                        } else {
                            Log.e(TAG, "onDeviceReady: mProvisionedMeshNodeLiveData is null");
                        }
                    }, 2000);
                } else {
                    mSetupProvisionedNode = false;
                    mProvisioningStateLiveData.onMeshNodeStateUpdated(
                            ProvisionerStates.PROVISIONER_UNASSIGNED);
                    clearExtendedMeshNode();
                }
            }
            mIsConnectedToProxy.postValue(true);
        }
    }

    @Override public void onBondingRequired(@NonNull final BluetoothDevice device) {}
    @Override public void onBonded(@NonNull final BluetoothDevice device) {}
    @Override public void onBondingFailed(@NonNull final BluetoothDevice device) {}

    @Override
    public void onError(@NonNull final BluetoothDevice device,
                        @NonNull final String message, final int errorCode) {
        Log.e(TAG, message + " (code: " + errorCode + "), device: " + device.getAddress());
        mConnectionState.postValue(message);
    }

    @Override public void onDeviceNotSupported(@NonNull final BluetoothDevice device) {}

    // =========================================================================
    // MeshManagerCallbacks
    // =========================================================================

    @Override public void onNetworkLoaded(final MeshNetwork meshNetwork) {
        loadNetwork(meshNetwork);
    }

    @Override
    public void onNetworkUpdated(final MeshNetwork meshNetwork) {
        loadNetwork(meshNetwork);
        updateSelectedGroup();
    }

    @Override public void onNetworkLoadFailed(final String error) {
        mNetworkImportState.postValue(error);
    }

    @Override public void onNetworkImportFailed(final String error) {
        mNetworkImportState.postValue(error);
    }
    @Override
    public void onNetworkImported(final MeshNetwork meshNetwork) {
        loadNetwork(meshNetwork);
        final Runnable cb = mOnNetworkImportedCallback;
        if (cb != null) {
            mHandler.post(cb);
            Log.d(TAG, "✅ onNetworkImported: callback fired");
        }
        mNetworkImportState.postValue(meshNetwork.getMeshName()
                + " has been successfully imported.\n"
                + "To start sending messages to this network, please change the provisioner "
                + "address. Using the same provisioner address will cause messages to be "
                + "discarded due to incorrect sequence numbers. If the network has no nodes, "
                + "you do not need to change the address.");
    }

    @Override public void sendProvisioningPdu(final UnprovisionedMeshNode meshNode,
                                              final byte[] pdu) {
        mBleMeshManager.sendPdu(pdu);
    }

    @Override public void onMeshPduCreated(final byte[] pdu) {
        mBleMeshManager.sendPdu(pdu);
    }

    @Override public int getMtu() { return mBleMeshManager.getMaximumPacketSize(); }

    // =========================================================================
    // MeshProvisioningStatusCallbacks
    // =========================================================================

    @Override
    public void onProvisioningStateChanged(final UnprovisionedMeshNode meshNode,
                                           final ProvisioningState.States state,
                                           final byte[] data) {
        mUnprovisionedMeshNode = meshNode;
        mUnprovisionedMeshNodeLiveData.postValue(meshNode);
        if (state == ProvisioningState.States.PROVISIONING_INVITE) {
            mProvisioningStateLiveData = new ProvisioningStatusLiveData();
        } else if (state == ProvisioningState.States.PROVISIONING_FAILED) {
            mIsProvisioningComplete = false;
        }
        mProvisioningStateLiveData.onMeshNodeStateUpdated(
                ProvisionerStates.fromStatusCode(state.getState()));
    }

    @Override
    public void onProvisioningFailed(final UnprovisionedMeshNode meshNode,
                                     final ProvisioningState.States state,
                                     final byte[] data) {
        mUnprovisionedMeshNode = meshNode;
        mUnprovisionedMeshNodeLiveData.postValue(meshNode);
        if (state == ProvisioningState.States.PROVISIONING_FAILED) {
            mIsProvisioningComplete = false;
        }
        mProvisioningStateLiveData.onMeshNodeStateUpdated(
                ProvisionerStates.fromStatusCode(state.getState()));
    }

    @Override
    public void onProvisioningCompleted(final ProvisionedMeshNode meshNode,
                                        final ProvisioningState.States state,
                                        final byte[] data) {
        mProvisionedMeshNode = meshNode;
        mUnprovisionedMeshNodeLiveData.postValue(null);
        mProvisionedMeshNodeLiveData.postValue(meshNode);
        if (state == ProvisioningState.States.PROVISIONING_COMPLETE) {
            onProvisioningCompleted(meshNode);
        }
        mProvisioningStateLiveData.onMeshNodeStateUpdated(
                ProvisionerStates.fromStatusCode(state.getState()));
    }

    private void onProvisioningCompleted(@NonNull final ProvisionedMeshNode node) {
        mIsProvisioningComplete = true;
        mProvisionedMeshNode    = node;
        mIsAutoSetupInProgress.postValue(true);
        mIsReconnecting.postValue(true);
        mBleMeshManager.disconnect().enqueue();
        loadNodes();
        mHandler.post(() -> mConnectionState.postValue("Scanning for provisioned node…"));
        mHandler.postDelayed(mReconnectRunnable, 1000);
    }

    private void loadNodes() {
        if (mMeshNetwork == null) return;
        final List<ProvisionedMeshNode> nodes = new ArrayList<>();
        final String provisionerUuid = mMeshNetwork.getSelectedProvisioner().getProvisionerUuid();
        for (final ProvisionedMeshNode node : mMeshNetwork.getNodes()) {
            if (!node.getUuid().equalsIgnoreCase(provisionerUuid)) {
                nodes.add(node);
            }
        }
        mProvisionedNodes.postValue(nodes);
    }

    // =========================================================================
    // MeshStatusCallbacks
    // =========================================================================

    @Override
    public void onTransactionFailed(final int dst, final boolean hasIncompleteTimerExpired) {
        mProvisionedMeshNode = mMeshNetwork.getNode(dst);
        mTransactionStatus.postValue(new TransactionStatus(dst, hasIncompleteTimerExpired));
    }

    @Override
    public void onUnknownPduReceived(final int src, final byte[] accessPayload) {
        final ProvisionedMeshNode node = mMeshNetwork.getNode(src);
        if (node != null) updateNode(node);
    }

    @Override
    public void onBlockAcknowledgementProcessed(final int dst,
                                                @NonNull final ControlMessage message) {
        final ProvisionedMeshNode node = mMeshNetwork.getNode(dst);
        if (node != null) {
            mProvisionedMeshNode = node;
            if (mSetupProvisionedNode) {
                mProvisionedMeshNodeLiveData.postValue(mProvisionedMeshNode);
                mProvisioningStateLiveData.onMeshNodeStateUpdated(
                        ProvisionerStates.SENDING_BLOCK_ACKNOWLEDGEMENT);
            }
        }
    }

    @Override
    public void onBlockAcknowledgementReceived(final int src,
                                               @NonNull final ControlMessage message) {
        final ProvisionedMeshNode node = mMeshNetwork.getNode(src);
        if (node != null) {
            mProvisionedMeshNode = node;
            if (mSetupProvisionedNode) {
                mProvisionedMeshNodeLiveData.postValue(node);
                mProvisioningStateLiveData.onMeshNodeStateUpdated(
                        ProvisionerStates.BLOCK_ACKNOWLEDGEMENT_RECEIVED);
            }
        }
    }

    @Override public void onHeartbeatMessageReceived(final int src,
                                                     @NonNull final ControlMessage message) {}

    @Override
    public void onMeshMessageProcessed(final int dst, @NonNull final MeshMessage meshMessage) {
        final ProvisionedMeshNode node = mMeshNetwork.getNode(dst);
        if (node != null) {
            mProvisionedMeshNode = node;
            if (mSetupProvisionedNode) {
                if (meshMessage instanceof ConfigCompositionDataGet) {
                    mProvisionedMeshNodeLiveData.postValue(node);
                    mProvisioningStateLiveData.onMeshNodeStateUpdated(
                            ProvisionerStates.COMPOSITION_DATA_GET_SENT);
                } else if (meshMessage instanceof ConfigDefaultTtlGet) {
                    mProvisionedMeshNodeLiveData.postValue(node);
                    mProvisioningStateLiveData.onMeshNodeStateUpdated(
                            ProvisionerStates.SENDING_DEFAULT_TTL_GET);
                } else if (meshMessage instanceof ConfigAppKeyAdd) {
                    mProvisionedMeshNodeLiveData.postValue(node);
                    mProvisioningStateLiveData.onMeshNodeStateUpdated(
                            ProvisionerStates.SENDING_APP_KEY_ADD);
                }
            }
        }
    }

    // =========================================================================
    // onMeshMessageReceived — main dispatcher
    // =========================================================================

    @Override
    public void onMeshMessageReceived(final int src, @NonNull final MeshMessage meshMessage) {
        final ProvisionedMeshNode node = mMeshNetwork.getNode(src);

        if (node != null) {
            final int opCode = meshMessage.getOpCode();

            if (opCode == ProxyConfigMessageOpCodes.FILTER_STATUS) {
                mProvisionedMeshNode = node;
                setSelectedMeshNode(node);
                final ProxyConfigFilterStatus status = (ProxyConfigFilterStatus) meshMessage;
                mConnectedProxyAddress.postValue(status.getSrc());
                mMeshMessageLiveData.postValue(status);

            } else if (opCode == CONFIG_COMPOSITION_DATA_STATUS) {
                if (mSetupProvisionedNode) {
                    mIsCompositionDataReceived = true;
                    mProvisionedMeshNodeLiveData.postValue(node);
                    mConnectedProxyAddress.postValue(node.getUnicastAddress());
                    mProvisioningStateLiveData.onMeshNodeStateUpdated(
                            ProvisionerStates.COMPOSITION_DATA_STATUS_RECEIVED);
                    mHandler.postDelayed(() ->
                            mMeshManagerApi.createMeshPdu(node.getUnicastAddress(),
                                    new ConfigDefaultTtlGet()), 500);
                } else {
                    updateNode(node);
                }

            } else if (opCode == CONFIG_DEFAULT_TTL_STATUS) {
                final ConfigDefaultTtlStatus status = (ConfigDefaultTtlStatus) meshMessage;
                if (mSetupProvisionedNode) {
                    mIsDefaultTtlReceived = true;
                    if (mMeshNetworkLiveData.getAppKeys().isEmpty()) {
                        mSetupProvisionedNode = false;
                    }
                    mProvisionedMeshNodeLiveData.postValue(node);
                    mProvisioningStateLiveData.onMeshNodeStateUpdated(
                            ProvisionerStates.DEFAULT_TTL_STATUS_RECEIVED);
                    if (!mMeshNetworkLiveData.getAppKeys().isEmpty()) {
                        final ApplicationKey appKey = mMeshNetworkLiveData.getSelectedAppKey();
                        if (appKey != null) {
                            mHandler.postDelayed(() -> {
                                final NetworkKey netKey = mMeshNetwork.getNetKeys()
                                        .get(appKey.getBoundNetKeyIndex());
                                mMeshManagerApi.createMeshPdu(node.getUnicastAddress(),
                                        new ConfigAppKeyAdd(netKey, appKey));
                            }, 1500);
                        } else {
                            mSetupProvisionedNode = false;
                            mProvisioningStateLiveData.onMeshNodeStateUpdated(
                                    ProvisionerStates.APP_KEY_STATUS_RECEIVED);
                        }
                    }
                } else {
                    updateNode(node);
                    mMeshMessageLiveData.postValue(status);
                }

            } else if (opCode == CONFIG_NETWORK_TRANSMIT_STATUS) {
                updateNode(node);
                mMeshMessageLiveData.postValue(meshMessage);

            } else if (opCode == CONFIG_APPKEY_STATUS) {
                final ConfigAppKeyStatus status = (ConfigAppKeyStatus) meshMessage;
                if (status.isSuccessful()) {
                    mIsAppKeyAddCompleted = true;
                    mSetupProvisionedNode = false;
                    mProvisionedMeshNodeLiveData.postValue(node);
                    if (mProvisioningStateLiveData != null) {
                        mProvisioningStateLiveData.onMeshNodeStateUpdated(
                                ProvisionerStates.APP_KEY_STATUS_RECEIVED);
                    }
                    Log.d(TAG_BIND, "✅ AppKey add SUCCESS → startAutoAppKeyBind node=0x"
                            + String.format("%04X", node.getUnicastAddress()));
                    startAutoAppKeyBind(node);
                } else {
                    mSetupProvisionedNode = false;
                    if (mProvisioningStateLiveData != null) {
                        mProvisioningStateLiveData.onMeshNodeStateUpdated(
                                ProvisionerStates.APP_KEY_STATUS_RECEIVED);
                    }
                    Log.w(TAG_BIND, "⚠️ CONFIG_APPKEY_STATUS FAILED statusCode="
                            + status.getStatusCode());
                    updateNode(node);
                    mMeshMessageLiveData.postValue(status);
                }

            } else if (opCode == CONFIG_MODEL_APP_STATUS) {
                final ConfigModelAppStatus status = (ConfigModelAppStatus) meshMessage;
                updateNode(node);
                if (node.getElements().containsKey(status.getElementAddress())) {
                    final Element element = node.getElements().get(status.getElementAddress());
                    if (element != null) {
                        mSelectedElement.postValue(element);
                        mSelectedModel.postValue(
                                element.getMeshModels().get(status.getModelIdentifier()));
                    }
                }
                if (mAutoBindNode != null
                        && mAutoBindNode.getUnicastAddress() == node.getUnicastAddress()) {
                    Log.d(TAG_BIND, "✅ Bind ack received for model 0x"
                            + String.format("%04X", status.getModelIdentifier()));
                    mHandler.removeCallbacks(mBindTimeoutRunnable);
                    mIsBindingInProgress = false;
                    mAutoBindIndex++;
                    mHandler.postDelayed(this::sendNextAutoBind, 200);
                }

            } else if (opCode == CONFIG_MODEL_PUBLICATION_STATUS) {
                updateNode(node);
                final ConfigModelPublicationStatus status =
                        (ConfigModelPublicationStatus) meshMessage;
                if (node.getElements().containsKey(status.getElementAddress())) {
                    final Element element = node.getElements().get(status.getElementAddress());
                    if (element != null) {
                        mSelectedElement.postValue(element);
                        mSelectedModel.postValue(
                                element.getMeshModels().get(status.getModelIdentifier()));
                    }
                }
                mMeshMessageLiveData.postValue(status);

            } else if (opCode == CONFIG_MODEL_SUBSCRIPTION_STATUS) {
                if (updateNode(node)) {
                    final ConfigModelSubscriptionStatus status =
                            (ConfigModelSubscriptionStatus) meshMessage;
                    if (node.getElements().containsKey(status.getElementAddress())) {
                        final Element element = node.getElements().get(status.getElementAddress());
                        if (element != null) {
                            mSelectedElement.postValue(element);
                            mSelectedModel.postValue(
                                    element.getMeshModels().get(status.getModelIdentifier()));
                        }
                    }
                }

            } else if (opCode == CONFIG_NODE_RESET_STATUS) {
                mBleMeshManager.setClearCacheRequired();
                mExtendedMeshNode.postValue(null);
                loadNodes();
                mMeshMessageLiveData.postValue(meshMessage);

            } else if (opCode == CONFIG_RELAY_STATUS) {
                if (updateNode(node)) mMeshMessageLiveData.postValue(meshMessage);

            } else if (opCode == CONFIG_HEARTBEAT_PUBLICATION_STATUS) {
                if (updateNode(node)) {
                    final Element element = node.getElements().get(meshMessage.getSrc());
                    if (element != null) {
                        mSelectedModel.postValue(element.getMeshModels()
                                .get((int) SigModelParser.CONFIGURATION_SERVER));
                    }
                    mMeshMessageLiveData.postValue(meshMessage);
                }

            } else if (opCode == CONFIG_HEARTBEAT_SUBSCRIPTION_STATUS) {
                if (updateNode(node)) {
                    final Element element = node.getElements().get(meshMessage.getSrc());
                    if (element != null) {
                        mSelectedModel.postValue(element.getMeshModels()
                                .get((int) SigModelParser.CONFIGURATION_SERVER));
                    }
                    mMeshMessageLiveData.postValue(meshMessage);
                }

            } else if (opCode == CONFIG_GATT_PROXY_STATUS) {
                if (updateNode(node)) mMeshMessageLiveData.postValue(meshMessage);

            } else if (opCode == GENERIC_ON_OFF_STATUS) {
                if (updateNode(node)) {
                    final GenericOnOffStatus status = (GenericOnOffStatus) meshMessage;
                    if (node.getElements().containsKey(status.getSrcAddress())) {
                        final Element element = node.getElements().get(status.getSrcAddress());
                        if (element != null) {
                            mSelectedElement.postValue(element);
                            mSelectedModel.postValue(element.getMeshModels()
                                    .get((int) SigModelParser.GENERIC_ON_OFF_SERVER));
                        }
                    }
                }

            } else if (opCode == GENERIC_LEVEL_STATUS) {
                if (updateNode(node)) {
                    final GenericLevelStatus status = (GenericLevelStatus) meshMessage;
                    if (node.getElements().containsKey(status.getSrcAddress())) {
                        final Element element = node.getElements().get(status.getSrcAddress());
                        if (element != null) {
                            mSelectedElement.postValue(element);
                            mSelectedModel.postValue(element.getMeshModels()
                                    .get((int) SigModelParser.GENERIC_LEVEL_SERVER));
                        }
                    }
                }

            } else if (opCode == SCENE_STATUS) {
                if (updateNode(node)) {
                    final SceneStatus status = (SceneStatus) meshMessage;
                    final Element element = node.getElements().get(status.getSrcAddress());
                    if (element != null) mSelectedElement.postValue(element);
                }

            } else if (opCode == SCENE_REGISTER_STATUS) {
                if (updateNode(node)) {
                    final SceneRegisterStatus status = (SceneRegisterStatus) meshMessage;
                    final Element element = node.getElements().get(status.getSrcAddress());
                    if (element != null) mSelectedElement.postValue(element);
                }

            } else if (meshMessage instanceof VendorModelMessageStatus) {
                if (updateNode(node)) {
                    final VendorModelMessageStatus status = (VendorModelMessageStatus) meshMessage;
                    if (node.getElements().containsKey(status.getSrcAddress())) {
                        final Element element = node.getElements().get(status.getSrcAddress());
                        if (element != null) {
                            mSelectedElement.postValue(element);
                            mSelectedModel.postValue(
                                    element.getMeshModels().get(status.getModelIdentifier()));
                        }
                    }
                }
            }
        }

        if (mMeshMessageLiveData.hasActiveObservers()) {
            mMeshMessageLiveData.postValue(meshMessage);
        }
        if (mMeshManagerApi.getMeshNetwork() != null) {
            mMeshNetworkLiveData.refresh(mMeshManagerApi.getMeshNetwork());
        }
    }

    @Override
    public void onMessageDecryptionFailed(final String meshLayer, final String errorMessage) {
        Log.e(TAG, "Decryption failed in " + meshLayer + ": " + errorMessage);
    }

    // =========================================================================
    // Internal helpers
    // =========================================================================

    private void loadNetwork(final MeshNetwork meshNetwork) {
        mMeshNetwork = meshNetwork;
        if (mMeshNetwork != null) {
            if (!mMeshNetwork.isProvisionerSelected()) {
                final Provisioner provisioner = meshNetwork.getProvisioners().get(0);
                provisioner.setLastSelected(true);
                mMeshNetwork.selectProvisioner(provisioner);
            }
            mMeshNetworkLiveData.loadNetworkInformation(meshNetwork);
            loadNodes();
            final ProvisionedMeshNode node = getSelectedMeshNode().getValue();
            if (node != null) {
                mExtendedMeshNode.postValue(mMeshNetwork.getNode(node.getUuid()));
            }
        }
    }

    private boolean updateNode(@NonNull final ProvisionedMeshNode node) {
        // Always refresh the full list so ALL observers see updated data.
        final List<ProvisionedMeshNode> currentList = mProvisionedNodes.getValue();
        if (currentList != null) {
            boolean listChanged = false;
            final List<ProvisionedMeshNode> updatedList = new ArrayList<>(currentList);
            for (int i = 0; i < updatedList.size(); i++) {
                if (updatedList.get(i).getUnicastAddress() == node.getUnicastAddress()) {
                    updatedList.set(i, node);
                    listChanged = true;
                    break;
                }
            }
            if (listChanged) {
                mProvisionedNodes.postValue(updatedList);
            }
        }

        if (mProvisionedMeshNode != null
                && mProvisionedMeshNode.getUnicastAddress() == node.getUnicastAddress()) {
            mProvisionedMeshNode = node;
            mExtendedMeshNode.postValue(node);
            return true;
        }
        return false;
    }

    private void updateSelectedGroup() {
        if (mMeshNetwork == null) return;
        final Group selectedGroup = mSelectedGroupLiveData.getValue();
        if (selectedGroup != null) {
            mSelectedGroupLiveData.postValue(
                    mMeshNetwork.getGroup(selectedGroup.getAddress()));
        }
    }

    // =========================================================================
    // BLE Scanner
    // =========================================================================

    private void startScan() {
        if (mIsScanning) return;
        mIsScanning = true;
        final ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setReportDelay(0)
                .setUseHardwareFilteringIfSupported(false)
                .build();
        final List<ScanFilter> filters = new ArrayList<>();
        filters.add(new ScanFilter.Builder()
                .setServiceUuid(new ParcelUuid(MESH_PROXY_UUID))
                .build());
        BluetoothLeScannerCompat.getScanner().startScan(filters, settings, mScanCallback);
        mHandler.postDelayed(mScannerTimeout, 20_000);
    }

    private void stopScan() {
        mHandler.removeCallbacks(mScannerTimeout);
        BluetoothLeScannerCompat.getScanner().stopScan(mScanCallback);
        mIsScanning = false;
    }

    private final ScanCallback mScanCallback = new ScanCallback() {
        @Override
        public void onScanResult(final int callbackType, @NonNull final ScanResult result) {
            final ScanRecord scanRecord = result.getScanRecord();
            if (scanRecord == null) return;
            final byte[] serviceData = Utils.getServiceData(result, MESH_PROXY_UUID);
            if (serviceData != null
                    && mMeshManagerApi.isAdvertisedWithNodeIdentity(serviceData)) {
                final ProvisionedMeshNode node = mProvisionedMeshNode;
                if (node != null && mMeshManagerApi.nodeIdentityMatches(node, serviceData)) {
                    stopScan();
                    mConnectionState.postValue("Provisioned node found");
                    onProvisionedDeviceFound(node, new ExtendedBluetoothDevice(result));
                }
            }
        }
    };

    private void onProvisionedDeviceFound(@NonNull final ProvisionedMeshNode node,
                                          @NonNull final ExtendedBluetoothDevice device) {
        mSetupProvisionedNode = true;
        mProvisionedMeshNode  = node;
        mIsReconnectingFlag   = true;
        mHandler.postDelayed(() -> connectToProxy(device), 2000);
    }

    // =========================================================================
    // AUTO APP KEY BIND
    // =========================================================================
    private static boolean requiresAppKeyBind(final int modelId) {
        return modelId != MODEL_CONFIGURATION_SERVER
                && modelId != MODEL_CONFIGURATION_CLIENT
                && modelId != MODEL_HEALTH_SERVER
                && modelId != MODEL_HEALTH_CLIENT;
    }

    private void startAutoAppKeyBind(@NonNull final ProvisionedMeshNode node) {
        mIsAutoSetupInProgress.postValue(true);
        final List<ApplicationKey> appKeys = mMeshNetworkLiveData.getAppKeys();
        if (appKeys == null || appKeys.isEmpty()) {
            Log.w(TAG_BIND, "startAutoAppKeyBind: no AppKey available — skip.");
            mIsAutoSetupInProgress.postValue(false);
            return;
        }
        final int appKeyIndex = appKeys.get(0).getKeyIndex();

        mPendingBindOperations.clear();
        mAutoBindIndex       = 0;
        mAutoBindNode        = node;
        mIsBindingInProgress = false;

        final String rawName     = normalizeId(node.getNodeName());
        final String resolvedKey = resolveServerKeyByNodeName(rawName);
        final String storeKey    = (resolvedKey != null) ? resolvedKey : rawName;

        Log.d(TAG_BIND, "START AUTO BIND:"
                + " nodeName='" + node.getNodeName() + "'"
                + " rawName='"  + rawName + "'"
                + " resolvedKey='" + resolvedKey + "'"
                + " storeKey='" + storeKey + "'"
                + " unicast=0x" + String.format("%04X", node.getUnicastAddress()));

        boolean isServerNode         = false;
        boolean isClientNode         = false;
        int     serverElementAddress = -1;

        for (final Element element : node.getElements().values()) {
            final int elementAddress = element.getElementAddress();
            for (final MeshModel model : element.getMeshModels().values()) {
                final int modelId = model.getModelId();

                if (!requiresAppKeyBind(modelId)) {
                    Log.d(TAG_BIND, "  SKIP foundation model 0x"
                            + String.format("%04X", modelId)
                            + " at elem 0x" + String.format("%04X", elementAddress));
                    continue;
                }

                if (modelId == MODEL_GENERIC_ONOFF_SERVER) {
                    if (!isServerNode) {
                        isServerNode         = true;
                        serverElementAddress = elementAddress;
                    }
                } else if (modelId == MODEL_GENERIC_ONOFF_CLIENT) {
                    isClientNode = true;
                }

                mPendingBindOperations.add(new int[]{elementAddress, modelId, appKeyIndex});
            }
        }

        // ── Persist server info ───────────────────────────────────────────────
        if (isServerNode && storeKey != null && !storeKey.isEmpty()) {
            if (serverElementAddress != -1) {
                final int existing = ClientServerElementStore.getServerUnicastAddress(storeKey);
                if (existing == -1) {
                    ClientServerElementStore.saveCompleteServerInfo(
                            storeKey, node.getUnicastAddress(), 0, serverElementAddress);
                    Log.d(TAG_BIND, "✅ Server saved: key='" + storeKey + "'");
                } else {
                    ClientServerElementStore.saveServerUnicastAddress(
                            storeKey, node.getUnicastAddress());
                    ClientServerElementStore.saveServerPrimaryElementAddress(
                            storeKey, serverElementAddress);
                    Log.d(TAG_BIND, "✅ Server updated: key='" + storeKey + "'");
                }
                if (storeKey.contains(":")) {
                    final String area = storeKey.split(":")[0].trim();
                    ClientServerElementStore.saveServerAreaId(storeKey, area);
                    Log.d(TAG_BIND, "✅ Server area saved: key='" + storeKey
                            + "' area='" + area + "'");
                }
            } else {
                Log.e(TAG_BIND, "❌ serverElementAddress not found — save skipped");
            }
            final String mac = node.getMacAddress();
            if (mac != null && !mac.isEmpty()) {
                ClientServerElementStore.saveServerMacAddress(storeKey, mac);
            }
        }

        // ── Persist client unicast ────────────────────────────────────────────
        if (isClientNode && rawName != null && !rawName.isEmpty()) {
            final int existingClientUnicast =
                    ClientServerElementStore.getServerUnicastAddress(rawName);
            if (existingClientUnicast == -1) {
                ClientServerElementStore.saveClientUnicastAddress(rawName, node.getUnicastAddress());
                Log.d(TAG_BIND, "✅ Client unicast saved: key='" + rawName + "'");
            }
        }

        if (mPendingBindOperations.isEmpty()) {
            Log.d(TAG_BIND, "No bindable models — finishing immediately.");
            if (isClientNode) saveClientElementAddresses(node, rawName);
            mAutoBindNode = null;
            mIsAutoSetupInProgress.postValue(false);
            return;
        }

        mHandler.postDelayed(this::sendNextAutoBind, 500);
    }
    /**
     * Silent proxy connect — no logger, no queue delay
     * Use this for background auto-connect only
     */
    /**
     * Silent proxy connect — no logger, faster retry
     * Use for background auto-connect only
     */
    public void connectSilent(@NonNull final ExtendedBluetoothDevice device) {
        mBleMeshManager.setSilentProxyMode(false); // auto connect mat use karo
        initIsConnectedLiveData(true);
        mConnectionState.postValue("Connecting…");

        // ✅ No retry, no delay — direct enqueue
        mBleMeshManager.connect(device.getDevice())
                .enqueue();
    }
    // =========================================================================
    // resolveServerKeyByNodeName
    // =========================================================================

    @Nullable
    private String resolveServerKeyByNodeName(@Nullable final String normalizedNodeName) {
        if (normalizedNodeName == null || normalizedNodeName.isEmpty()) return null;

        if (ClientServerElementStore.getServerSvgElementId(normalizedNodeName) != -1) {
            Log.d(TAG_BIND, "resolveServerKey: direct match '" + normalizedNodeName + "'");
            return normalizedNodeName;
        }

        final List<String> allKeys = ClientServerElementStore.getAllServerSvgKeys();
        final List<String> matches = new ArrayList<>();
        for (final String storedKey : allKeys) {
            if (extractPureNameFromKey(storedKey).equals(normalizedNodeName)) {
                matches.add(storedKey);
            }
        }

        if (matches.isEmpty()) {
            Log.w(TAG_BIND, "resolveServerKey: no match for '"
                    + normalizedNodeName + "' — may be client-only");
            return null;
        }
        if (matches.size() == 1) {
            Log.d(TAG_BIND, "resolveServerKey: suffix match '"
                    + normalizedNodeName + "' → '" + matches.get(0) + "'");
            return matches.get(0);
        }

        // Disambiguate by stored unicast address
        if (mAutoBindNode != null) {
            final int currentUnicast = mAutoBindNode.getUnicastAddress();
            for (final String key : matches) {
                final int stored = ClientServerElementStore.getServerUnicastAddress(key);
                if (stored != -1 && stored == currentUnicast) {
                    Log.d(TAG_BIND, "resolveServerKey: unicast match → '" + key + "'");
                    return key;
                }
            }
            // Prefer keys not yet assigned to any node
            for (final String key : matches) {
                if (ClientServerElementStore.getServerUnicastAddress(key) == -1) {
                    Log.d(TAG_BIND, "resolveServerKey: unassigned slot → '" + key + "'");
                    return key;
                }
            }
        }

        Log.w(TAG_BIND, "resolveServerKey: ambiguous — falling back to " + matches.get(0));
        return matches.get(0);
    }

    // =========================================================================
    // extractPureNameFromKey
    // =========================================================================

    @NonNull
    private String extractPureNameFromKey(@Nullable final String key) {
        if (key == null) return "";
        String name = key.trim().toLowerCase();
        final int colon = name.lastIndexOf(':');
        if (colon != -1) name = name.substring(colon + 1).trim();
        // Strip trailing digits (e.g. "light1" → "light")
        name = name.replaceAll("\\s*\\d+$", "").trim();
        return name;
    }
    // ── Publication setup callback ────────────────────────────────────────────
    public interface OnAutoSetupCompleteListener {
        void onAutoSetupComplete(@NonNull ProvisionedMeshNode node);
    }

    private OnAutoSetupCompleteListener mAutoSetupCompleteListener;

    public void setAutoSetupCompleteListener(@Nullable OnAutoSetupCompleteListener listener) {
        mAutoSetupCompleteListener = listener;
    }
    // =========================================================================
    // sendNextAutoBind
    // =========================================================================

    private void sendNextAutoBind() {

        if (mAutoBindNode == null) {
            Log.w(TAG_BIND, "sendNextAutoBind: node is null — stop.");
            return;
        }

        // Guard: do not send if a bind is already in flight.
        if (mIsBindingInProgress) return;

        if (mAutoBindIndex >= mPendingBindOperations.size()) {
            Log.d(TAG_BIND, "✅ ALL MODELS BOUND for node 0x"
                    + String.format("%04X", mAutoBindNode.getUnicastAddress()));

            boolean isClientNode = false;
            for (final Element element : mAutoBindNode.getElements().values()) {
                for (final MeshModel model : element.getMeshModels().values()) {
                    if (model.getModelId() == MODEL_GENERIC_ONOFF_CLIENT) {
                        isClientNode = true;
                        break;
                    }
                }
                if (isClientNode) break;
            }

            final String rawName = normalizeId(mAutoBindNode.getNodeName());
            if (isClientNode && rawName != null) {
                saveClientElementAddresses(mAutoBindNode, rawName);
            }

            final ProvisionedMeshNode completedNode = mAutoBindNode;

            mAutoBindNode        = null;
            mPendingBindOperations.clear();
            mAutoBindIndex       = 0;
            mIsBindingInProgress = false;

            if (mAutoSetupCompleteListener != null && completedNode != null) {
                mHandler.post(() -> mAutoSetupCompleteListener.onAutoSetupComplete(completedNode));
            }

            mIsAutoSetupInProgress.postValue(false);
            return;
        }
        final int[] op           = mPendingBindOperations.get(mAutoBindIndex);
        final int   elementAddr  = op[0];
        final int   modelId      = op[1];
        final int   appKeyIndex  = op[2];

        try {
            mMeshManagerApi.createMeshPdu(
                    mAutoBindNode.getUnicastAddress(),
                    new ConfigModelAppBind(elementAddr, modelId, appKeyIndex));

            mIsBindingInProgress = true;

            Log.d(TAG_BIND, "BIND [" + (mAutoBindIndex + 1) + "/"
                    + mPendingBindOperations.size() + "]"
                    + " Elem=0x" + String.format("%04X", elementAddr)
                    + " Model=0x" + String.format("%04X", modelId));

            mHandler.removeCallbacks(mBindTimeoutRunnable);
            mHandler.postDelayed(mBindTimeoutRunnable, BIND_TIMEOUT_MS);

        } catch (final Exception e) {
            Log.e(TAG_BIND, "❌ BIND PDU creation failed"
                    + " Elem=0x" + String.format("%04X", elementAddr)
                    + " Model=0x" + String.format("%04X", modelId)
                    + " — " + e.getMessage());
            mIsBindingInProgress = false;
            mAutoBindIndex++;
            mHandler.postDelayed(this::sendNextAutoBind, 300);
        }
    }

    // =========================================================================
    // saveClientElementAddresses
    // =========================================================================

    private void saveClientElementAddresses(@NonNull final ProvisionedMeshNode node,
                                            @NonNull final String clientKey) {
        if (clientKey.isEmpty()) {
            Log.w(TAG_BIND, "saveClientElementAddresses: clientKey empty — skip");
            return;
        }

        final List<Element> sortedElements = new ArrayList<>(node.getElements().values());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            sortedElements.sort((a, b) ->
                    Integer.compare(a.getElementAddress(), b.getElementAddress()));
        }

        final Map<Integer, Integer> addressMap = new HashMap<>();
        int clientIndex = 0;

        Log.d(TAG_BIND, "╔══════ SAVING CLIENT ADDRESSES key='" + clientKey + "' ══════");
        for (final Element element : sortedElements) {
            final int elementAddr    = element.getElementAddress();
            boolean   hasClientModel = false;
            for (final MeshModel model : element.getMeshModels().values()) {
                if (model.getModelId() == MODEL_GENERIC_ONOFF_CLIENT) {
                    hasClientModel = true;
                    break;
                }
            }
            if (hasClientModel) {
                addressMap.put(clientIndex, elementAddr);
                Log.d(TAG_BIND, "║  CLIENT[" + clientIndex + "] → 0x"
                        + String.format("%04X", elementAddr));
                clientIndex++;
            }
        }
        Log.d(TAG_BIND, "╚══════ Total client elements: " + addressMap.size() + " ══════");

        if (!addressMap.isEmpty()) {
            ClientServerElementStore.saveAll(clientKey, addressMap);
            Log.d(TAG_BIND, "✅ CLIENT addresses saved under key='" + clientKey + "'");
        } else {
            Log.w(TAG_BIND, "⚠️ No OnOff-Client elements found for node: "
                    + node.getNodeName());
        }
    }

    // =========================================================================
    // normalizeId
    // =========================================================================

    @Nullable
    private String normalizeId(@Nullable final String id) {
        return id == null ? null : id.trim().toLowerCase();
    }
}