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
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Environment;
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
import no.nordicsemi.android.swaromapmesh.transport.ConfigModelPublicationSet;
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
    private static final int    ATTENTION_TIMER = 5;

    static final String EXPORT_PATH = Environment.getExternalStorageDirectory() + File.separator +
            "Nordic Semiconductor" + File.separator + "nRF Mesh" + File.separator;

    // ── SIG model IDs ─────────────────────────────────────────────────────────
    private static final int MODEL_GENERIC_ONOFF_SERVER = 0x1000;
    private static final int MODEL_GENERIC_ONOFF_CLIENT = 0x1001;

    // ── Connection state ──────────────────────────────────────────────────────
    private final MutableLiveData<Boolean> mIsConnectedToProxy = new MutableLiveData<>();
    private MutableLiveData<Boolean>       mIsConnected;
    private final MutableLiveData<Void>    mOnDeviceReady   = new MutableLiveData<>();
    private final MutableLiveData<String>  mConnectionState = new MutableLiveData<>();

    private final SingleLiveEvent<Boolean>               mIsReconnecting              = new SingleLiveEvent<>();
    private final MutableLiveData<UnprovisionedMeshNode> mUnprovisionedMeshNodeLiveData = new MutableLiveData<>();
    private final MutableLiveData<ProvisionedMeshNode>   mProvisionedMeshNodeLiveData   = new MutableLiveData<>();
    private final SingleLiveEvent<Integer>               mConnectedProxyAddress         = new SingleLiveEvent<>();

    private boolean mIsProvisioningComplete = false;

    // ── Selected items ────────────────────────────────────────────────────────
    private final MutableLiveData<ProvisionedMeshNode> mExtendedMeshNode     = new MutableLiveData<>();
    private final MutableLiveData<Element>             mSelectedElement      = new MutableLiveData<>();
    private final MutableLiveData<MeshModel>           mSelectedModel        = new MutableLiveData<>();
    private final MutableLiveData<Provisioner>         mSelectedProvisioner  = new MutableLiveData<>();
    private final MutableLiveData<Group>               mSelectedGroupLiveData = new MutableLiveData<>();

    // ── Network / messaging ───────────────────────────────────────────────────
    private final MeshNetworkLiveData          mMeshNetworkLiveData  = new MeshNetworkLiveData();
    private final SingleLiveEvent<String>      mNetworkImportState   = new SingleLiveEvent<>();
    private final SingleLiveEvent<MeshMessage> mMeshMessageLiveData  = new SingleLiveEvent<>();
    private final MutableLiveData<List<ProvisionedMeshNode>> mProvisionedNodes = new MutableLiveData<>();
    private final MutableLiveData<TransactionStatus> mTransactionStatus = new SingleLiveEvent<>();

    // ── Core objects ──────────────────────────────────────────────────────────
    private final MeshManagerApi mMeshManagerApi;
    private final BleMeshManager mBleMeshManager;
    private final Handler        mHandler;

    private UnprovisionedMeshNode mUnprovisionedMeshNode;
    private ProvisionedMeshNode   mProvisionedMeshNode;
    private boolean               mIsReconnectingFlag;
    private boolean               mIsScanning;
    private boolean               mSetupProvisionedNode;
    private ProvisioningStatusLiveData mProvisioningStateLiveData;
    private MeshNetwork           mMeshNetwork;

    // ── Provisioning state flags ──────────────────────────────────────────────
    private boolean mIsCompositionDataReceived;
    private boolean mIsDefaultTtlReceived;
    private boolean mIsAppKeyAddCompleted;
    private boolean mIsNetworkRetransmitSetCompleted;

    // ── Auto AppKey Bind state ────────────────────────────────────────────────
    private final List<int[]> mPendingBindOperations = new ArrayList<>();
    private int               mAutoBindIndex         = 0;
    private ProvisionedMeshNode mAutoBindNode        = null;
    private boolean           mIsBindingInProgress   = false;

    // ── Pending reverse publication (server → client) ─────────────────────────
    private int mPendingReverseServerUnicast     = -1;
    private int mPendingReverseServerElementAddr = -1;
    private int mPendingReverseClientElementAddr = -1;
    private int mPendingReverseAppKeyIndex       = -1;

    // ── Import callback ────────────────────────────────────────────────────────
    private Runnable mOnNetworkImportedCallback = null;

    // ── Runnables ─────────────────────────────────────────────────────────────
    private final Runnable mReconnectRunnable = this::startScan;
    private final Runnable mScannerTimeout   = () -> {
        stopScan();
        mIsReconnecting.postValue(false);
    };

    // ── Auto setup progress ────────────────────────────────────────────────────
    private final MutableLiveData<Boolean> mIsAutoSetupInProgress = new MutableLiveData<>();

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

    public void setOnNetworkImportedCallback(@Nullable Runnable callback) {
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
        final Group group = mMeshNetwork.getGroup(address);
        if (group != null) mSelectedGroupLiveData.postValue(group);
    }

    // =========================================================================
    // ScannerActivity reconnect helpers
    // =========================================================================
    public ProvisionedMeshNode getLastProvisionedNode() { return mProvisionedMeshNode; }

    public void markSetupRequired(int nodeUnicastAddress) {
        if (mMeshNetwork == null) {
            Log.e(TAG, "markSetupRequired: mMeshNetwork is null — abort");
            return;
        }
        final ProvisionedMeshNode node = mMeshNetwork.getNode(nodeUnicastAddress);
        if (node == null) {
            Log.e(TAG, "markSetupRequired: node not found for 0x"
                    + Integer.toHexString(nodeUnicastAddress));
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

    void connect(final Context context, final ExtendedBluetoothDevice device,
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
        mConnectionState.postValue("Connecting....");
        mBleMeshManager.connect(device.getDevice()).retry(3, 200).enqueue();
    }

    private void connectToProxy(final ExtendedBluetoothDevice device) {
        initIsConnectedLiveData(true);
        mConnectionState.postValue("Connecting....");
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

    public void identifyNode(final ExtendedBluetoothDevice device) {
        final UnprovisionedBeacon beacon = (UnprovisionedBeacon) device.getBeacon();
        if (beacon != null) {
            mMeshManagerApi.identifyNode(beacon.getUuid(), ATTENTION_TIMER);
        } else {
            final byte[] serviceData = Utils.getServiceData(
                    device.getScanResult(), BleMeshManager.MESH_PROVISIONING_UUID);
            if (serviceData != null) {
                final UUID uuid = mMeshManagerApi.getDeviceUuid(serviceData);
                mMeshManagerApi.identifyNode(uuid, ATTENTION_TIMER);
            }
        }
    }

    private void clearExtendedMeshNode() { mExtendedMeshNode.postValue(null); }

    // =========================================================================
    // BleMeshManagerCallbacks
    // =========================================================================
    @Override
    public void onDataReceived(final BluetoothDevice bluetoothDevice,
                               final int mtu, final byte[] pdu) {
        mMeshManagerApi.handleNotifications(mtu, pdu);
    }

    @Override
    public void onDataSent(final BluetoothDevice device, final int mtu, final byte[] pdu) {
        mMeshManagerApi.handleWriteCallbacks(mtu, pdu);
    }

    @Override
    public void onDeviceConnecting(@NonNull final BluetoothDevice device) {
        mConnectionState.postValue("Connecting....");
    }

    @Override
    public void onDeviceConnected(@NonNull final BluetoothDevice device) {
        mIsConnected.postValue(true);
        mConnectionState.postValue("Discovering services....");
        mIsConnectedToProxy.postValue(true);
    }

    @Override
    public void onDeviceDisconnecting(@NonNull final BluetoothDevice device) {
        mConnectionState.postValue(mIsReconnectingFlag ? "Reconnecting..." : "Disconnecting...");
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
            mIsConnected.postValue(false);
            mIsConnectedToProxy.postValue(false);
            if (mConnectedProxyAddress.getValue() != null) {
                final MeshNetwork network = mMeshManagerApi.getMeshNetwork();
                if (network != null) network.setProxyFilter(null);
            }
        }
        mSetupProvisionedNode = false;
        mConnectedProxyAddress.postValue(null);
        mIsBindingInProgress  = false;
    }

    @Override public void onLinkLossOccurred(@NonNull final BluetoothDevice device) {
        mIsConnected.postValue(false);
    }

    @Override public void onServicesDiscovered(@NonNull final BluetoothDevice device,
                                               final boolean optionalServicesFound) {
        mConnectionState.postValue("Initializing...");
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
    public void onError(final BluetoothDevice device,
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

    @Override public void onNetworkLoadFailed(final String error)   { mNetworkImportState.postValue(error); }
    @Override public void onNetworkImportFailed(final String error) { mNetworkImportState.postValue(error); }

    @Override
    public void onNetworkImported(final MeshNetwork meshNetwork) {
        loadNetwork(meshNetwork);
        if (mOnNetworkImportedCallback != null) {
            mHandler.post(mOnNetworkImportedCallback);
            Log.d(TAG, "✅ onNetworkImported: rebuildProvisionedFromMesh callback fired");
        }
        mNetworkImportState.postValue(meshNetwork.getMeshName()
                + " has been successfully imported.\n"
                + "In order to start sending messages to this network, please change the "
                + "provisioner address. Using the same provisioner address will cause messages "
                + "to be discarded due to the usage of incorrect sequence numbers for this "
                + "address. However if the network does not contain any nodes you do not need "
                + "to change the address");
    }

    @Override public void sendProvisioningPdu(final UnprovisionedMeshNode meshNode,
                                              final byte[] pdu) {
        mBleMeshManager.sendPdu(pdu);
    }

    @Override public void onMeshPduCreated(final byte[] pdu) { mBleMeshManager.sendPdu(pdu); }
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

    private void onProvisioningCompleted(final ProvisionedMeshNode node) {
        mIsProvisioningComplete = true;
        mProvisionedMeshNode    = node;
        mIsAutoSetupInProgress.postValue(true);
        mIsReconnecting.postValue(true);
        mBleMeshManager.disconnect().enqueue();
        loadNodes();
        mHandler.post(() -> mConnectionState.postValue("Scanning for provisioned node"));
        mHandler.postDelayed(mReconnectRunnable, 1000);
    }

    private void loadNodes() {
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

    @Override public void onHeartbeatMessageReceived(int src,
                                                     @NonNull ControlMessage message) {}

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

            if (meshMessage.getOpCode() == ProxyConfigMessageOpCodes.FILTER_STATUS) {
                mProvisionedMeshNode = node;
                setSelectedMeshNode(node);
                final ProxyConfigFilterStatus status = (ProxyConfigFilterStatus) meshMessage;
                mConnectedProxyAddress.postValue(status.getSrc());
                mMeshMessageLiveData.postValue(status);

            } else if (meshMessage.getOpCode() == CONFIG_COMPOSITION_DATA_STATUS) {
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

            } else if (meshMessage.getOpCode() == CONFIG_DEFAULT_TTL_STATUS) {
                final ConfigDefaultTtlStatus status = (ConfigDefaultTtlStatus) meshMessage;
                if (mSetupProvisionedNode) {
                    mIsDefaultTtlReceived = true;
                    if (mMeshNetworkLiveData.getAppKeys().isEmpty()) mSetupProvisionedNode = false;
                    mProvisionedMeshNodeLiveData.postValue(node);
                    mProvisioningStateLiveData.onMeshNodeStateUpdated(
                            ProvisionerStates.DEFAULT_TTL_STATUS_RECEIVED);
                    if (!mMeshNetworkLiveData.getAppKeys().isEmpty()) {
                        final ApplicationKey appKey = mMeshNetworkLiveData.getSelectedAppKey();
                        if (appKey != null) {
                            mHandler.postDelayed(() -> {
                                final NetworkKey netKey =
                                        mMeshNetwork.getNetKeys().get(appKey.getBoundNetKeyIndex());
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

            } else if (meshMessage.getOpCode() == CONFIG_NETWORK_TRANSMIT_STATUS) {
                updateNode(node);
                mMeshMessageLiveData.postValue(meshMessage);

            } else if (meshMessage.getOpCode() == CONFIG_APPKEY_STATUS) {
                final ConfigAppKeyStatus status = (ConfigAppKeyStatus) meshMessage;
                if (status.isSuccessful()) {
                    mIsAppKeyAddCompleted = true;
                    mSetupProvisionedNode = false;
                    mProvisionedMeshNodeLiveData.postValue(node);
                    if (mProvisioningStateLiveData != null) {
                        mProvisioningStateLiveData.onMeshNodeStateUpdated(
                                ProvisionerStates.APP_KEY_STATUS_RECEIVED);
                    }
                    Log.d(TAG_BIND, "✅ AppKey SUCCESS → startAutoAppKeyBind node=0x"
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

            } else if (meshMessage.getOpCode() == CONFIG_MODEL_APP_STATUS) {
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
                    mIsBindingInProgress = false;
                    mAutoBindIndex++;
                    sendNextAutoBind();
                }

            } else if (meshMessage.getOpCode() == CONFIG_MODEL_PUBLICATION_STATUS) {
                if (updateNode(node)) {
                    final ConfigModelPublicationStatus status =
                            (ConfigModelPublicationStatus) meshMessage;
                    if (node.getElements().containsKey(status.getElementAddress())) {
                        final Element element = node.getElements().get(status.getElementAddress());
                        mSelectedElement.postValue(element);
                        mSelectedModel.postValue(
                                element.getMeshModels().get(status.getModelIdentifier()));
                    }

                    // ── Pending reverse publication (server → client) ──────────
                    if (mPendingReverseServerUnicast != -1) {
                        final int serverUnicast     = mPendingReverseServerUnicast;
                        final int serverElementAddr = mPendingReverseServerElementAddr;
                        final int clientElementAddr = mPendingReverseClientElementAddr;
                        final int appKeyIndex       = mPendingReverseAppKeyIndex;

                        mPendingReverseServerUnicast     = -1;
                        mPendingReverseServerElementAddr = -1;
                        mPendingReverseClientElementAddr = -1;
                        mPendingReverseAppKeyIndex       = -1;

                        mHandler.postDelayed(() -> {
                            try {
                                Log.d(TAG, "🔄 REVERSE PUB: server=0x"
                                        + String.format("%04X", serverUnicast)
                                        + " serverElem=0x"
                                        + String.format("%04X", serverElementAddr)
                                        + " → clientElem=0x"
                                        + String.format("%04X", clientElementAddr));
                                mMeshManagerApi.createMeshPdu(
                                        serverUnicast,
                                        new ConfigModelPublicationSet(
                                                serverElementAddr,
                                                clientElementAddr,
                                                appKeyIndex,
                                                false, 5, 0, 0, 0, 0,
                                                MODEL_GENERIC_ONOFF_SERVER
                                        )
                                );
                            } catch (Exception e) {
                                Log.e(TAG, "❌ Reverse publication failed: " + e.getMessage());
                            }
                        }, 1000);
                    }
                }

            } else if (meshMessage.getOpCode() == CONFIG_MODEL_SUBSCRIPTION_STATUS) {
                if (updateNode(node)) {
                    final ConfigModelSubscriptionStatus status =
                            (ConfigModelSubscriptionStatus) meshMessage;
                    if (node.getElements().containsKey(status.getElementAddress())) {
                        final Element element = node.getElements().get(status.getElementAddress());
                        mSelectedElement.postValue(element);
                        mSelectedModel.postValue(
                                element.getMeshModels().get(status.getModelIdentifier()));
                    }
                }

            } else if (meshMessage.getOpCode() == CONFIG_NODE_RESET_STATUS) {
                mBleMeshManager.setClearCacheRequired();
                mExtendedMeshNode.postValue(null);
                loadNodes();
                mMeshMessageLiveData.postValue(meshMessage);

            } else if (meshMessage.getOpCode() == CONFIG_RELAY_STATUS) {
                if (updateNode(node)) mMeshMessageLiveData.postValue(meshMessage);

            } else if (meshMessage.getOpCode() == CONFIG_HEARTBEAT_PUBLICATION_STATUS) {
                if (updateNode(node)) {
                    final Element element = node.getElements().get(meshMessage.getSrc());
                    if (element != null)
                        mSelectedModel.postValue(element.getMeshModels()
                                .get((int) SigModelParser.CONFIGURATION_SERVER));
                    mMeshMessageLiveData.postValue(meshMessage);
                }

            } else if (meshMessage.getOpCode() == CONFIG_HEARTBEAT_SUBSCRIPTION_STATUS) {
                if (updateNode(node)) {
                    final Element element = node.getElements().get(meshMessage.getSrc());
                    if (element != null)
                        mSelectedModel.postValue(element.getMeshModels()
                                .get((int) SigModelParser.CONFIGURATION_SERVER));
                    mMeshMessageLiveData.postValue(meshMessage);
                }

            } else if (meshMessage.getOpCode() == CONFIG_GATT_PROXY_STATUS) {
                if (updateNode(node)) mMeshMessageLiveData.postValue(meshMessage);

            } else if (meshMessage.getOpCode() == GENERIC_ON_OFF_STATUS) {
                if (updateNode(node)) {
                    final GenericOnOffStatus status = (GenericOnOffStatus) meshMessage;
                    if (node.getElements().containsKey(status.getSrcAddress())) {
                        final Element element = node.getElements().get(status.getSrcAddress());
                        mSelectedElement.postValue(element);
                        mSelectedModel.postValue(element.getMeshModels()
                                .get((int) SigModelParser.GENERIC_ON_OFF_SERVER));
                    }
                }

            } else if (meshMessage.getOpCode() == GENERIC_LEVEL_STATUS) {
                if (updateNode(node)) {
                    final GenericLevelStatus status = (GenericLevelStatus) meshMessage;
                    if (node.getElements().containsKey(status.getSrcAddress())) {
                        final Element element = node.getElements().get(status.getSrcAddress());
                        mSelectedElement.postValue(element);
                        mSelectedModel.postValue(element.getMeshModels()
                                .get((int) SigModelParser.GENERIC_LEVEL_SERVER));
                    }
                }

            } else if (meshMessage.getOpCode() == SCENE_STATUS) {
                if (updateNode(node)) {
                    final SceneStatus status = (SceneStatus) meshMessage;
                    if (node.getElements().containsKey(status.getSrcAddress()))
                        mSelectedElement.postValue(
                                node.getElements().get(status.getSrcAddress()));
                }

            } else if (meshMessage.getOpCode() == SCENE_REGISTER_STATUS) {
                if (updateNode(node)) {
                    final SceneRegisterStatus status = (SceneRegisterStatus) meshMessage;
                    if (node.getElements().containsKey(status.getSrcAddress()))
                        mSelectedElement.postValue(
                                node.getElements().get(status.getSrcAddress()));
                }

            } else if (meshMessage instanceof VendorModelMessageStatus) {
                if (updateNode(node)) {
                    final VendorModelMessageStatus status = (VendorModelMessageStatus) meshMessage;
                    if (node.getElements().containsKey(status.getSrcAddress())) {
                        final Element element = node.getElements().get(status.getSrcAddress());
                        mSelectedElement.postValue(element);
                        mSelectedModel.postValue(
                                element.getMeshModels().get(status.getModelIdentifier()));
                    }
                }
            }
        }

        if (mMeshMessageLiveData.hasActiveObservers())
            mMeshMessageLiveData.postValue(meshMessage);
        if (mMeshManagerApi.getMeshNetwork() != null)
            mMeshNetworkLiveData.refresh(mMeshManagerApi.getMeshNetwork());
    }

    @Override
    public void onMessageDecryptionFailed(final String meshLayer, final String errorMessage) {
        Log.e(TAG, "Decryption failed in " + meshLayer + " : " + errorMessage);
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
            if (node != null)
                mExtendedMeshNode.postValue(mMeshNetwork.getNode(node.getUuid()));
        }
    }

    private boolean updateNode(@NonNull final ProvisionedMeshNode node) {
        if (mProvisionedMeshNode != null
                && mProvisionedMeshNode.getUnicastAddress() == node.getUnicastAddress()) {
            mProvisionedMeshNode = node;
            mExtendedMeshNode.postValue(node);
            return true;
        }
        return false;
    }

    private void updateSelectedGroup() {
        final Group selectedGroup = mSelectedGroupLiveData.getValue();
        if (selectedGroup != null)
            mSelectedGroupLiveData.postValue(
                    mMeshNetwork.getGroup(selectedGroup.getAddress()));
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
                .setServiceUuid(new ParcelUuid(MESH_PROXY_UUID)).build());
        BluetoothLeScannerCompat.getScanner().startScan(filters, settings, scanCallback);
        mHandler.postDelayed(mScannerTimeout, 20000);
    }

    private void stopScan() {
        mHandler.removeCallbacks(mScannerTimeout);
        BluetoothLeScannerCompat.getScanner().stopScan(scanCallback);
        mIsScanning = false;
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(final int callbackType, final ScanResult result) {
            final ScanRecord scanRecord = result.getScanRecord();
            if (scanRecord != null) {
                final byte[] serviceData = Utils.getServiceData(result, MESH_PROXY_UUID);
                if (serviceData != null
                        && mMeshManagerApi.isAdvertisedWithNodeIdentity(serviceData)) {
                    final ProvisionedMeshNode node = mProvisionedMeshNode;
                    if (mMeshManagerApi.nodeIdentityMatches(node, serviceData)) {
                        stopScan();
                        mConnectionState.postValue("Provisioned node found");
                        onProvisionedDeviceFound(node, new ExtendedBluetoothDevice(result));
                    }
                }
            }
        }
    };

    private void onProvisionedDeviceFound(final ProvisionedMeshNode node,
                                          final ExtendedBluetoothDevice device) {
        mSetupProvisionedNode = true;
        mProvisionedMeshNode  = node;
        mIsReconnectingFlag   = true;
        mHandler.postDelayed(() -> connectToProxy(device), 2000);
    }

    // =========================================================================
    // setPendingReversePublication
    // =========================================================================
    public void setPendingReversePublication(int serverUnicast, int serverElementAddr,
                                             int clientElementAddr, int appKeyIndex) {
        mPendingReverseServerUnicast     = serverUnicast;
        mPendingReverseServerElementAddr = serverElementAddr;
        mPendingReverseClientElementAddr = clientElementAddr;
        mPendingReverseAppKeyIndex       = appKeyIndex;
        Log.d(TAG, "setPendingReversePublication: server=0x"
                + String.format("%04X", serverUnicast)
                + " serverElem=0x" + String.format("%04X", serverElementAddr)
                + " clientElem=0x" + String.format("%04X", clientElementAddr));
    }

    // =========================================================================
    // AUTO APP KEY BIND
    // =========================================================================
    private void startAutoAppKeyBind(@NonNull final ProvisionedMeshNode node) {
        mIsAutoSetupInProgress.postValue(true);
        final List<ApplicationKey> appKeys = mMeshNetworkLiveData.getAppKeys();
        if (appKeys == null || appKeys.isEmpty()) {
            Log.w(TAG_BIND, "startAutoAppKeyBind: No AppKey — skip.");
            mIsAutoSetupInProgress.postValue(false);
            return;
        }
        final int appKeyIndex = appKeys.get(0).getKeyIndex();

        mPendingBindOperations.clear();
        mAutoBindIndex       = 0;
        mAutoBindNode        = node;
        mIsBindingInProgress = false;

        // ── STEP 1: Resolve the store key for this node (server nodes only) ──
        // Client nodes use plain normalizeId() — they have their own simple key
        final String rawName       = normalizeId(node.getNodeName());
        final String resolvedKey   = resolveServerKeyByNodeName(rawName);
        final String storeKey = (resolvedKey != null) ? resolvedKey : rawName;

        Log.d(TAG_BIND, "START AUTO BIND: nodeName='" + node.getNodeName()
                + "' rawName='" + rawName
                + "' resolvedKey='" + resolvedKey
                + "' storeKey='" + storeKey
                + "' unicast=0x" + String.format("%04X", node.getUnicastAddress()));

        boolean isServerNode         = false;
        boolean isClientNode         = false;
        int     serverElementAddress = -1;

        for (Element element : node.getElements().values()) {
            final int elementAddress = element.getElementAddress();
            for (MeshModel model : element.getMeshModels().values()) {
                final int modelId = model.getModelId();
                if (modelId == MODEL_GENERIC_ONOFF_SERVER) {
                    if (!isServerNode) {
                        isServerNode         = true;
                        serverElementAddress = elementAddress;
                    }
                    mPendingBindOperations.add(new int[]{elementAddress, modelId, appKeyIndex});
                } else if (modelId == MODEL_GENERIC_ONOFF_CLIENT) {
                    isClientNode = true;
                    mPendingBindOperations.add(new int[]{elementAddress, modelId, appKeyIndex});
                } else {
                    mPendingBindOperations.add(new int[]{elementAddress, modelId, appKeyIndex});
                }
            }
        }

        // ── STEP 2: Save server info under resolvedKey (full key like "pdri:relay node1") ──
        if (isServerNode && storeKey != null && !storeKey.isEmpty()) {
            if (serverElementAddress == -1) {
                Log.e(TAG_BIND, "❌ serverElementAddress not found — save skip");
            } else {
                int existing = ClientServerElementStore.getServerUnicastAddress(storeKey);
                if (existing == -1) {
                    ClientServerElementStore.saveCompleteServerInfo(
                            storeKey,
                            node.getUnicastAddress(),
                            0,
                            serverElementAddress
                    );
                    Log.d(TAG_BIND, "✅ Server saved first time under key='" + storeKey + "'");
                } else {
                    ClientServerElementStore.saveServerUnicastAddress(
                            storeKey, node.getUnicastAddress());
                    ClientServerElementStore.saveServerPrimaryElementAddress(
                            storeKey, serverElementAddress);
                    Log.d(TAG_BIND, "✅ Server updated: key='" + storeKey
                            + "' unicast=0x" + String.format("%04X", node.getUnicastAddress())
                            + " elementAddr=0x" + String.format("%04X", serverElementAddress));
                }

                int existingSvgId = ClientServerElementStore.getServerSvgElementId(storeKey);
                if (existingSvgId == -1) {
                    Log.w(TAG_BIND, "⚠️ svgId not yet set for key='" + storeKey + "'");
                } else {
                    Log.d(TAG_BIND, "✅ svgId=" + existingSvgId
                            + " ready for key='" + storeKey + "'");
                }
            }
        }

        // ── STEP 3: For client nodes, save unicast under plain rawName key ──
        // e.g. rawName = "relay node" — client addresses will be saved here later
        if (isClientNode && rawName != null && !rawName.isEmpty()) {
            int existingClientUnicast =
                    ClientServerElementStore.getServerUnicastAddress(rawName);
            if (existingClientUnicast == -1) {
                ClientServerElementStore.saveServerUnicastAddress(
                        rawName, node.getUnicastAddress());
                Log.d(TAG_BIND, "✅ Client unicast saved: key='" + rawName
                        + "' unicast=0x" + String.format("%04X", node.getUnicastAddress()));
            }
        }

        int svgId = ClientServerElementStore.getServerSvgElementId(storeKey);
        Log.d(TAG_BIND, "Type: "
                + (isServerNode ? "SERVER" : "")
                + (isClientNode ? (isServerNode ? "+CLIENT" : "CLIENT") : "")
                + (svgId != -1 ? " | svgId=" + svgId : " | svgId NOT SET"));

        if (mPendingBindOperations.isEmpty()) {
            if (isClientNode) saveClientElementAddresses(node, rawName);
            mAutoBindNode = null;
            mIsAutoSetupInProgress.postValue(false);
            return;
        }

        mHandler.postDelayed(this::sendNextAutoBind, 500);
    }

    // =========================================================================
    // resolveServerKeyByNodeName
    // ─────────────────────────────────────────────────────────────────────────
    private String resolveServerKeyByNodeName(String normalizedNodeName) {
        if (normalizedNodeName == null || normalizedNodeName.isEmpty()) return null;

        // Direct lookup first — exact match
        int direct = ClientServerElementStore.getServerSvgElementId(normalizedNodeName);
        if (direct != -1) {
            Log.d(TAG_BIND, "resolveServerKey: direct match '" + normalizedNodeName + "'");
            return normalizedNodeName;
        }

        // Suffix match against keys that have svgId stored
        // e.g. "pdri:relay node1" → strip prefix and digits → "relay node"
        List<String> allKeys = ClientServerElementStore.getAllServerSvgKeys();
        List<String> matches = new ArrayList<>();

        for (String storedKey : allKeys) {
            String pureName = extractPureNameFromKey(storedKey);
            if (pureName.equals(normalizedNodeName)) {
                matches.add(storedKey);
            }
        }

        if (matches.isEmpty()) {
            Log.w(TAG_BIND, "resolveServerKey: no svgId-key match for '"
                    + normalizedNodeName + "' — node may be client-only");
            return null;
        }

        if (matches.size() == 1) {
            Log.d(TAG_BIND, "resolveServerKey: suffix match '"
                    + normalizedNodeName + "' → '" + matches.get(0) + "'");
            return matches.get(0);
        }

        // Multiple matches — disambiguate by current node's unicast address
        if (mAutoBindNode != null) {
            int currentUnicast = mAutoBindNode.getUnicastAddress();
            for (String key : matches) {
                int storedUnicast = ClientServerElementStore.getServerUnicastAddress(key);
                if (storedUnicast != -1 && storedUnicast == currentUnicast) {
                    Log.d(TAG_BIND, "resolveServerKey: unicast match 0x"
                            + String.format("%04X", currentUnicast) + " → '" + key + "'");
                    return key;
                }
            }
            // New node — pick a key with unicast not yet assigned
            for (String key : matches) {
                int storedUnicast = ClientServerElementStore.getServerUnicastAddress(key);
                if (storedUnicast == -1) {
                    Log.d(TAG_BIND, "resolveServerKey: new node → unset key '"
                            + key + "' unicast=0x"
                            + String.format("%04X", mAutoBindNode.getUnicastAddress()));
                    return key;
                }
            }
        }

        Log.w(TAG_BIND, "resolveServerKey: fallback first match: " + matches.get(0));
        return matches.get(0);
    }

    // =========================================================================
    // extractPureNameFromKey
    private String extractPureNameFromKey(String key) {
        if (key == null) return "";
        String name = key.trim().toLowerCase();
        // Strip area prefix (everything before and including last ":")
        int colon = name.lastIndexOf(":");
        if (colon != -1) name = name.substring(colon + 1).trim();
        // Strip trailing digits and spaces
        name = name.replaceAll("\\s*\\d+$", "").replaceAll("\\d+$", "").trim();
        return name;
    }

    // =========================================================================
    // sendNextAutoBind
    // =========================================================================
    private void sendNextAutoBind() {
        if (mAutoBindNode == null) {
            Log.w(TAG_BIND, "sendNextAutoBind: node null — stop.");
            return;
        }
        if (mIsBindingInProgress) return;

        if (mAutoBindIndex >= mPendingBindOperations.size()) {
            Log.d(TAG_BIND, "✅ ALL MODELS BOUND for node 0x"
                    + String.format("%04X", mAutoBindNode.getUnicastAddress()));

            boolean isClientNode = false;
            boolean isServerNode = false;

            for (Element element : mAutoBindNode.getElements().values()) {
                for (MeshModel model : element.getMeshModels().values()) {
                    if (model.getModelId() == MODEL_GENERIC_ONOFF_CLIENT) isClientNode = true;
                    if (model.getModelId() == MODEL_GENERIC_ONOFF_SERVER) isServerNode = true;
                }
            }

            final String rawName = normalizeId(mAutoBindNode.getNodeName());

            if (isClientNode) {
                // ✅ Client addresses saved under plain rawName key
                saveClientElementAddresses(mAutoBindNode, rawName);
            }

            if (isServerNode) {
                final ProvisionedMeshNode serverNode = mAutoBindNode;
                mHandler.postDelayed(() -> triggerAutoPublication(serverNode), 2000);
            } else {
                mIsAutoSetupInProgress.postValue(false);
            }

            mAutoBindNode        = null;
            mPendingBindOperations.clear();
            mAutoBindIndex       = 0;
            mIsBindingInProgress = false;
            return;
        }

        final int[] op        = mPendingBindOperations.get(mAutoBindIndex);
        final int elementAddr = op[0];
        final int modelId     = op[1];
        final int appKeyIndex = op[2];

        try {
            mMeshManagerApi.createMeshPdu(
                    mAutoBindNode.getUnicastAddress(),
                    new ConfigModelAppBind(elementAddr, modelId, appKeyIndex)
            );
            mIsBindingInProgress = true;

            if (modelId == MODEL_GENERIC_ONOFF_SERVER || modelId == MODEL_GENERIC_ONOFF_CLIENT) {
                Log.d(TAG_BIND, "BIND [" + (mAutoBindIndex + 1) + "/"
                        + mPendingBindOperations.size() + "]"
                        + " Element=0x" + String.format("%04X", elementAddr)
                        + " Model=0x" + String.format("%04X", modelId));
            }
        } catch (Exception e) {
            Log.e(TAG_BIND, "❌ BIND FAILED Element=0x"
                    + String.format("%04X", elementAddr)
                    + " Model=0x" + String.format("%04X", modelId)
                    + " Error: " + e.getMessage());
            mAutoBindIndex++;
            mHandler.postDelayed(this::sendNextAutoBind, 300);
        }
    }

    // =========================================================================
    // triggerAutoPublication
    // ─────────────────────────────────────────────────────────────────────────

    private void triggerAutoPublication(@NonNull final ProvisionedMeshNode serverNode) {
        if (mMeshNetwork == null) {
            Log.e(TAG_BIND, "triggerAutoPublication: mMeshNetwork null — abort");
            return;
        }

        final List<ApplicationKey> appKeys = mMeshNetworkLiveData.getAppKeys();
        if (appKeys == null || appKeys.isEmpty()) {
            Log.e(TAG_BIND, "triggerAutoPublication: no AppKey — abort");
            return;
        }
        final int appKeyIndex = appKeys.get(0).getKeyIndex();

        // ── Resolve server store key ──────────────────────────────────────────
        final String rawServerName    = normalizeId(serverNode.getNodeName());
        final String serverStoreKey   = resolveServerKeyByNodeName(rawServerName);

        if (serverStoreKey == null || serverStoreKey.isEmpty()) {
            Log.e(TAG_BIND, "triggerAutoPublication: cannot resolve server store key"
                    + " for nodeName='" + serverNode.getNodeName() + "' — abort");
            mIsAutoSetupInProgress.postValue(false);
            return;
        }

        // ── Get server svgId ──────────────────────────────────────────────────
        final int serverSvgId = ClientServerElementStore.getServerSvgElementId(serverStoreKey);
        if (serverSvgId == -1) {
            Log.e(TAG_BIND, "triggerAutoPublication: svgId not set for key='"
                    + serverStoreKey + "' — abort."
                    + " Make sure saveElementId() was called from DeviceDetailActivity.");
            mIsAutoSetupInProgress.postValue(false);
            return;
        }

        // ── Get server element address ────────────────────────────────────────
        final int serverElementAddr =
                ClientServerElementStore.getServerPrimaryElementAddress(serverStoreKey);
        if (serverElementAddr == -1) {
            Log.e(TAG_BIND, "triggerAutoPublication: server primary element addr"
                    + " not found for key='" + serverStoreKey + "' — abort");
            mIsAutoSetupInProgress.postValue(false);
            return;
        }

        // ── Get server area ID for area matching ──────────────────────────────
        String serverAreaId = ClientServerElementStore.getServerAreaId(serverStoreKey);
        Log.d(TAG_BIND, "triggerAutoPublication: serverAreaId='" + serverAreaId + "'");

        Log.d(TAG_BIND, "triggerAutoPublication ▶"
                + " serverKey='" + serverStoreKey + "'"
                + " svgId=" + serverSvgId
                + " serverElem=0x" + String.format("%04X", serverElementAddr)
                + " serverArea='" + serverAreaId + "'");


        int clientElementAddr = -1;
        int clientUnicast     = -1;
        String matchedClientKey = null;

        final String provisionerUuid =
                mMeshNetwork.getSelectedProvisioner().getProvisionerUuid();

        for (ProvisionedMeshNode candidate : mMeshNetwork.getNodes()) {
            if (candidate.getUuid().equalsIgnoreCase(provisionerUuid)) continue;
            if (candidate.getUnicastAddress() == serverNode.getUnicastAddress()) continue;

            // Check candidate has GenericOnOffClient model
            boolean hasClient = false;
            for (Element el : candidate.getElements().values()) {
                for (MeshModel m : el.getMeshModels().values()) {
                    if (m.getModelId() == MODEL_GENERIC_ONOFF_CLIENT) {
                        hasClient = true;
                        break;
                    }
                }
                if (hasClient) break;
            }
            if (!hasClient) continue;

            final String devKey = normalizeId(candidate.getNodeName());

            // serverSvgId IS the element index saved in element_addr_<devKey>_<svgId>
            final int addr = ClientServerElementStore.getClientAddress(devKey, serverSvgId);
            if (addr == -1) {
                Log.d(TAG_BIND, "  no client addr for devKey='" + devKey
                        + "' svgId=" + serverSvgId);
                continue;
            }

            // ✅ FIX: getClientAreaId now uses node UUID for reliable area lookup
            //        No more same-pureName confusion between VCRI and PDRI nodes
            String clientAreaId = getClientAreaId(candidate, devKey);
            Log.d(TAG_BIND, "  candidate='" + devKey
                    + "' clientAreaId='" + clientAreaId + "'"
                    + " serverAreaId='" + serverAreaId + "'");

            if (serverAreaId != null && !serverAreaId.isEmpty()) {
                if (!serverAreaId.equalsIgnoreCase(clientAreaId)) {
                    Log.d(TAG_BIND, "  ❌ Area mismatch — skip: server="
                            + serverAreaId + " client=" + clientAreaId);
                    continue;
                }
            }

            // ✅ Same area — match found
            clientElementAddr = addr;
            clientUnicast     = candidate.getUnicastAddress();
            matchedClientKey  = devKey;
            Log.d(TAG_BIND, "✅ Client match (same area): devKey='" + devKey
                    + "' svgId=" + serverSvgId
                    + " clientElem=0x" + String.format("%04X", clientElementAddr)
                    + " clientUnicast=0x" + String.format("%04X", clientUnicast)
                    + " area=" + clientAreaId);
            break;
        }
        if (clientElementAddr == -1 || clientUnicast == -1) {
            Log.w(TAG_BIND, "triggerAutoPublication: no client found for svgId=" + serverSvgId
                    + " — publication skipped.");
            mIsAutoSetupInProgress.postValue(false);
            return;
        }

        // ✅ NEW: Save the client element to SVG mapping
        // This allows AreaClientListActivity to correctly look up the server
        if (matchedClientKey != null && serverSvgId != -1) {
            SharedPreferences prefs = ClientServerElementStore.getPrefsPublic();
            if (prefs != null) {

                int elementIndex = -1;
                for (int i = 1; i <= 40; i++) {  // Check up to 40 elements
                    int addr = ClientServerElementStore.getClientAddress(matchedClientKey, i);
                    if (addr == clientElementAddr) {
                        elementIndex = i;
                        break;
                    }
                }

                if (elementIndex != -1) {
                    String mappingKey = "client_element_svg_" + matchedClientKey.toLowerCase() + "_" + elementIndex;
                    prefs.edit().putString(mappingKey, String.valueOf(serverSvgId)).apply();
                    Log.d(TAG_BIND, "✅ Saved client element mapping: " + matchedClientKey
                            + "[" + elementIndex + "] → SVG ID " + serverSvgId);
                } else {
                    // Fallback: save with index 1
                    String mappingKey = "client_element_svg_" + matchedClientKey.toLowerCase() + "_1";
                    prefs.edit().putString(mappingKey, String.valueOf(serverSvgId)).apply();
                    Log.d(TAG_BIND, "⚠️ Saved client element mapping (fallback index 1): "
                            + matchedClientKey + " → SVG ID " + serverSvgId);
                }
            }
        }

        final int finalClientElem    = clientElementAddr;
        final int finalClientUnicast = clientUnicast;
        final int finalServerElem    = serverElementAddr;
        final int finalServerUnicast = serverNode.getUnicastAddress();
        final String finalMatchedClientKey = matchedClientKey;
        final String finalServerStoreKey = serverStoreKey;

        // ── STEP 1: Client → Server publication ──────────────────────────────
        Log.d(TAG_BIND, "📤 PUB STEP1 (client→server):"
                + " clientUnicast=0x" + String.format("%04X", finalClientUnicast)
                + " clientElem=0x" + String.format("%04X", finalClientElem)
                + " → serverElem=0x" + String.format("%04X", finalServerElem));

        try {
            mMeshManagerApi.createMeshPdu(
                    finalClientUnicast,
                    new ConfigModelPublicationSet(
                            finalClientElem,
                            finalServerElem,
                            appKeyIndex,
                            false, 5, 0, 0, 0, 0,
                            MODEL_GENERIC_ONOFF_CLIENT
                    )
            );
        } catch (Exception e) {
            Log.e(TAG_BIND, "❌ PUB STEP1 failed: " + e.getMessage());
            mIsAutoSetupInProgress.postValue(false);
            return;
        }

        // ── STEP 2: Server → Client publication (reverse) ────────────────────
        // Also save the client-to-server mapping for UI lookup
        mHandler.postDelayed(() -> {
            // Save client-to-server mapping for AreaClientListActivity
            if (finalMatchedClientKey != null && finalServerStoreKey != null && serverSvgId != -1) {
                SharedPreferences prefs = ClientServerElementStore.getPrefsPublic();
                if (prefs != null) {
                    String ctosKey = "client_to_server_" + finalMatchedClientKey + "_" + serverSvgId;
                    prefs.edit().putString(ctosKey, finalServerStoreKey).apply();
                    Log.d(TAG_BIND, "✅ Saved client_to_server mapping: " + ctosKey
                            + " = " + finalServerStoreKey);
                }
            }

            Log.d(TAG_BIND, "📤 PUB STEP2 (server→client):"
                    + " serverUnicast=0x" + String.format("%04X", finalServerUnicast)
                    + " serverElem=0x" + String.format("%04X", finalServerElem)
                    + " → clientElem=0x" + String.format("%04X", finalClientElem));
            try {
                mMeshManagerApi.createMeshPdu(
                        finalServerUnicast,
                        new ConfigModelPublicationSet(
                                finalServerElem,
                                finalClientElem,
                                appKeyIndex,
                                false, 5, 0, 0, 0, 0,
                                MODEL_GENERIC_ONOFF_SERVER
                        )
                );
            } catch (Exception e) {
                Log.e(TAG_BIND, "❌ PUB STEP2 failed: " + e.getMessage());
            }
            mIsAutoSetupInProgress.postValue(false);
        }, 1500);
    }
// =========================================================================
// CHANGE 1: getClientAreaId() — FIXED
// Old logic: element_id_ prefix se pureName match karta tha clientKey se
// Problem: VCRI:SW-CN01-AA aur PDRI:SW-CN01-AA dono ka pureName "sw-cn01-aa"
//          same hota tha, isliye wrong area return hota tha
// Fix: directly "element_id_" + full svgDeviceId prefix check karo
//      jisme clientKey (pure name) match ho, aur area return karo
// =========================================================================

    private String getClientAreaId(ProvisionedMeshNode clientNode, String clientKey) {
        SharedPreferences prefs = ClientServerElementStore.getPrefsPublic();
        if (prefs == null) return null;

        // clientKey = "sw-cn01-aa" (normalized node name)
        // We need to find which provisioned SVG device ID belongs to this node
        // by matching the pure name part after ":" in the SVG device ID

        // First try: match via node UUID → node_svg_ mapping (most reliable)
        if (clientNode != null) {
            String svgId = prefs.getString("node_svg_" + clientNode.getUuid(), null);
            if (svgId != null && svgId.contains(":")) {
                String area = svgId.substring(0, svgId.indexOf(":")).trim();
                Log.d(TAG_BIND, "getClientAreaId: uuid→svgId='" + svgId
                        + "' area='" + area + "'");
                return area;
            }
        }

        // Fallback: scan element_id_ keys for a match
        // element_id_VCRI:SW-CN01-AA  → pure name "sw-cn01-aa" → area "VCRI"
        // element_id_PDRI:SW-CN01-AA  → pure name "sw-cn01-aa" → area "PDRI"
        // We must match ONLY the one whose unicast matches this clientNode
        String prefix = "element_id_";
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            String k = entry.getKey();
            if (!k.startsWith(prefix)) continue;

            String svgDeviceId = k.substring(prefix.length()); // "VCRI:SW-CN01-AA"
            String pureName    = extractPureNameFromKey(svgDeviceId); // "sw-cn01-aa"

            if (!pureName.equalsIgnoreCase(clientKey.trim())) continue;

            // Pure name matches — now verify this svgDeviceId belongs to THIS node
            // by checking node_svg_ prefs for clientNode uuid
            if (clientNode != null) {
                String mappedSvg = prefs.getString(
                        "node_svg_" + clientNode.getUuid(), null);
                if (mappedSvg != null) {
                    // Only return area if this svgDeviceId matches the mapped svg
                    if (!svgDeviceId.equalsIgnoreCase(mappedSvg)) continue;
                }
            }

            int colon = svgDeviceId.indexOf(":");
            if (colon != -1) {
                String area = svgDeviceId.substring(0, colon).trim();
                Log.d(TAG_BIND, "getClientAreaId: fallback svgDeviceId='"
                        + svgDeviceId + "' area='" + area + "'");
                return area;
            }
        }

        Log.w(TAG_BIND, "getClientAreaId: no area found for clientKey='" + clientKey + "'");
        return null;
    }

    private void saveClientElementAddresses(@NonNull final ProvisionedMeshNode node,
                                            @NonNull final String clientKey) {
        if (clientKey.isEmpty()) {
            Log.w(TAG_BIND, "saveClientElementAddresses: clientKey empty — skip");
            return;
        }

        // ── Sort elements by element address (ascending) ─────────────────────
        List<Element> sortedElements = new ArrayList<>(node.getElements().values());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            sortedElements.sort((a, b) ->
                    Integer.compare(a.getElementAddress(), b.getElementAddress()));
        }

        final Map<Integer, Integer> addressMap = new HashMap<>();
        int clientIndex = 0;  // 0-based sequential counter for CLIENT elements only

        Log.d(TAG_BIND, "╔══════════════════════════════════════════════════");
        Log.d(TAG_BIND, "║ SAVING CLIENT ADDRESSES for key='" + clientKey + "'");

        for (Element element : sortedElements) {
            int elementAddr = element.getElementAddress();

            boolean hasClientModel = false;
            for (MeshModel model : element.getMeshModels().values()) {
                if (model.getModelId() == MODEL_GENERIC_ONOFF_CLIENT) {
                    hasClientModel = true;
                    break;
                }
            }

            if (hasClientModel) {
                // ✅ 0-based: first CLIENT = 0, second = 1, ...
                addressMap.put(clientIndex, elementAddr);
                Log.d(TAG_BIND, "║  CLIENT[" + clientIndex + "] → 0x"
                        + String.format("%04X", elementAddr));
                clientIndex++;
            }
        }

        Log.d(TAG_BIND, "║ Total client elements: " + addressMap.size());
        Log.d(TAG_BIND, "╚══════════════════════════════════════════════════");

        if (!addressMap.isEmpty()) {
            ClientServerElementStore.saveAll(clientKey, addressMap);
            Log.d(TAG_BIND, "✅ CLIENT addresses saved under key='" + clientKey + "'");
        } else {
            Log.w(TAG_BIND, "⚠️ No client elements found for: " + node.getNodeName());
        }
    }



    private String normalizeId(String id) {
        return id == null ? null : id.trim().toLowerCase();
    }
}