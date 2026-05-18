package no.nordicsemi.android.swarorgbww.viewmodels;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;
import dagger.hilt.android.qualifiers.ApplicationContext;

import no.nordicsemi.android.swarorgbww.ApplicationKey;
import no.nordicsemi.android.swarorgbww.MeshNetwork;
import no.nordicsemi.android.swarorgbww.NodeKey;
import no.nordicsemi.android.swarorgbww.adapter.ExtendedBluetoothDevice;
import no.nordicsemi.android.swarorgbww.ble.adapter.DevicesAdapter;
import no.nordicsemi.android.swarorgbww.swajaui.AutoPublicationHelper;
import no.nordicsemi.android.swarorgbww.transport.ConfigModelPublicationStatus;
import no.nordicsemi.android.swarorgbww.transport.Element;
import no.nordicsemi.android.swarorgbww.transport.MeshMessage;
import no.nordicsemi.android.swarorgbww.transport.ProvisionedMeshNode;
import no.nordicsemi.android.swarorgbww.utils.NetworkExportUtils;

@HiltViewModel
public class SharedViewModel extends BaseViewModel
        implements NetworkExportUtils.NetworkExportCallbacks {

    private static final String TAG = "SharedViewModel";
    private static final String PREFS_NAME              = "mesh_prefs";
    private static final String KEY_PROXY_ENABLED       = "proxy_enabled";
    private static final String KEY_SELECTED_DEVICE     = "selected_device";
    private static final String KEY_SIGNAL_THRESHOLD    = "signal_threshold";
    private static final String KEY_SVG_URI             = "svg_uri";
    private static final String DEFAULT_SELECTED_DEVICE = "All Device";

    // Publication model IDs
    private static final int GENERIC_ONOFF_CLIENT = 0x1001;
    private static final int GENERIC_ONOFF_SERVER = 0x1000;

    // ── Publication retry constants ────────────────────────────────────────
    private static final int  PUB_MAX_RETRIES = 3;
    private static final long PUB_TIMEOUT_MS  = 8_000L; // 8 seconds per attempt

    private final SharedPreferences prefs;
    private final Context mContext;

    // ── Repositories ───────────────────────────────────────────────────────
    private final ScannerRepository      mScannerRepository;
    private final SingleLiveEvent<String> networkExportState = new SingleLiveEvent<>();

    // ── LiveData ───────────────────────────────────────────────────────────
    private final MutableLiveData<Boolean>     proxyEnabled         = new MutableLiveData<>();
    private final MutableLiveData<String>      selectedDevice       = new MutableLiveData<>(DEFAULT_SELECTED_DEVICE);
    private final MutableLiveData<Integer>     signalThreshold      = new MutableLiveData<>(DevicesAdapter.SIGNAL_DEFAULT);
    private final MutableLiveData<Uri>         svgUri               = new MutableLiveData<>();
    private final MutableLiveData<Set<String>> provisionedDeviceIds = new MutableLiveData<>(new HashSet<>());
    private final MutableLiveData<String>      focusAreaId          = new MutableLiveData<>();
    private final MutableLiveData<String>      mSelectedSvgDeviceId = new MutableLiveData<>();
    private final MutableLiveData<String>      mServerSvgDeviceId   = new MutableLiveData<>();

    private final MutableLiveData<List<ExtendedBluetoothDevice>> filteredDevices =
            new MutableLiveData<>(new ArrayList<>());
    private final MutableLiveData<List<ExtendedBluetoothDevice>> allUnprovisionedDevices =
            new MutableLiveData<>(new ArrayList<>());

    // ── In-memory UUID → SVG ID map ────────────────────────────────────────
    private final Map<String, String> nodeToSvgMap = new HashMap<>();

    // ── Publication retry tracking ─────────────────────────────────────────
    // Key = "elemAddr_publishAddr"  e.g. "3_5"
    private final Map<String, PublicationAttempt> mPendingPublications = new HashMap<>();

    // ── Last provisioned node ──────────────────────────────────────────────
    private ProvisionedMeshNode lastProvisionedNode;

    // =========================================================================
    // Constructor
    // =========================================================================

    @Inject
    SharedViewModel(
            @NonNull final NrfMeshRepository nrfMeshRepository,
            @NonNull final ScannerRepository scannerRepository,
            @ApplicationContext @NonNull final Context context
    ) {
        super(nrfMeshRepository);

        mContext = context;
        ClientServerElementStore.init(context);

        mScannerRepository = scannerRepository;
        scannerRepository.registerBroadcastReceivers();

        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        // ── Restore simple prefs ───────────────────────────────────────────
        proxyEnabled.setValue(prefs.getBoolean(KEY_PROXY_ENABLED, true));
        selectedDevice.setValue(prefs.getString(KEY_SELECTED_DEVICE, DEFAULT_SELECTED_DEVICE));
        signalThreshold.setValue(prefs.getInt(KEY_SIGNAL_THRESHOLD, DevicesAdapter.SIGNAL_DEFAULT));

        final String savedSvgUri = prefs.getString(KEY_SVG_URI, null);
        if (savedSvgUri != null) svgUri.setValue(Uri.parse(savedSvgUri));

        // ── Load provisioned set from Store ────────────────────────────────
        syncFromStore();

        // ── On node list change → re-sync ─────────────────────────────────
        getNodes().observeForever(nodes -> syncFromStore());

        // ── After network import → re-sync ────────────────────────────────
        mNrfMeshRepository.setOnNetworkImportedCallback(this::onNetworkImported);

        // ── Auto-setup complete → publication ─────────────────────────────
        mNrfMeshRepository.setAutoSetupCompleteListener(this::onAutoSetupComplete);

        // ── Observe publication status for retry logic ─────────────────────
        observePublicationStatus();
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        if (!mNrfMeshRepository.getBleMeshManager().isConnected()) {
            mNrfMeshRepository.disconnect();
        }
        mScannerRepository.unregisterBroadcastReceivers();
        // Cancel all pending publication timeouts
        cancelAllPublicationTimeouts();
    }

    public void syncFromStore() {
        Set<String> keys = ClientServerElementStore.getProvisionedKeys();
        provisionedDeviceIds.setValue(new HashSet<>(keys));
        Log.d(TAG, "✅ syncFromStore: " + keys.size() + " devices → " + keys);
    }

    public void startHardcodedProxyConnect() {
        Log.i(TAG, "⚡ startHardcodedProxyConnect triggered");
        mNrfMeshRepository.connectHardcodedProxy();
    }

    // ── Called after mesh network import ──────────────────────────────────
    private void onNetworkImported() {
        List<ProvisionedMeshNode> nodes = getAllProvisionedNodes();
        if (nodes == null || nodes.isEmpty()) {
            Log.w(TAG, "onNetworkImported: no nodes");
            return;
        }

        // ── UUID → SVG map rebuild ─────────────────────────────────────────
        for (ProvisionedMeshNode node : nodes) {
            String svgId = prefs.getString("node_svg_" + node.getUuid(), null);
            if (svgId == null) {
                // Fallback: try to find by unicast address in store
                svgId = ClientServerElementStore
                        .getKeyByUnicastAddress(node.getUnicastAddress());
                if (svgId != null) {
                    // Re-persist the mapping so next time it's found directly
                    prefs.edit()
                            .putString("node_svg_" + node.getUuid(), svgId)
                            .apply();
                }
            }
            if (svgId != null) {
                nodeToSvgMap.put(node.getUuid(), svgId);
            }
        }

        // ── Sync provisioned set from store (already restored by deserializer) ─
        syncFromStore();

        new Handler(Looper.getMainLooper())
                .postDelayed(this::forceSvgRefresh, 1000);

        Log.d(TAG, "✅ onNetworkImported: rebuilt "
                + nodeToSvgMap.size() + " UUID mappings");
    }
    // =========================================================================
    // AUTO PUBLICATION SETUP
    // =========================================================================

    private void onAutoSetupComplete(@NonNull ProvisionedMeshNode node) {
        Log.d(TAG, "🔔 onAutoSetupComplete: node=0x"
                + String.format("%04X", node.getUnicastAddress())
                + " name=" + node.getNodeName());
        new Handler(Looper.getMainLooper())
                .postDelayed(() -> triggerPublicationSetup(node), 500);
    }

    private void triggerPublicationSetup(@NonNull ProvisionedMeshNode completedNode) {
        Log.d(TAG, "📡 triggerPublicationSetup: node=0x"
                + String.format("%04X", completedNode.getUnicastAddress())
                + " name=" + completedNode.getNodeName());

        List<ApplicationKey> appKeys = getNetworkLiveData().getAppKeys();
        if (appKeys == null || appKeys.isEmpty()) {
            Log.e(TAG, "triggerPublicationSetup: no AppKey");
            return;
        }
        int appKeyIndex = appKeys.get(0).getKeyIndex();

        SharedPreferences meshPrefs = mContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        Set<String> provisionedKeys = ClientServerElementStore.getProvisionedKeys();
        if (provisionedKeys.isEmpty()) return;

        boolean anyScheduled = false;
        long delayOffset = 0;

        for (String serverKey : provisionedKeys) {

            // ── 1. Server unicast address ──────────────────────────────────
            int serverAddr = ClientServerElementStore.getServerUnicastAddress(serverKey);
            if (serverAddr == -1) continue;
            if (!isNodeInNetwork(serverAddr)) {
                Log.d(TAG, "  Server 0x" + String.format("%04X", serverAddr) + " not in network — skip");
                continue;
            }

            // ── 2. Verify server node (must have OnOff Server model) ───────
            if (!isServerNode(serverAddr)) {
                Log.w(TAG, "  0x" + String.format("%04X", serverAddr) + " has no OnOff Server model — skip");
                continue;
            }

            // ── 3. Area prefix ─────────────────────────────────────────────
            String areaPrefix = serverKey.contains(":")
                    ? serverKey.split(":")[0].trim().toLowerCase() : "";

            // ── 4. elementId ───────────────────────────────────────────────
            int svgElementId = ClientServerElementStore.getServerSvgElementId(serverKey);
            if (svgElementId == -1) {
                Log.w(TAG, "  svgElementId=-1 for key=" + serverKey + " — skip");
                continue;
            }

            // ── 5. Client name via elementId ───────────────────────────────
            String clientNamePart = findClientNameByElementId(areaPrefix, svgElementId);
            if (clientNamePart == null) {
                Log.d(TAG, "  No client found with elementId=" + svgElementId
                        + " area=" + areaPrefix + " — skip");
                continue;
            }

            // ── 6. C→S address ─────────────────────────────────────────────
            int cToSAddr = ClientServerElementStore.getClientAddress(clientNamePart, svgElementId);
            if (cToSAddr == -1) {
                Log.d(TAG, "  C→S addr not found: clientName=" + clientNamePart);
                continue;
            }
            if (!isNodeInNetwork(cToSAddr)) {
                Log.d(TAG, "  C→S 0x" + String.format("%04X", cToSAddr) + " not in network — skip");
                continue;
            }

            // ── 7. S→C address via receiveId ──────────────────────────────
            String receiveIdStr = ClientServerElementStore.getReceiveId(serverKey);
            int sToCAddr = -1;
            if (receiveIdStr != null && !receiveIdStr.trim().isEmpty()) {
                try {
                    int receiveIndex = Integer.parseInt(receiveIdStr.trim());
                    sToCAddr = ClientServerElementStore.getClientAddress(clientNamePart, receiveIndex);
                    Log.d(TAG, "  receiveId=" + receiveIndex
                            + " → S→C addr=0x" + String.format("%04X", sToCAddr));
                } catch (NumberFormatException e) {
                    Log.w(TAG, "  Invalid receiveId=" + receiveIdStr);
                }
            }
            if (sToCAddr != -1 && !isNodeInNetwork(sToCAddr)) {
                Log.d(TAG, "  S→C 0x" + String.format("%04X", sToCAddr) + " not in network — clearing");
                sToCAddr = -1;
            }

            // ── 8. Already done? ───────────────────────────────────────────
            if (AutoPublicationHelper.isPublicationSetupComplete(meshPrefs, cToSAddr, serverAddr)) {
                Log.d(TAG, "  Already complete: 0x"
                        + String.format("%04X", cToSAddr)
                        + " ↔ 0x" + String.format("%04X", serverAddr));
                continue;
            }

            // ── 9. In-flight guard ─────────────────────────────────────────
            String pairKey = cToSAddr + "_" + serverAddr;
            if (meshPrefs.getBoolean("pub_inflight_" + pairKey, false)) {
                Log.d(TAG, "  Already in-flight: " + pairKey + " — skip");
                continue;
            }
            meshPrefs.edit().putBoolean("pub_inflight_" + pairKey, true).apply();

            // ── 10. Log & schedule ─────────────────────────────────────────
            Log.d(TAG, "  Scheduling pair: " + serverKey);
            Log.d(TAG, "     C→S: 0x" + String.format("%04X", cToSAddr)
                    + " → Server 0x" + String.format("%04X", serverAddr));
            if (sToCAddr != -1)
                Log.d(TAG, "     S→C: Server 0x" + String.format("%04X", serverAddr)
                        + " → 0x" + String.format("%04X", sToCAddr));
            else
                Log.d(TAG, "     S→C: skipped (no receiveId or addr not found)");

            final int fCToSAddr   = cToSAddr;
            final int fSToCAddr   = sToCAddr;
            final int fServerAddr = serverAddr;
            final int fAppKey     = appKeyIndex;
            final String fPairKey = pairKey;

            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                setupPublicationPairDirectional(fCToSAddr, fSToCAddr, fServerAddr, fAppKey);
                new Handler(Looper.getMainLooper()).postDelayed(
                        () -> meshPrefs.edit().remove("pub_inflight_" + fPairKey).apply(),
                        10_000);
            }, delayOffset);

            delayOffset += 4000;
            anyScheduled = true;
        }

        if (anyScheduled)
            Log.d(TAG, "✅ triggerPublicationSetup: pairs scheduled");
        else
            Log.d(TAG, "triggerPublicationSetup: nothing to schedule");
    }

    @Nullable
    private String findClientNameByElementId(String areaPrefix, int svgElementId) {
        SharedPreferences storePrefs = ClientServerElementStore.getPrefsPublic();
        if (storePrefs == null) return null;

        String targetSuffix   = "_" + svgElementId;
        String normalizedArea = areaPrefix.toLowerCase().trim();
        Set<String> provisioned = ClientServerElementStore.getProvisionedKeys();

        for (Map.Entry<String, ?> e : storePrefs.getAll().entrySet()) {
            String k = e.getKey();
            if (!k.startsWith("element_addr_")) continue;
            if (!k.endsWith(targetSuffix)) continue;

            String rest = k.substring("element_addr_".length());
            int lastUnderscore = rest.lastIndexOf("_");
            if (lastUnderscore == -1) continue;
            String storedName = rest.substring(0, lastUnderscore).toLowerCase();

            if (!normalizedArea.isEmpty()) {
                boolean areaMatch = false;
                for (String provKey : provisioned) {
                    String provArea = provKey.contains(":")
                            ? provKey.split(":")[0].trim().toLowerCase() : "";
                    String provName = provKey.contains(":")
                            ? provKey.split(":")[1].trim().toLowerCase()
                            : provKey.toLowerCase();
                    if (provArea.equals(normalizedArea) && provName.equals(storedName)) {
                        areaMatch = true;
                        break;
                    }
                }
                if (!areaMatch) continue;
            }

            Log.d(TAG, "findClientNameByElementId: area=" + areaPrefix
                    + " elementId=" + svgElementId + " → " + storedName);
            return storedName;
        }
        Log.w(TAG, "findClientNameByElementId: not found area=" + areaPrefix
                + " elementId=" + svgElementId);
        return null;
    }

    private boolean isServerNode(int unicastAddr) {
        List<ProvisionedMeshNode> nodes = getAllProvisionedNodes();
        if (nodes == null) return false;
        for (ProvisionedMeshNode node : nodes) {
            for (Element el : node.getElements().values()) {
                if (el.getElementAddress() == unicastAddr) {
                    boolean hasServer = el.getMeshModels().containsKey(0x1000);
                    Log.d(TAG, "isServerNode: 0x" + String.format("%04X", unicastAddr)
                            + " hasOnOffServer=" + hasServer);
                    return hasServer;
                }
            }
        }
        return false;
    }

    @Nullable
    private String findClientNameForArea(String areaPrefix) {
        SharedPreferences storePrefs = ClientServerElementStore.getPrefsPublic();
        if (storePrefs == null) return null;

        String normalizedArea = areaPrefix.toLowerCase().trim();
        Set<String> provisioned = ClientServerElementStore.getProvisionedKeys();

        for (Map.Entry<String, ?> e : storePrefs.getAll().entrySet()) {
            String k = e.getKey();
            if (!k.startsWith("element_addr_")) continue;

            String rest = k.substring("element_addr_".length());
            int lastUnderscore = rest.lastIndexOf("_");
            if (lastUnderscore == -1) continue;
            String storedName = rest.substring(0, lastUnderscore).toLowerCase();

            for (String provKey : provisioned) {
                String provArea = provKey.contains(":")
                        ? provKey.split(":")[0].trim().toLowerCase() : "";
                String provName = provKey.contains(":")
                        ? provKey.split(":")[1].trim().toLowerCase()
                        : provKey.toLowerCase();

                if (provArea.equals(normalizedArea) && provName.equals(storedName)) {
                    Log.d(TAG, "findClientNameForArea: area=" + areaPrefix
                            + " → clientName=" + storedName);
                    return storedName;
                }
            }
        }

        Log.w(TAG, "findClientNameForArea: no client found for area=" + areaPrefix);
        return null;
    }

    // =========================================================================
    // setupPublicationPairDirectional
    // Uses schedulePublicationWithRetry() instead of direct AutoPublicationHelper calls
    // =========================================================================

    private void setupPublicationPairDirectional(
            int cToSAddr, int sToCAddr, int serverAddr, int appKeyIndex) {

        Log.d(TAG, "setupPublicationPairDirectional:"
                + " C→S=0x" + String.format("%04X", cToSAddr)
                + " S→C=0x" + String.format("%04X", sToCAddr)
                + " Server=0x" + String.format("%04X", serverAddr));

        List<ProvisionedMeshNode> nodes = getAllProvisionedNodes();
        if (nodes == null) return;

        ProvisionedMeshNode clientNode = null;
        ProvisionedMeshNode serverNode = null;

        for (ProvisionedMeshNode n : nodes) {
            for (Element el : n.getElements().values()) {
                if (el.getElementAddress() == cToSAddr) clientNode = n;
                if (el.getElementAddress() == serverAddr) serverNode = n;
            }
        }

        if (clientNode == null || serverNode == null) {
            Log.e(TAG, "  Node not found: client="
                    + (clientNode == null ? "NULL" : "OK")
                    + " server=" + (serverNode == null ? "NULL" : "OK"));
            return;
        }

        int cToSElemIdx  = AutoPublicationHelper.getElementIndex(clientNode, cToSAddr);
        int serverElemIdx = AutoPublicationHelper.getElementIndex(serverNode, serverAddr);

        if (cToSElemIdx == -1 || serverElemIdx == -1) {
            Log.e(TAG, "  Element index not found");
            return;
        }

        // ── C→S: client element → server  (with retry) ────────────────────
        schedulePublicationWithRetry(
                clientNode,
                cToSAddr,
                GENERIC_ONOFF_CLIENT,
                serverAddr,
                appKeyIndex,
                "C→S");

        // ── S→C: server → client element  (with retry, 1.5s delay) ───────
        final ProvisionedMeshNode fServerNode  = serverNode;
        final int                 fAppKeyIndex = appKeyIndex;
        final int                 fSToCAddr    = sToCAddr;
        final int                 fServerAddr  = serverAddr;

        if (fSToCAddr != -1 && isNodeInNetwork(fSToCAddr)) {
            new Handler(Looper.getMainLooper()).postDelayed(() ->
                            schedulePublicationWithRetry(
                                    fServerNode,
                                    fServerAddr,
                                    GENERIC_ONOFF_SERVER,
                                    fSToCAddr,
                                    fAppKeyIndex,
                                    "S→C"),
                    1500);
        }
    }

    private void setupPublicationPair(int clientAddr, int serverAddr, int appKeyIndex) {
        Log.d(TAG, "setupPublicationPair: 0x" + String.format("%04X", clientAddr)
                + " ↔ 0x" + String.format("%04X", serverAddr));

        List<ProvisionedMeshNode> nodes = getAllProvisionedNodes();
        if (nodes == null) {
            Log.e(TAG, "setupPublicationPair: node list null");
            return;
        }

        ProvisionedMeshNode clientNode = null;
        ProvisionedMeshNode serverNode = null;

        for (ProvisionedMeshNode n : nodes) {
            for (Element el : n.getElements().values()) {
                if (el.getElementAddress() == clientAddr) clientNode = n;
                if (el.getElementAddress() == serverAddr) serverNode = n;
            }
        }

        if (clientNode == null) {
            Log.e(TAG, "setupPublicationPair: clientNode not found for 0x"
                    + String.format("%04X", clientAddr));
            return;
        }
        if (serverNode == null) {
            Log.e(TAG, "setupPublicationPair: serverNode not found for 0x"
                    + String.format("%04X", serverAddr));
            return;
        }

        int clientElemIndex = AutoPublicationHelper.getElementIndex(clientNode, clientAddr);
        int serverElemIndex = AutoPublicationHelper.getElementIndex(serverNode, serverAddr);

        if (clientElemIndex == -1 || serverElemIndex == -1) {
            Log.e(TAG, "setupPublicationPair: element index not found"
                    + " clientIdx=" + clientElemIndex
                    + " serverIdx=" + serverElemIndex);
            return;
        }

        AutoPublicationHelper.setupBidirectionalPublication(
                this, clientNode, serverNode,
                clientElemIndex, serverElemIndex, appKeyIndex);
    }

    private boolean isNodeInNetwork(int unicastAddr) {
        List<ProvisionedMeshNode> nodes = getAllProvisionedNodes();
        if (nodes == null) return false;
        for (ProvisionedMeshNode n : nodes) {
            if (n.getUnicastAddress() == unicastAddr) return true;
            for (Element el : n.getElements().values()) {
                if (el.getElementAddress() == unicastAddr) return true;
            }
        }
        return false;
    }

    // =========================================================================
    // PUBLICATION RETRY LOGIC
    // =========================================================================

    /**
     * Observes CONFIG_MODEL_PUBLICATION_STATUS messages from the mesh network.
     * On success → clears the pending attempt.
     * On fail    → triggers retry logic.
     */
    private void observePublicationStatus() {
        getMeshMessageLiveData().observeForever(meshMessage -> {
            if (meshMessage == null) return;

            // CONFIG_MODEL_PUBLICATION_STATUS opcode = 0x8019
            if (!(meshMessage instanceof ConfigModelPublicationStatus)) return;

            ConfigModelPublicationStatus status = (ConfigModelPublicationStatus) meshMessage;

            int publishAddr = status.getPublishAddress();
            int elementAddr = status.getElementAddress();
            String key      = elementAddr + "_" + publishAddr;

            PublicationAttempt attempt = mPendingPublications.get(key);
            if (attempt == null) {
                // Not one of our tracked attempts — ignore
                return;
            }

            boolean success = status.isSuccessful(); // status code 0x00 = success

            if (success) {
                Log.d(TAG, "✅ Publication STATUS SUCCESS [" + attempt.label
                        + "] key=" + key + " attempt=" + attempt.attemptCount);
                onPublicationSuccess(key, attempt);
            } else {
                Log.w(TAG, "⚠️ Publication STATUS FAILED [" + attempt.label
                        + "] key=" + key + " statusCode=" + status.getStatusCode());
                onPublicationFailed(key, attempt, "Status code: " + status.getStatusCode());
            }
        });
    }

    /**
     * Sends the publication message and starts an 8-second timeout.
     * If no CONFIG_MODEL_PUBLICATION_STATUS arrives within the timeout,
     * treats it as a failure and triggers retry.
     *
     * @param node           The ProvisionedMeshNode that owns the source element
     * @param elementAddress Source element address (the one being configured to publish)
     * @param modelId        Model ID on that element (OnOff Client or Server)
     * @param publishAddress Destination address to publish to
     * @param appKeyIndex    AppKey index to use
     * @param label          Human-readable label — "C→S" or "S→C" — for logs/toast
     */
    private void schedulePublicationWithRetry(
            @NonNull ProvisionedMeshNode node,
            int elementAddress,
            int modelId,
            int publishAddress,
            int appKeyIndex,
            @NonNull String label) {

        String key = elementAddress + "_" + publishAddress;

        // Reuse existing attempt object on retry path; create new on first call
        PublicationAttempt attempt = mPendingPublications.get(key);
        if (attempt == null) {
            attempt = new PublicationAttempt(node, elementAddress, publishAddress,
                    modelId, appKeyIndex, label);
            mPendingPublications.put(key, attempt);
        }

        attempt.attemptCount++;
        final int currentAttempt = attempt.attemptCount;
        final PublicationAttempt fAttempt = attempt;

        Log.d(TAG, "📡 schedulePublicationWithRetry [" + label + "]"
                + " attempt=" + currentAttempt + "/" + PUB_MAX_RETRIES
                + " elem=0x" + String.format("%04X", elementAddress)
                + " → publish=0x" + String.format("%04X", publishAddress));

        // Show retry toast (not on first attempt)
        if (currentAttempt > 1) {
            new Handler(Looper.getMainLooper()).post(() ->
                    Toast.makeText(
                            mContext,
                            "🔄 Retrying publication [" + label + "]  attempt "
                                    + currentAttempt + " / " + PUB_MAX_RETRIES,
                            Toast.LENGTH_SHORT).show());
        }

        // ── Send the actual publication PDU ────────────────────────────────
        AutoPublicationHelper.setupPublication(
                this, node, elementAddress,
                modelId, publishAddress,
                appKeyIndex, label);

        // ── Cancel any existing timeout for this key ───────────────────────
        if (fAttempt.timeoutRunnable != null) {
            new Handler(Looper.getMainLooper()).removeCallbacks(fAttempt.timeoutRunnable);
        }

        // ── Schedule timeout ───────────────────────────────────────────────
        fAttempt.timeoutRunnable = () -> {
            // Guard: if a newer attempt already started, or attempt was resolved, skip
            PublicationAttempt current = mPendingPublications.get(key);
            if (current == null) return;
            if (current.attemptCount != currentAttempt) return;

            Log.w(TAG, "⏱️ Publication TIMEOUT [" + label + "]"
                    + " attempt=" + currentAttempt
                    + " elem=0x" + String.format("%04X", elementAddress));
            onPublicationFailed(key, current, "Timeout (no STATUS received)");
        };

        new Handler(Looper.getMainLooper())
                .postDelayed(fAttempt.timeoutRunnable, PUB_TIMEOUT_MS);
    }

    /**
     * Called when CONFIG_MODEL_PUBLICATION_STATUS arrives with success (0x00).
     */
    private void onPublicationSuccess(@NonNull String key,
                                      @NonNull PublicationAttempt attempt) {
        // Cancel the timeout — no longer needed
        if (attempt.timeoutRunnable != null) {
            new Handler(Looper.getMainLooper()).removeCallbacks(attempt.timeoutRunnable);
            attempt.timeoutRunnable = null;
        }

        mPendingPublications.remove(key);

        Log.d(TAG, "✅ Publication fully confirmed [" + attempt.label + "] key=" + key
                + " after " + attempt.attemptCount + " attempt(s)");

        // Clean up inflight pref flag if it was set
        SharedPreferences meshPrefs =
                mContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        meshPrefs.edit().remove("pub_inflight_" + key).apply();
    }

    /**
     * Called when publication fails (timeout or bad status code).
     * Retries up to PUB_MAX_RETRIES times with progressive delay, then shows final error toast.
     */
    private void onPublicationFailed(@NonNull String key,
                                     @NonNull PublicationAttempt attempt,
                                     @NonNull String reason) {
        // Cancel current timeout
        if (attempt.timeoutRunnable != null) {
            new Handler(Looper.getMainLooper()).removeCallbacks(attempt.timeoutRunnable);
            attempt.timeoutRunnable = null;
        }

        if (attempt.attemptCount < PUB_MAX_RETRIES) {
            // ── Schedule retry with progressive delay ──────────────────────
            // Attempt 1 failed → retry after 2s
            // Attempt 2 failed → retry after 4s
            long retryDelay = 2_000L * attempt.attemptCount;

            Log.d(TAG, "↩️ Will retry publication [" + attempt.label + "] in "
                    + retryDelay + "ms  reason=" + reason);

            final PublicationAttempt fAttempt = attempt;
            new Handler(Looper.getMainLooper()).postDelayed(() ->
                            schedulePublicationWithRetry(
                                    fAttempt.node,
                                    fAttempt.elementAddress,
                                    fAttempt.publishAddress,
                                    fAttempt.modelId,
                                    fAttempt.appKeyIndex,
                                    fAttempt.label),
                    retryDelay);

        } else {
            // ── All retries exhausted ──────────────────────────────────────
            mPendingPublications.remove(key);

            Log.e(TAG, "❌ Publication PERMANENTLY FAILED [" + attempt.label
                    + "] after " + attempt.attemptCount + " attempts. reason=" + reason
                    + " elem=0x" + String.format("%04X", attempt.elementAddress)
                    + " → publish=0x" + String.format("%04X", attempt.publishAddress));

            new Handler(Looper.getMainLooper()).post(() ->
                    Toast.makeText(
                            mContext,
                            "❌ Publication setup failed [" + attempt.label + "] after "
                                    + PUB_MAX_RETRIES + " attempts.\nPlease check device connection.",
                            Toast.LENGTH_LONG).show());

            // Cleanup inflight flag
            SharedPreferences meshPrefs =
                    mContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            meshPrefs.edit().remove("pub_inflight_" + key).apply();
        }
    }

    /**
     * Cancels all pending publication timeouts (called from onCleared).
     */
    private void cancelAllPublicationTimeouts() {
        Handler h = new Handler(Looper.getMainLooper());
        for (PublicationAttempt attempt : mPendingPublications.values()) {
            if (attempt.timeoutRunnable != null) {
                h.removeCallbacks(attempt.timeoutRunnable);
            }
        }
        mPendingPublications.clear();
        Log.d(TAG, "🧹 cancelAllPublicationTimeouts: cleared all pending publications");
    }

    // =========================================================================
    // PROVISIONED DEVICE IDs
    // =========================================================================

    public LiveData<Set<String>> getProvisionedDeviceIds() {
        return provisionedDeviceIds;
    }

    public boolean isDeviceProvisioned(String svgDeviceId) {
        return ClientServerElementStore.isProvisioned(svgDeviceId);
    }

    public void forceSvgRefresh() {
        syncFromStore();
        Log.d(TAG, "🔄 forceSvgRefresh done");
    }

    public LiveData<MeshMessage> getMeshMessageLiveData() {
        return mNrfMeshRepository.getMeshMessageLiveData();
    }

    public void clearProvisionedDevices() {
        for (String key : ClientServerElementStore.getProvisionedKeys()) {
            ClientServerElementStore.clearDevice(key);
        }
        provisionedDeviceIds.setValue(new HashSet<>());
        Log.d(TAG, "🧹 clearProvisionedDevices done");
    }

    // =========================================================================
    // NODE DELETE
    // =========================================================================

    public void removeNodeFromNetwork(ProvisionedMeshNode node) {
        if (node == null) return;
        List<ProvisionedMeshNode> nodes = getAllProvisionedNodes();
        if (nodes != null) {
            nodes.remove(node);
            Log.d(TAG, "🔥 Node removed: " + node.getNodeName());
        }
    }

    public boolean fullyDeleteNode(@NonNull ProvisionedMeshNode adapterNode) {
        ProvisionedMeshNode realNode = null;
        List<ProvisionedMeshNode> nodes = getAllProvisionedNodes();
        if (nodes != null) {
            for (ProvisionedMeshNode n : nodes) {
                if (n.getUuid().equals(adapterNode.getUuid())) {
                    realNode = n;
                    break;
                }
            }
        }
        if (realNode == null) {
            Log.e(TAG, "❌ fullyDeleteNode: node not found in network");
            return false;
        }

        String svgId = getSvgIdFromNode(realNode);

        if (svgId == null) {
            svgId = ClientServerElementStore.getKeyByUnicastAddress(realNode.getUnicastAddress());
            Log.d(TAG, "fullyDeleteNode: svgId via unicast fallback = " + svgId);
        }

        Log.d(TAG, "fullyDeleteNode: nodeName=" + realNode.getNodeName()
                + " unicast=0x" + String.format("%04X", realNode.getUnicastAddress())
                + " svgId=" + svgId);

        boolean deleted = getNetworkLiveData().getMeshNetwork().deleteNode(realNode);
        if (!deleted) {
            Log.e(TAG, "❌ fullyDeleteNode: mesh delete failed");
            return false;
        }

        if (svgId != null) {
            ClientServerElementStore.clearDevice(svgId);
            Log.d(TAG, "✅ fullyDeleteNode: clearDevice(" + svgId + ")");
        } else {
            Log.w(TAG, "⚠️ fullyDeleteNode: svgId null — scanning provisioned set by unicast");
            Set<String> keys = ClientServerElementStore.getProvisionedKeys();
            for (String key : keys) {
                int addr = ClientServerElementStore.getServerUnicastAddress(key);
                if (addr == realNode.getUnicastAddress()) {
                    ClientServerElementStore.clearDevice(key);
                    Log.d(TAG, "✅ fullyDeleteNode: cleared by unicast scan → " + key);
                    break;
                }
            }
        }

        nodeToSvgMap.remove(realNode.getUuid());
        prefs.edit().remove("node_svg_" + realNode.getUuid()).apply();

        forceSvgRefresh();
        Log.d(TAG, "✅ fullyDeleteNode complete: " + realNode.getNodeName());
        return true;
    }

    // =========================================================================
    // CLIENT PROVISIONING
    // =========================================================================

    public void onClientProvisioned(@NonNull ProvisionedMeshNode clientNode,
                                    @NonNull String svgDeviceId) {
        List<Element> sorted = new ArrayList<>(clientNode.getElements().values());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            sorted.sort((a, b) ->
                    Integer.compare(a.getElementAddress(), b.getElementAddress()));
        }
        Map<Integer, Integer> elementAddresses = new HashMap<>();
        for (int i = 0; i < sorted.size() && i < 40; i++) {
            elementAddresses.put(i, sorted.get(i).getElementAddress());
        }
        ClientServerElementStore.saveAllClientElementAddresses(svgDeviceId, elementAddresses);
        Log.d(TAG, "✅ onClientProvisioned: saved "
                + elementAddresses.size() + " elements for " + svgDeviceId);
    }

    // =========================================================================
    // ELEMENT ID
    // =========================================================================

    @Nullable
    public String getElementId(@NonNull String svgDeviceId) {
        int id = ClientServerElementStore.getServerSvgElementId(svgDeviceId);
        return id != -1 ? String.valueOf(id) : null;
    }

    // =========================================================================
    // NODE ↔ SVG MAPPING
    // =========================================================================

    public void mapNodeToSvg(String nodeUuid, String svgId) {
        if (nodeUuid == null || svgId == null) return;
        nodeToSvgMap.put(nodeUuid, svgId);
        prefs.edit().putString("node_svg_" + nodeUuid, svgId).apply();
        Log.d(TAG, "✅ mapNodeToSvg: " + nodeUuid + " → " + svgId);
    }

    public String getSvgIdFromNode(ProvisionedMeshNode node) {
        if (node == null) return null;
        String svgId = nodeToSvgMap.get(node.getUuid());
        if (svgId == null) {
            svgId = prefs.getString("node_svg_" + node.getUuid(), null);
            if (svgId != null) nodeToSvgMap.put(node.getUuid(), svgId);
        }
        return svgId;
    }

    public void autoMapNodeToCurrentSvg(ProvisionedMeshNode node) {
        if (node == null) return;
        String svgId = getSelectedSvgDeviceId();
        if (svgId == null) {
            Log.w(TAG, "autoMapNodeToCurrentSvg: svgId is null");
            return;
        }
        mapNodeToSvg(node.getUuid(), svgId);
    }

    // =========================================================================
    // FOCUS AREA
    // =========================================================================

    public LiveData<String> getFocusAreaId()   { return focusAreaId; }
    public void setFocusAreaId(String areaId)  { focusAreaId.setValue(areaId); }

    // =========================================================================
    // NETWORK
    // =========================================================================

    public LiveData<String> getNetworkLoadState()   { return mNrfMeshRepository.getNetworkLoadState(); }
    public LiveData<String> getNetworkExportState() { return networkExportState; }
    public void setSelectedGroup(final int address) { mNrfMeshRepository.setSelectedGroup(address); }

    public void exportMeshNetwork(@NonNull final java.io.OutputStream stream) {
        NetworkExportUtils.exportMeshNetwork(getMeshManagerApi(), stream, this);
    }

    public void exportMeshNetwork(@NonNull final Context context) {
        NetworkExportUtils.exportMeshNetwork(
                getMeshManagerApi(),
                mNrfMeshRepository.getExportPath(context),
                getNetworkLiveData().getNetworkName() + ".json",
                this);
    }

    @Override
    public void onNetworkExported() {
        networkExportState.postValue(
                getNetworkLiveData().getMeshNetwork().getMeshName() + " exported successfully.");
    }

    @Override
    public void onNetworkExportFailed(@NonNull final String error) {
        networkExportState.postValue(error);
    }

    // =========================================================================
    // LAST PROVISIONED NODE
    // =========================================================================

    public void setLastProvisionedNode(ProvisionedMeshNode node) {
        this.lastProvisionedNode = node;
    }

    public ProvisionedMeshNode getLastProvisionedNode() {
        return lastProvisionedNode;
    }

    // =========================================================================
    // SVG URI
    // =========================================================================

    public LiveData<Uri> getSvgUri()  { return svgUri; }
    public Uri getSvgUriValue()       { return svgUri.getValue(); }
    public boolean hasSvg()           { return svgUri.getValue() != null; }

    public void setSvgUri(@NonNull Uri uri) {
        svgUri.setValue(uri);
        prefs.edit().putString(KEY_SVG_URI, uri.toString()).apply();
    }

    public void clearSvgUri() {
        svgUri.setValue(null);
        prefs.edit().remove(KEY_SVG_URI).apply();
    }

    // =========================================================================
    // PROXY
    // =========================================================================

    public LiveData<Boolean> getProxyEnabled() { return proxyEnabled; }
    public boolean isProxyEnabled() {
        Boolean v = proxyEnabled.getValue();
        return v != null && v;
    }

    public void setProxyEnabled(boolean enabled) {
        proxyEnabled.setValue(enabled);
        prefs.edit().putBoolean(KEY_PROXY_ENABLED, enabled).apply();
    }

    // =========================================================================
    // DEVICE NAME FILTER
    // =========================================================================

    public void setDeviceNameFilter(String filter) {
        prefs.edit().putString("device_name_filter", filter).apply();
    }

    public String getDeviceNameFilterValue() {
        return prefs.getString("device_name_filter", "");
    }

    // =========================================================================
    // SELECTED DEVICE
    // =========================================================================

    public LiveData<String> getSelectedDevice() { return selectedDevice; }

    public String getSelectedDeviceValue() {
        String v = selectedDevice.getValue();
        return v != null ? v : DEFAULT_SELECTED_DEVICE;
    }

    public boolean isDeviceSelected(String deviceName) {
        return deviceName != null && deviceName.equals(getSelectedDeviceValue());
    }

    public void setSelectedDevice(String device) {
        if (device == null) device = DEFAULT_SELECTED_DEVICE;
        selectedDevice.setValue(device);
        prefs.edit().putString(KEY_SELECTED_DEVICE, device).apply();
    }

    public void clearSelectedDevice() {
        setSelectedDevice(DEFAULT_SELECTED_DEVICE);
    }

    // =========================================================================
    // SIGNAL THRESHOLD
    // =========================================================================

    public LiveData<Integer> getSignalThreshold() { return signalThreshold; }

    public int getSignalThresholdValue() {
        Integer v = signalThreshold.getValue();
        return v != null ? v : DevicesAdapter.SIGNAL_DEFAULT;
    }

    public void setSignalThreshold(int threshold) {
        int sanitized = (threshold == DevicesAdapter.SIGNAL_100)
                ? DevicesAdapter.SIGNAL_100 : DevicesAdapter.SIGNAL_DEFAULT;
        signalThreshold.setValue(sanitized);
        prefs.edit().putInt(KEY_SIGNAL_THRESHOLD, sanitized).apply();
    }

    public void clearSignalThreshold() {
        setSignalThreshold(DevicesAdapter.SIGNAL_DEFAULT);
    }

    // =========================================================================
    // AUTO APP KEY
    // =========================================================================

    @Nullable
    public ApplicationKey getDefaultAppKey() {
        try {
            MeshNetwork network = getNetworkLiveData().getMeshNetwork();
            if (network == null) return null;
            List<ApplicationKey> appKeys = network.getAppKeys();
            if (appKeys == null || appKeys.isEmpty()) return null;
            return appKeys.get(0);
        } catch (Exception e) {
            return null;
        }
    }

    public boolean isDefaultAppKeyBound(@NonNull final ProvisionedMeshNode node) {
        ApplicationKey key = getDefaultAppKey();
        if (key == null) return false;
        for (NodeKey k : node.getAddedAppKeys()) {
            if (k.getIndex() == key.getKeyIndex()) return true;
        }
        return false;
    }

    public boolean isAutoAppKeyDone(int unicastAddress) {
        return prefs.getBoolean("app_key_done_" + unicastAddress, false);
    }

    public void setAutoAppKeyDone(int unicastAddress) {
        prefs.edit().putBoolean("app_key_done_" + unicastAddress, true).apply();
    }

    // =========================================================================
    // FILTERED DEVICES
    // =========================================================================

    public LiveData<List<ExtendedBluetoothDevice>> getFilteredDevices() {
        return filteredDevices;
    }

    public List<ExtendedBluetoothDevice> getFilteredDevicesValue() {
        List<ExtendedBluetoothDevice> v = filteredDevices.getValue();
        return v != null ? v : new ArrayList<>();
    }

    public void setFilteredDevices(List<ExtendedBluetoothDevice> devices) {
        filteredDevices.setValue(devices != null ? devices : new ArrayList<>());
    }

    public void clearFilteredDevices() {
        filteredDevices.setValue(new ArrayList<>());
    }

    // =========================================================================
    // ALL UNPROVISIONED DEVICES
    // =========================================================================

    public LiveData<List<ExtendedBluetoothDevice>> getAllUnprovisionedDevices() {
        return allUnprovisionedDevices;
    }

    public List<ExtendedBluetoothDevice> getAllUnprovisionedDevicesValue() {
        List<ExtendedBluetoothDevice> v = allUnprovisionedDevices.getValue();
        return v != null ? v : new ArrayList<>();
    }

    public void setAllUnprovisionedDevices(List<ExtendedBluetoothDevice> devices) {
        allUnprovisionedDevices.setValue(devices != null ? devices : new ArrayList<>());
    }

    public void addUnprovisionedDevice(ExtendedBluetoothDevice device) {
        if (device == null) return;
        List<ExtendedBluetoothDevice> current = getAllUnprovisionedDevicesValue();
        if (!current.contains(device)) {
            current.add(device);
            allUnprovisionedDevices.setValue(current);
        }
    }

    public void clearAllUnprovisionedDevices() {
        allUnprovisionedDevices.setValue(new ArrayList<>());
    }

    // =========================================================================
    // FILTER UTILITY
    // =========================================================================

    public boolean isFilterActive() {
        return !getSelectedDeviceValue().equals(DEFAULT_SELECTED_DEVICE)
                || getSignalThresholdValue() != DevicesAdapter.SIGNAL_DEFAULT;
    }

    public String getActiveFilterDescription() {
        StringBuilder sb = new StringBuilder();
        if (!getSelectedDeviceValue().equals(DEFAULT_SELECTED_DEVICE))
            sb.append("Device: ").append(getSelectedDeviceValue());
        if (getSignalThresholdValue() == DevicesAdapter.SIGNAL_100) {
            if (sb.length() > 0) sb.append(" | ");
            sb.append("Signal ≥ 100%");
        }
        return sb.length() > 0 ? "Filter: " + sb : "No filter active";
    }

    public void resetAllFilters() {
        clearSelectedDevice();
        clearSignalThreshold();
        applyCurrentFilter();
    }

    public List<ExtendedBluetoothDevice> applyFilter(List<ExtendedBluetoothDevice> devices) {
        if (devices == null) return new ArrayList<>();
        String  nameFilter      = getSelectedDeviceValue();
        int     threshold       = getSignalThresholdValue();
        boolean hasDeviceFilter = !nameFilter.equals(DEFAULT_SELECTED_DEVICE);
        boolean hasSignalFilter = threshold != DevicesAdapter.SIGNAL_DEFAULT;
        if (!hasDeviceFilter && !hasSignalFilter) return new ArrayList<>(devices);

        List<ExtendedBluetoothDevice> filtered = new ArrayList<>();
        String lowerFilter = nameFilter.toLowerCase();
        for (ExtendedBluetoothDevice device : devices) {
            boolean deviceOk = !hasDeviceFilter
                    || (device.getName() != null
                    && device.getName().toLowerCase().contains(lowerFilter));
            boolean signalOk = !hasSignalFilter
                    || matchesSignalThreshold(device, threshold);
            if (deviceOk && signalOk) filtered.add(device);
        }
        return filtered;
    }

    private boolean matchesSignalThreshold(@NonNull ExtendedBluetoothDevice device,
                                           int threshold) {
        int rssiPercent = (int) (100.0f * (127.0f + device.getRssi()) / (127.0f + 20.0f));
        return rssiPercent >= threshold;
    }

    public void applyCurrentFilter() {
        setFilteredDevices(applyFilter(getAllUnprovisionedDevicesValue()));
    }

    // =========================================================================
    // SCANNER REPOSITORY
    // =========================================================================

    public ScannerRepository getScannerRepository() { return mScannerRepository; }

    public LiveData<ScannerLiveData> getScannerResults() {
        return mScannerRepository.getScannerResults();
    }

    // =========================================================================
    // SELECTED SVG DEVICE ID
    // =========================================================================

    public LiveData<String> getSelectedSvgDeviceIdLiveData() { return mSelectedSvgDeviceId; }

    @Nullable
    public String getSelectedSvgDeviceId() { return mSelectedSvgDeviceId.getValue(); }

    public void setSelectedSvgDeviceId(@Nullable String svgDeviceId) {
        mSelectedSvgDeviceId.setValue(svgDeviceId);
        Log.d(TAG, "setSelectedSvgDeviceId: " + svgDeviceId);
    }

    public void clearSelectedSvgDeviceId() {
        mSelectedSvgDeviceId.setValue(null);
    }

    // =========================================================================
    // SERVER SVG DEVICE ID
    // =========================================================================

    public void setServerSvgDeviceId(@Nullable String serverSvgDeviceId) {
        mServerSvgDeviceId.setValue(serverSvgDeviceId);
        if (serverSvgDeviceId != null) {
            ClientServerElementStore.saveServerAreaId(serverSvgDeviceId, serverSvgDeviceId);
        }
        Log.d(TAG, "setServerSvgDeviceId: " + serverSvgDeviceId);
    }

    public LiveData<String> getServerSvgDeviceIdLiveData() { return mServerSvgDeviceId; }

    @Nullable
    public String getServerSvgDeviceId() { return mServerSvgDeviceId.getValue(); }

    public void clearServerSvgDeviceId() {
        mServerSvgDeviceId.setValue(null);
    }

    // =========================================================================
    // UTILITY — Node finders
    // =========================================================================

    @Nullable
    public ProvisionedMeshNode findNodeBySvgDeviceId(String svgDeviceId) {
        if (svgDeviceId == null) return null;
        String targetKey = ClientServerElementStore.normalize(svgDeviceId);
        try {
            MeshNetwork network = getNetworkLiveData().getMeshNetwork();
            if (network == null) return null;
            for (ProvisionedMeshNode node : network.getNodes()) {
                String nodeName = node.getNodeName();
                if (nodeName == null) continue;
                if (ClientServerElementStore.normalize(nodeName).equals(targetKey)) {
                    return node;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "findNodeBySvgDeviceId error", e);
        }
        return null;
    }

    public boolean selectNodeBySvgDeviceId(@Nullable String svgDeviceId) {
        ProvisionedMeshNode node = findNodeBySvgDeviceId(svgDeviceId);
        if (node != null) {
            setSelectedMeshNode(node);
            return true;
        }
        return false;
    }

    public int getProvisionedNodeCount() {
        try {
            MeshNetwork network = getNetworkLiveData().getMeshNetwork();
            if (network == null) return 0;
            List<ProvisionedMeshNode> nodes = network.getNodes();
            return nodes != null ? nodes.size() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    @Nullable
    public List<ProvisionedMeshNode> getAllProvisionedNodes() {
        try {
            MeshNetwork network = getNetworkLiveData().getMeshNetwork();
            if (network == null) return null;
            return network.getNodes();
        } catch (Exception e) {
            Log.e(TAG, "getAllProvisionedNodes error", e);
            return null;
        }
    }

    public LiveData<Boolean> isAutoSetupInProgress() {
        return mNrfMeshRepository.isAutoSetupInProgress();
    }

    // =========================================================================
    // Inner class — PublicationAttempt
    // Tracks retry state for a single publication pair
    // =========================================================================

    private static class PublicationAttempt {
        final ProvisionedMeshNode node;
        final int                 elementAddress; // source element being configured
        final int                 publishAddress; // destination to publish to
        final int                 modelId;
        final int                 appKeyIndex;
        final String              label;          // "C→S" or "S→C"
        int                       attemptCount = 0;
        Runnable                  timeoutRunnable;

        PublicationAttempt(ProvisionedMeshNode node, int elementAddress,
                           int publishAddress, int modelId,
                           int appKeyIndex, String label) {
            this.node           = node;
            this.elementAddress = elementAddress;
            this.publishAddress = publishAddress;
            this.modelId        = modelId;
            this.appKeyIndex    = appKeyIndex;
            this.label          = label;
        }
    }

    // =========================================================================
    // Inner class — PublishConfig (kept for compatibility)
    // =========================================================================

    public static class PublishConfig {
        private final int    address;
        private final String clientDeviceId;
        private final int    clientElementIndex;

        public PublishConfig(int address, String clientDeviceId, int clientElementIndex) {
            this.address            = address;
            this.clientDeviceId     = clientDeviceId;
            this.clientElementIndex = clientElementIndex;
        }

        public int    getAddress()            { return address; }
        public String getClientDeviceId()     { return clientDeviceId; }
        public int    getClientElementIndex() { return clientElementIndex; }
    }
}