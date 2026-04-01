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
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;

import androidx.annotation.NonNull;
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
import no.nordicsemi.android.swaromapmesh.transport.ConfigGattProxyStatus;
import no.nordicsemi.android.swaromapmesh.transport.ConfigModelAppBind;
import no.nordicsemi.android.swaromapmesh.transport.ConfigModelAppStatus;
import no.nordicsemi.android.swaromapmesh.transport.ConfigModelPublicationStatus;
import no.nordicsemi.android.swaromapmesh.transport.ConfigModelSubscriptionStatus;
import no.nordicsemi.android.swaromapmesh.transport.ConfigNetworkTransmitStatus;
import no.nordicsemi.android.swaromapmesh.transport.ConfigNodeResetStatus;
import no.nordicsemi.android.swaromapmesh.transport.ConfigRelayStatus;
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
import no.nordicsemi.android.swaromapmesh.utils.MeshAddress;
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

    private static final String TAG = NrfMeshRepository.class.getSimpleName();
    private static final String TAG_BIND = "AUTO_BIND";

    private static final int ATTENTION_TIMER = 5;
    static final String EXPORT_PATH = Environment.getExternalStorageDirectory() + File.separator +
            "Nordic Semiconductor" + File.separator + "nRF Mesh" + File.separator;

    // ── Connection state ──────────────────────────────────────────────────────
    private final MutableLiveData<Boolean> mIsConnectedToProxy = new MutableLiveData<>();
    private MutableLiveData<Boolean> mIsConnected;
    private final MutableLiveData<Void> mOnDeviceReady = new MutableLiveData<>();
    private final MutableLiveData<String> mConnectionState = new MutableLiveData<>();

    private final SingleLiveEvent<Boolean> mIsReconnecting = new SingleLiveEvent<>();
    private final MutableLiveData<UnprovisionedMeshNode> mUnprovisionedMeshNodeLiveData = new MutableLiveData<>();
    private final MutableLiveData<ProvisionedMeshNode> mProvisionedMeshNodeLiveData = new MutableLiveData<>();
    private final SingleLiveEvent<Integer> mConnectedProxyAddress = new SingleLiveEvent<>();

    private boolean mIsProvisioningComplete = false;

    // ── Selected items ────────────────────────────────────────────────────────
    private final MutableLiveData<ProvisionedMeshNode> mExtendedMeshNode = new MutableLiveData<>();
    private final MutableLiveData<Element> mSelectedElement = new MutableLiveData<>();
    private final MutableLiveData<MeshModel> mSelectedModel = new MutableLiveData<>();
    private final MutableLiveData<Provisioner> mSelectedProvisioner = new MutableLiveData<>();
    private final MutableLiveData<Group> mSelectedGroupLiveData = new MutableLiveData<>();

    // ── Network / messaging ───────────────────────────────────────────────────
    private final MeshNetworkLiveData mMeshNetworkLiveData = new MeshNetworkLiveData();
    private final SingleLiveEvent<String> mNetworkImportState = new SingleLiveEvent<>();
    private final SingleLiveEvent<MeshMessage> mMeshMessageLiveData = new SingleLiveEvent<>();
    private final MutableLiveData<List<ProvisionedMeshNode>> mProvisionedNodes = new MutableLiveData<>();
    private final MutableLiveData<TransactionStatus> mTransactionStatus = new SingleLiveEvent<>();

    // ── Core objects ──────────────────────────────────────────────────────────
    private final MeshManagerApi mMeshManagerApi;
    private final BleMeshManager mBleMeshManager;
    private final Handler mHandler;

    private UnprovisionedMeshNode mUnprovisionedMeshNode;
    private ProvisionedMeshNode mProvisionedMeshNode;
    private boolean mIsReconnectingFlag;
    private boolean mIsScanning;
    private boolean mSetupProvisionedNode;
    private ProvisioningStatusLiveData mProvisioningStateLiveData;
    private MeshNetwork mMeshNetwork;

    // ── Provisioning state flags ──────────────────────────────────────────────
    private boolean mIsCompositionDataReceived;
    private boolean mIsDefaultTtlReceived;
    private boolean mIsAppKeyAddCompleted;
    private boolean mIsNetworkRetransmitSetCompleted;

    // ── Auto AppKey Bind state ────────────────────────────────────────────────
    private final List<int[]> mPendingBindOperations = new ArrayList<>();
    private int mAutoBindIndex = 0;
    private ProvisionedMeshNode mAutoBindNode = null;
    private boolean mIsBindingInProgress = false;

    // ── Runnables ─────────────────────────────────────────────────────────────
    private final Runnable mReconnectRunnable = this::startScan;
    private final Runnable mScannerTimeout = () -> {
        stopScan();
        mIsReconnecting.postValue(false);
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
    // Getters / Setters
    // =========================================================================
    LiveData<Void> isDeviceReady() {
        return mOnDeviceReady;
    }

    LiveData<String> getConnectionState() {
        return mConnectionState;
    }

    LiveData<Boolean> isConnected() {
        return mIsConnected;
    }

    LiveData<Boolean> isConnectedToProxy() {
        return mIsConnectedToProxy;
    }

    LiveData<Boolean> isReconnecting() {
        return mIsReconnecting;
    }

    boolean isProvisioningComplete() {
        return mIsProvisioningComplete;
    }

    boolean isCompositionDataStatusReceived() {
        return mIsCompositionDataReceived;
    }

    boolean isDefaultTtlReceived() {
        return mIsDefaultTtlReceived;
    }

    boolean isAppKeyAddCompleted() {
        return mIsAppKeyAddCompleted;
    }

    boolean isNetworkRetransmitSetCompleted() {
        return mIsNetworkRetransmitSetCompleted;
    }

    final MeshNetworkLiveData getMeshNetworkLiveData() {
        return mMeshNetworkLiveData;
    }

    LiveData<List<ProvisionedMeshNode>> getNodes() {
        return mProvisionedNodes;
    }

    LiveData<String> getNetworkLoadState() {
        return mNetworkImportState;
    }

    ProvisioningStatusLiveData getProvisioningState() {
        return mProvisioningStateLiveData;
    }

    LiveData<TransactionStatus> getTransactionStatus() {
        return mTransactionStatus;
    }

    MeshManagerApi getMeshManagerApi() {
        return mMeshManagerApi;
    }

    BleMeshManager getBleMeshManager() {
        return mBleMeshManager;
    }

    LiveData<MeshMessage> getMeshMessageLiveData() {
        return mMeshMessageLiveData;
    }

    LiveData<Group> getSelectedGroup() {
        return mSelectedGroupLiveData;
    }

    LiveData<UnprovisionedMeshNode> getUnprovisionedMeshNode() {
        return mUnprovisionedMeshNodeLiveData;
    }

    LiveData<Integer> getConnectedProxyAddress() {
        return mConnectedProxyAddress;
    }

    LiveData<ProvisionedMeshNode> getSelectedMeshNode() {
        return mExtendedMeshNode;
    }

    LiveData<Element> getSelectedElement() {
        return mSelectedElement;
    }

    LiveData<Provisioner> getSelectedProvisioner() {
        return mSelectedProvisioner;
    }

    LiveData<MeshModel> getSelectedModel() {
        return mSelectedModel;
    }

    void clearTransactionStatus() {
        if (mTransactionStatus.getValue() != null) mTransactionStatus.postValue(null);
    }

    void setSelectedMeshNode(final ProvisionedMeshNode node) {
        mProvisionedMeshNode = node;
        mExtendedMeshNode.postValue(node);
    }

    void setSelectedElement(final Element element) {
        mSelectedElement.postValue(element);
    }

    void setSelectedModel(final MeshModel model) {
        mSelectedModel.postValue(model);
    }

    void setSelectedProvisioner(@NonNull final Provisioner p) {
        mSelectedProvisioner.postValue(p);
    }

    void setSelectedGroup(final int address) {
        final Group group = mMeshNetwork.getGroup(address);
        if (group != null) mSelectedGroupLiveData.postValue(group);
    }

    // =========================================================================
    // ScannerActivity reconnect helpers
    // =========================================================================

    public ProvisionedMeshNode getLastProvisionedNode() {
        return mProvisionedMeshNode;
    }

    public void markSetupRequired(int nodeUnicastAddress) {
        if (mMeshNetwork == null) {
            Log.e(TAG, "markSetupRequired: mMeshNetwork is null — abort");
            return;
        }

        final ProvisionedMeshNode node = mMeshNetwork.getNode(nodeUnicastAddress);
        if (node == null) {
            Log.e(TAG, "markSetupRequired: node not found for address 0x"
                    + Integer.toHexString(nodeUnicastAddress)
                    + " — trying mProvisionedMeshNode fallback");
            if (mProvisionedMeshNode != null
                    && mProvisionedMeshNode.getUnicastAddress() == nodeUnicastAddress) {
                Log.d(TAG, "markSetupRequired: fallback node found");
            } else {
                Log.e(TAG, "markSetupRequired: fallback also failed — abort");
                return;
            }
        } else {
            mProvisionedMeshNode = node;
            mProvisionedMeshNodeLiveData.postValue(node);
        }

        mSetupProvisionedNode = true;
        mIsCompositionDataReceived = false;
        mIsDefaultTtlReceived = false;
        mIsAppKeyAddCompleted = false;
        mIsNetworkRetransmitSetCompleted = false;

        Log.d(TAG, "markSetupRequired ✅"
                + " node=0x" + Integer.toHexString(nodeUnicastAddress)
                + " mSetupProvisionedNode=true");
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
        mIsProvisioningComplete = false;
        mIsCompositionDataReceived = false;
        mIsDefaultTtlReceived = false;
        mIsAppKeyAddCompleted = false;
        mIsNetworkRetransmitSetCompleted = false;
        final LogSession logSession = Logger.newSession(context, null,
                device.getAddress(), device.getName());
        mBleMeshManager.setLogger(logSession);
        initIsConnectedLiveData(connectToNetwork);
        mConnectionState.postValue("Connecting....");
        Log.d(TAG, "Connect issued");
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
        mIsReconnectingFlag = false;
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

    private void clearExtendedMeshNode() {
        mExtendedMeshNode.postValue(null);
    }

    // =========================================================================
    // BleMeshManagerCallbacks
    // =========================================================================
    @Override
    public void onDataReceived(final BluetoothDevice bluetoothDevice,
                               final int mtu, final byte[] pdu) {
        mMeshManagerApi.handleNotifications(mtu, pdu);
    }

    @Override
    public void onDataSent(final BluetoothDevice device,
                           final int mtu, final byte[] pdu) {
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
        Log.v(TAG, "Disconnecting...");
        mConnectionState.postValue(mIsReconnectingFlag ? "Reconnecting..." : "Disconnecting...");
    }

    @Override
    public void onDeviceDisconnected(@NonNull final BluetoothDevice device) {
        Log.v(TAG, "Disconnected");
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
        mIsBindingInProgress = false;
    }

    @Override
    public void onLinkLossOccurred(@NonNull final BluetoothDevice device) {
        Log.v(TAG, "Link loss occurred");
        mIsConnected.postValue(false);
    }

    @Override
    public void onServicesDiscovered(@NonNull final BluetoothDevice device,
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
                            Log.d(TAG, "onDeviceReady → ConfigCompositionDataGet"
                                    + " node=0x" + Integer.toHexString(node.getUnicastAddress()));
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

    @Override
    public void onBondingRequired(@NonNull final BluetoothDevice device) {
    }

    @Override
    public void onBonded(@NonNull final BluetoothDevice device) {
    }

    @Override
    public void onBondingFailed(@NonNull final BluetoothDevice device) {
    }

    @Override
    public void onError(final BluetoothDevice device,
                        @NonNull final String message, final int errorCode) {
        Log.e(TAG, message + " (code: " + errorCode + "), device: " + device.getAddress());
        mConnectionState.postValue(message);
    }

    @Override
    public void onDeviceNotSupported(@NonNull final BluetoothDevice device) {
    }

    // =========================================================================
    // MeshManagerCallbacks
    // =========================================================================
    @Override
    public void onNetworkLoaded(final MeshNetwork meshNetwork) {
        loadNetwork(meshNetwork);
    }

    @Override
    public void onNetworkUpdated(final MeshNetwork meshNetwork) {
        loadNetwork(meshNetwork);
        updateSelectedGroup();
    }

    @Override
    public void onNetworkLoadFailed(final String error) {
        mNetworkImportState.postValue(error);
    }

    @Override
    public void onNetworkImported(final MeshNetwork meshNetwork) {
        loadNetwork(meshNetwork);
        mNetworkImportState.postValue(meshNetwork.getMeshName()
                + " has been successfully imported.\n"
                + "In order to start sending messages to this network, please change the "
                + "provisioner address. Using the same provisioner address will cause messages "
                + "to be discarded due to the usage of incorrect sequence numbers for this "
                + "address. However if the network does not contain any nodes you do not need "
                + "to change the address");
    }

    @Override
    public void onNetworkImportFailed(final String error) {
        mNetworkImportState.postValue(error);
    }

    @Override
    public void sendProvisioningPdu(final UnprovisionedMeshNode meshNode,
                                    final byte[] pdu) {
        mBleMeshManager.sendPdu(pdu);
    }

    @Override
    public void onMeshPduCreated(final byte[] pdu) {
        mBleMeshManager.sendPdu(pdu);
    }

    @Override
    public int getMtu() {
        return mBleMeshManager.getMaximumPacketSize();
    }

    // =========================================================================
    // MeshProvisioningStatusCallbacks
    // =========================================================================
    @Override
    public void onProvisioningStateChanged(final UnprovisionedMeshNode meshNode,
                                           final ProvisioningState.States state,
                                           final byte[] data) {
        mUnprovisionedMeshNode = meshNode;
        mUnprovisionedMeshNodeLiveData.postValue(meshNode);
        switch (state) {
            case PROVISIONING_INVITE:
                mProvisioningStateLiveData = new ProvisioningStatusLiveData();
                break;
            case PROVISIONING_FAILED:
                mIsProvisioningComplete = false;
                break;
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
        mProvisionedMeshNode = node;
        mIsReconnecting.postValue(true);
        mBleMeshManager.disconnect().enqueue();
        loadNodes();
        mHandler.post(() -> mConnectionState.postValue("Scanning for provisioned node"));
        mHandler.postDelayed(mReconnectRunnable, 1000);
    }

    private void loadNodes() {
        final List<ProvisionedMeshNode> nodes = new ArrayList<>();
        for (final ProvisionedMeshNode node : mMeshNetwork.getNodes()) {
            if (!node.getUuid().equalsIgnoreCase(
                    mMeshNetwork.getSelectedProvisioner().getProvisionerUuid())) {
                nodes.add(node);
            }
        }
        mProvisionedNodes.postValue(nodes);
    }

    // =========================================================================
    // MeshStatusCallbacks
    // =========================================================================
    @Override
    public void onTransactionFailed(final int dst,
                                    final boolean hasIncompleteTimerExpired) {
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

    @Override
    public void onHeartbeatMessageReceived(int src,
                                           @NonNull ControlMessage message) {
    }

    @Override
    public void onMeshMessageProcessed(final int dst,
                                       @NonNull final MeshMessage meshMessage) {
        final ProvisionedMeshNode node = mMeshNetwork.getNode(dst);
        if (node != null) {
            mProvisionedMeshNode = node;
            if (meshMessage instanceof ConfigCompositionDataGet) {
                if (mSetupProvisionedNode) {
                    mProvisionedMeshNodeLiveData.postValue(node);
                    mProvisioningStateLiveData.onMeshNodeStateUpdated(
                            ProvisionerStates.COMPOSITION_DATA_GET_SENT);
                }
            } else if (meshMessage instanceof ConfigDefaultTtlGet) {
                if (mSetupProvisionedNode) {
                    mProvisionedMeshNodeLiveData.postValue(node);
                    mProvisioningStateLiveData.onMeshNodeStateUpdated(
                            ProvisionerStates.SENDING_DEFAULT_TTL_GET);
                }
            } else if (meshMessage instanceof ConfigAppKeyAdd) {
                if (mSetupProvisionedNode) {
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

            // ── Proxy Filter Status ───────────────────────────────────────────
            if (meshMessage.getOpCode() == ProxyConfigMessageOpCodes.FILTER_STATUS) {
                mProvisionedMeshNode = node;
                setSelectedMeshNode(node);
                final ProxyConfigFilterStatus status = (ProxyConfigFilterStatus) meshMessage;
                Log.v(TAG, "Proxy config src: "
                        + MeshAddress.formatAddress(status.getSrc(), false));
                mConnectedProxyAddress.postValue(status.getSrc());
                mMeshMessageLiveData.postValue(status);

                // ── Composition Data Status ───────────────────────────────────────
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

                // ── Default TTL Status ────────────────────────────────────────────
            } else if (meshMessage.getOpCode() == CONFIG_DEFAULT_TTL_STATUS) {
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
                                final NetworkKey networkKey =
                                        mMeshNetwork.getNetKeys().get(appKey.getBoundNetKeyIndex());
                                mMeshManagerApi.createMeshPdu(node.getUnicastAddress(),
                                        new ConfigAppKeyAdd(networkKey, appKey));
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

                // ── Network Transmit Status ───────────────────────────────────────
            } else if (meshMessage.getOpCode() == CONFIG_NETWORK_TRANSMIT_STATUS) {
                final ConfigNetworkTransmitStatus status = (ConfigNetworkTransmitStatus) meshMessage;
                updateNode(node);
                mMeshMessageLiveData.postValue(status);

                // ── CONFIG_APPKEY_STATUS — AUTO BIND TRIGGER ──────────────────────
            } else if (meshMessage.getOpCode() == CONFIG_APPKEY_STATUS) {
                final ConfigAppKeyStatus status = (ConfigAppKeyStatus) meshMessage;

                Log.d(TAG_BIND, "CONFIG_APPKEY_STATUS"
                        + " | success=" + status.isSuccessful()
                        + " | setupNode=" + mSetupProvisionedNode
                        + " | node=0x" + Integer.toHexString(node.getUnicastAddress()));

                if (status.isSuccessful()) {
                    mIsAppKeyAddCompleted = true;
                    mSetupProvisionedNode = false;
                    mProvisionedMeshNodeLiveData.postValue(node);

                    if (mProvisioningStateLiveData != null) {
                        mProvisioningStateLiveData.onMeshNodeStateUpdated(
                                ProvisionerStates.APP_KEY_STATUS_RECEIVED);
                    }

                    Log.d(TAG_BIND, "✅ AppKey SUCCESS → startAutoAppKeyBind"
                            + " node=0x" + Integer.toHexString(node.getUnicastAddress()));
                    startAutoAppKeyBind(node);

                } else {
                    mSetupProvisionedNode = false;
                    if (mProvisioningStateLiveData != null) {
                        mProvisioningStateLiveData.onMeshNodeStateUpdated(
                                ProvisionerStates.APP_KEY_STATUS_RECEIVED);
                    }
                    Log.w(TAG_BIND, "⚠️ CONFIG_APPKEY_STATUS FAILED"
                            + " statusCode=" + status.getStatusCode());
                    updateNode(node);
                    mMeshMessageLiveData.postValue(status);
                }

                // ── CONFIG_MODEL_APP_STATUS — AUTO BIND CHAIN ─────────────────────
            } else if (meshMessage.getOpCode() == CONFIG_MODEL_APP_STATUS) {
                final ConfigModelAppStatus status = (ConfigModelAppStatus) meshMessage;
                updateNode(node);

                if (node.getElements().containsKey(status.getElementAddress())) {
                    final Element element = node.getElements().get(status.getElementAddress());
                    if (element != null) {
                        mSelectedElement.postValue(element);
                        final MeshModel model =
                                element.getMeshModels().get(status.getModelIdentifier());
                        mSelectedModel.postValue(model);
                    }
                }

                Log.d(TAG_BIND, "CONFIG_MODEL_APP_STATUS"
                        + " | success=" + status.isSuccessful()
                        + " | element=0x" + String.format("%04X", status.getElementAddress())
                        + " | model=0x" + String.format("%04X", status.getModelIdentifier()));

                if (mAutoBindNode != null && mAutoBindNode.getUnicastAddress() == node.getUnicastAddress()) {
                    mIsBindingInProgress = false;

                    if (status.isSuccessful()) {
                        mAutoBindIndex++;
                        sendNextAutoBind();
                    } else {
                        Log.w(TAG_BIND, "⚠️ Bind failed for model 0x"
                                + String.format("%04X", status.getModelIdentifier())
                                + " on element 0x" + String.format("%04X", status.getElementAddress()));
                        mAutoBindIndex++;
                        sendNextAutoBind();
                    }
                }

                // ── Model Publication Status ──────────────────────────────────────
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
                }

                // ── Model Subscription Status ─────────────────────────────────────
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

                // ── Node Reset Status ─────────────────────────────────────────────
            } else if (meshMessage.getOpCode() == CONFIG_NODE_RESET_STATUS) {
                mBleMeshManager.setClearCacheRequired();
                mExtendedMeshNode.postValue(null);
                loadNodes();
                mMeshMessageLiveData.postValue(meshMessage);

                // ── Relay Status ──────────────────────────────────────────────────
            } else if (meshMessage.getOpCode() == CONFIG_RELAY_STATUS) {
                if (updateNode(node)) {
                    mMeshMessageLiveData.postValue(meshMessage);
                }

                // ── Heartbeat Publication Status ──────────────────────────────────
            } else if (meshMessage.getOpCode() == CONFIG_HEARTBEAT_PUBLICATION_STATUS) {
                if (updateNode(node)) {
                    final Element element = node.getElements().get(meshMessage.getSrc());
                    if (element != null) {
                        mSelectedModel.postValue(element.getMeshModels()
                                .get((int) SigModelParser.CONFIGURATION_SERVER));
                    }
                    mMeshMessageLiveData.postValue(meshMessage);
                }

                // ── Heartbeat Subscription Status ─────────────────────────────────
            } else if (meshMessage.getOpCode() == CONFIG_HEARTBEAT_SUBSCRIPTION_STATUS) {
                if (updateNode(node)) {
                    final Element element = node.getElements().get(meshMessage.getSrc());
                    if (element != null) {
                        mSelectedModel.postValue(element.getMeshModels()
                                .get((int) SigModelParser.CONFIGURATION_SERVER));
                    }
                    mMeshMessageLiveData.postValue(meshMessage);
                }

                // ── GATT Proxy Status ─────────────────────────────────────────────
            } else if (meshMessage.getOpCode() == CONFIG_GATT_PROXY_STATUS) {
                if (updateNode(node)) {
                    mMeshMessageLiveData.postValue(meshMessage);
                }

                // ── Generic OnOff Status ──────────────────────────────────────────
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

                // ── Generic Level Status ──────────────────────────────────────────
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

                // ── Scene Status ──────────────────────────────────────────────────
            } else if (meshMessage.getOpCode() == SCENE_STATUS) {
                if (updateNode(node)) {
                    final SceneStatus status = (SceneStatus) meshMessage;
                    if (node.getElements().containsKey(status.getSrcAddress())) {
                        mSelectedElement.postValue(
                                node.getElements().get(status.getSrcAddress()));
                    }
                }

                // ── Scene Register Status ─────────────────────────────────────────
            } else if (meshMessage.getOpCode() == SCENE_REGISTER_STATUS) {
                if (updateNode(node)) {
                    final SceneRegisterStatus status = (SceneRegisterStatus) meshMessage;
                    if (node.getElements().containsKey(status.getSrcAddress())) {
                        mSelectedElement.postValue(
                                node.getElements().get(status.getSrcAddress()));
                    }
                }

                // ── Vendor Model Status ───────────────────────────────────────────
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

        if (mMeshMessageLiveData.hasActiveObservers()) {
            mMeshMessageLiveData.postValue(meshMessage);
        }

        if (mMeshManagerApi.getMeshNetwork() != null) {
            mMeshNetworkLiveData.refresh(mMeshManagerApi.getMeshNetwork());
        }
    }

    @Override
    public void onMessageDecryptionFailed(final String meshLayer,
                                          final String errorMessage) {
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
            if (node != null) {
                mExtendedMeshNode.postValue(mMeshNetwork.getNode(node.getUuid()));
            }
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
                .setServiceUuid(new ParcelUuid(MESH_PROXY_UUID)).build());

        BluetoothLeScannerCompat.getScanner().startScan(filters, settings, scanCallback);
        Log.v(TAG, "Scan started");
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
        mProvisionedMeshNode = node;
        mIsReconnectingFlag = true;
        mHandler.postDelayed(() -> connectToProxy(device), 2000);
    }

    // =========================================================================
    // AUTO APP KEY BIND - COMPLETE WORKING VERSION
    // =========================================================================

    /**
     * Called after CONFIG_APPKEY_STATUS success.
     * Properly identifies both Server and Client nodes and queues models for binding.
     */
    private void startAutoAppKeyBind(@NonNull final ProvisionedMeshNode node) {
        final List<ApplicationKey> appKeys = mMeshNetworkLiveData.getAppKeys();
        if (appKeys == null || appKeys.isEmpty()) {
            Log.w(TAG_BIND, "startAutoAppKeyBind: No AppKey in network — skip.");
            return;
        }

        final int appKeyIndex = appKeys.get(0).getKeyIndex();

        mPendingBindOperations.clear();
        mAutoBindIndex = 0;
        mAutoBindNode = node;
        mIsBindingInProgress = false;

        Log.d(TAG_BIND, "╔══════════════════════════════════════════════════");
        Log.d(TAG_BIND, "║ START AUTO BIND for node: " + node.getNodeName()
                + " (0x" + String.format("%04X", node.getUnicastAddress()) + ")");
        Log.d(TAG_BIND, "╠══════════════════════════════════════════════════");

        boolean isServerNode = false;
        boolean isClientNode = false;
        int totalBindableModels = 0;
        int elementIndex = 0;

        // First pass: Determine node type and collect all bindable models
        for (Element element : node.getElements().values()) {
            final int elementAddress = element.getElementAddress();

            Log.d(TAG_BIND, "║  Element[" + elementIndex + "] address=0x"
                    + String.format("%04X", elementAddress));

            int modelIndex = 0;
            for (MeshModel model : element.getMeshModels().values()) {
                final int modelId = model.getModelId();

                // Check node type based on models
                if (modelId == 0x1000) { // Generic OnOff Server
                    isServerNode = true;
                    Log.d(TAG_BIND, "║    ✓ Found SERVER model (0x1000)");
                } else if (modelId == 0x1001) { // Generic OnOff Client
                    isClientNode = true;
                    Log.d(TAG_BIND, "║    ✓ Found CLIENT model (0x1001)");
                }

                // Skip Configuration models and Health models
                // 0x0000: Configuration Server
                // 0x0001: Health Server
                // 0x0002: Configuration Client
                // 0x0003: Health Client
                if (modelId == 0x0000 || modelId == 0x0001 ||
                        modelId == 0x0002 || modelId == 0x0003) {
                    Log.d(TAG_BIND, "║      Model[" + modelIndex + "] 0x"
                            + String.format("%04X", modelId)
                            + " → SKIP (config/health model)");
                    modelIndex++;
                    continue;
                }

                // Queue this model for binding
                mPendingBindOperations.add(new int[]{
                        elementAddress,
                        modelId,
                        appKeyIndex
                });
                totalBindableModels++;

                Log.d(TAG_BIND, "║      Model[" + modelIndex + "] 0x"
                        + String.format("%04X", modelId)
                        + " → QUEUED for bind");
                modelIndex++;
            }
            elementIndex++;
        }

        Log.d(TAG_BIND, "╠══════════════════════════════════════════════════");
        Log.d(TAG_BIND, "║ Node Type: " + (isServerNode ? "SERVER" : "")
                + (isClientNode ? (isServerNode ? " + " : "") + "CLIENT" : "")
                + (isServerNode ? " (will save address)" : ""));
        Log.d(TAG_BIND, "║ Total models to bind: " + totalBindableModels);
        Log.d(TAG_BIND, "╚══════════════════════════════════════════════════");

        // Save server unicast address if this is a SERVER node
        if (isServerNode) {
            final String nodeName = node.getNodeName();
            if (nodeName != null && !nodeName.isEmpty()) {
                int primaryAddress = node.getUnicastAddress();
                ClientElementStore.saveServerUnicastAddress(
                        nodeName.trim().toLowerCase(),
                        primaryAddress);
                Log.d(TAG_BIND, "✅ Server address saved: name=" + nodeName
                        + " unicast=0x" + String.format("%04X", primaryAddress));
            } else {
                Log.w(TAG_BIND, "⚠️ Server node has no name — address not cached");
            }
        }

        if (mPendingBindOperations.isEmpty()) {
            Log.w(TAG_BIND, "startAutoAppKeyBind: No bindable models found.");
            mAutoBindNode = null;

            // Still need to save client element addresses if this is a client node
            if (isClientNode) {
                saveClientElementAddresses(node);
            }
            return;
        }

        // Start the binding sequence
        mHandler.postDelayed(() -> sendNextAutoBind(), 500);
    }

    /**
     * Saves client element addresses for a client node.
     * Called after all models are bound OR if no models needed binding.
     */
    private void saveClientElementAddresses(@NonNull final ProvisionedMeshNode node) {
        final String deviceId = normalizeId(node.getNodeName());
        if (deviceId == null || deviceId.isEmpty()) {
            Log.w(TAG_BIND, "saveClientElementAddresses: node has no name — skipping");
            return;
        }

        final Map<Integer, Integer> addressMap = new HashMap<>();
        final int primaryAddress = node.getUnicastAddress();
        int elementPosition = 0;
        int savedCount = 0;

        Log.d(TAG_BIND, "╔══════════════════════════════════════════════════");
        Log.d(TAG_BIND, "║ SAVING CLIENT ADDRESSES for device: " + deviceId);
        Log.d(TAG_BIND, "║ Primary address: 0x" + String.format("%04X", primaryAddress));
        Log.d(TAG_BIND, "╠══════════════════════════════════════════════════");

        for (Element element : node.getElements().values()) {
            final int elementAddress = element.getElementAddress();

            // Skip the primary element (it's not a client-controlled element)
            if (elementAddress == primaryAddress) {
                Log.d(TAG_BIND, "║  Element 0x" + String.format("%04X", elementAddress)
                        + " (primary) → SKIP");
                continue;
            }

            // Check if this element has a Generic OnOff Client model
            boolean hasClientModel = false;
            for (MeshModel model : element.getMeshModels().values()) {
                if (model.getModelId() == 0x1001) { // Generic OnOff Client
                    hasClientModel = true;
                    break;
                }
            }

            if (hasClientModel) {
                addressMap.put(elementPosition, elementAddress);
                Log.d(TAG_BIND, "║  Element[" + elementPosition + "] 0x"
                        + String.format("%04X", elementAddress) + " → SAVED");
                savedCount++;
            } else {
                Log.d(TAG_BIND, "║  Element[" + elementPosition + "] 0x"
                        + String.format("%04X", elementAddress) + " → SKIP (no client model)");
            }
            elementPosition++;
        }

        Log.d(TAG_BIND, "╠══════════════════════════════════════════════════");
        Log.d(TAG_BIND, "║ Total saved: " + savedCount + " client elements");
        Log.d(TAG_BIND, "╚══════════════════════════════════════════════════");

        if (!addressMap.isEmpty()) {
            ClientElementStore.saveAll(deviceId, addressMap);
            Log.d(TAG_BIND, "✅ Saved " + savedCount
                    + " client element addresses for device=" + deviceId);
        } else {
            Log.w(TAG_BIND, "⚠️ No client elements found to save for device=" + deviceId);
        }
    }

    /**
     * Sends the next pending bind operation.
     */
    private void sendNextAutoBind() {
        if (mAutoBindNode == null) {
            Log.w(TAG_BIND, "sendNextAutoBind: node null — stop.");
            return;
        }

        if (mIsBindingInProgress) {
            Log.d(TAG_BIND, "sendNextAutoBind: binding already in progress, waiting...");
            return;
        }

        if (mAutoBindIndex >= mPendingBindOperations.size()) {
            Log.d(TAG_BIND, "╔══════════════════════════════════════════════════");
            Log.d(TAG_BIND, "║ ✅ ALL MODELS BOUND for node 0x"
                    + String.format("%04X", mAutoBindNode.getUnicastAddress()));
            Log.d(TAG_BIND, "╚══════════════════════════════════════════════════");

            // Check if this is a client node and save its addresses
            boolean isClientNode = false;
            for (Element element : mAutoBindNode.getElements().values()) {
                for (MeshModel model : element.getMeshModels().values()) {
                    if (model.getModelId() == 0x1001) {
                        isClientNode = true;
                        break;
                    }
                }
                if (isClientNode) break;
            }

            if (isClientNode) {
                saveClientElementAddresses(mAutoBindNode);
            }

            // Clean up
            mAutoBindNode = null;
            mPendingBindOperations.clear();
            mAutoBindIndex = 0;
            mIsBindingInProgress = false;
            return;
        }

        final int[] op = mPendingBindOperations.get(mAutoBindIndex);
        final int elementAddr = op[0];
        final int modelId = op[1];
        final int appKeyIndex = op[2];

        try {
            mMeshManagerApi.createMeshPdu(
                    mAutoBindNode.getUnicastAddress(),
                    new ConfigModelAppBind(elementAddr, modelId, appKeyIndex));

            mIsBindingInProgress = true;

            Log.d(TAG_BIND, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            Log.d(TAG_BIND, "SEND [" + (mAutoBindIndex + 1) + "/" + mPendingBindOperations.size() + "]"
                    + " Element=0x" + String.format("%04X", elementAddr)
                    + " Model=0x" + String.format("%04X", modelId));
            Log.d(TAG_BIND, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

        } catch (Exception e) {
            Log.e(TAG_BIND, "SEND FAILED for Element=0x" + String.format("%04X", elementAddr)
                    + " Model=0x" + String.format("%04X", modelId) + " : " + e.getMessage());

            // Move to next operation on failure
            mAutoBindIndex++;
            mHandler.postDelayed(() -> sendNextAutoBind(), 300);
        }
    }

    private String normalizeId(String id) {
        return id == null ? null : id.trim().toLowerCase();
    }
}