package no.nordicsemi.android.swaromapmesh.swajaui;

import static android.content.Context.MODE_PRIVATE;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.card.MaterialCardView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import dagger.hilt.android.AndroidEntryPoint;
import no.nordicsemi.android.swaromapmesh.ApplicationKey;
import no.nordicsemi.android.swaromapmesh.R;
import no.nordicsemi.android.swaromapmesh.transport.ConfigModelPublicationSet;
import no.nordicsemi.android.swaromapmesh.transport.ConfigModelPublicationStatus;
import no.nordicsemi.android.swaromapmesh.transport.Element;
import no.nordicsemi.android.swaromapmesh.transport.MeshModel;
import no.nordicsemi.android.swaromapmesh.transport.ProvisionedMeshNode;
import no.nordicsemi.android.swaromapmesh.viewmodels.ClientServerElementStore;
import no.nordicsemi.android.swaromapmesh.viewmodels.SharedViewModel;

@AndroidEntryPoint
public class AreaClientListActivity extends AppCompatActivity {

    private static final String TAG       = "AreaClientListActivity";
    private static final String PREFS_NAME = "mesh_prefs";

    // Publication settings
    private static final int     DEFAULT_PUBLISH_TTL       = 5;
    private static final int     DEFAULT_RETRANSMIT_COUNT  = 3;
    private static final int     DEFAULT_RETRANSMIT_INTERVAL = 2;
    private static final int     PUBLICATION_STEPS         = 0;
    private static final int     PUBLICATION_RESOLUTION    = 0;
    private static final boolean CREDENTIAL_FLAG           = false;

    // Model IDs
    private static final int GENERIC_ONOFF_CLIENT = 0x1001;
    private static final int GENERIC_ONOFF_SERVER = 0x1000;

    // OpCode
    private static final int CONFIG_MODEL_PUBLICATION_STATUS = 0x8019;

    // ── Retry settings ────────────────────────────────────────────────────
    private static final int  MAX_RETRIES     = 3;
    private static final long RETRY_DELAY_MS  = 4000;

    // ── Retry state ───────────────────────────────────────────────────────
    private final Map<String, Integer>  mRetryCount     = new HashMap<>();
    private final Map<String, Runnable> mRetryRunnables = new HashMap<>();
    private final Handler               mHandler        = new Handler(Looper.getMainLooper());

    private SharedViewModel  vm;
    private SharedPreferences prefs;
    private final List<AreaItem> areaItems = new ArrayList<>();

    private boolean mWasAutoSetupInProgress = false;

    // =========================================================================
    // LIFECYCLE
    // =========================================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_area_client_list);

        vm    = new ViewModelProvider(this).get(SharedViewModel.class);
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        ClientServerElementStore.init(getApplicationContext());

        // ── Observe auto-setup progress ────────────────────────────────────
        vm.isAutoSetupInProgress().observe(this, inProgress -> {
            boolean isInProgress = Boolean.TRUE.equals(inProgress);
            if (mWasAutoSetupInProgress && !isInProgress) {
                Log.d(TAG, "✅ Auto-setup complete → triggering publication setup");
                buildList();
                mHandler.postDelayed(this::setupPublicationsForNewPairs, 500);
            }
            mWasAutoSetupInProgress = isInProgress;
        });

        // ── Observe provisioned device changes ─────────────────────────────
        vm.getProvisionedDeviceIds().observe(this, ids -> {
            Log.d(TAG, "Provisioned devices changed, rebuilding list...");
            buildList();
        });

        // ── Observe mesh messages for publication status ───────────────────
        vm.getMeshMessageLiveData().observe(this, meshMessage -> {
            if (meshMessage != null
                    && meshMessage.getOpCode() == CONFIG_MODEL_PUBLICATION_STATUS
                    && meshMessage instanceof ConfigModelPublicationStatus) {
                handlePublicationStatus((ConfigModelPublicationStatus) meshMessage);
            }
        });

        buildList();
    }

    @Override
    protected void onResume() {
        super.onResume();
        buildList();
        Boolean inProgress = vm.isAutoSetupInProgress().getValue();
        if (!Boolean.TRUE.equals(inProgress)) {
            setupPublicationsForNewPairs();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Cancel all pending retry runnables to avoid leaks
        for (Runnable r : mRetryRunnables.values()) {
            mHandler.removeCallbacks(r);
        }
        mRetryRunnables.clear();
        mRetryCount.clear();
    }
    // =========================================================================
    // PUBLICATION STATUS HANDLER
    // =========================================================================
    private void handlePublicationStatus(ConfigModelPublicationStatus status) {
        int elemAddr    = status.getElementAddress();
        int publishAddr = status.getPublishAddress();
        String retryKey = retryKey(elemAddr, publishAddr);

        if (!status.isSuccessful()) {
            Log.e(TAG, String.format(
                    "❌ Publication FAILED: Elem=0x%04X → Pub=0x%04X, StatusCode=%d",
                    elemAddr, publishAddr, status.getStatusCode()));
            scheduleRetry(elemAddr, publishAddr, retryKey);
            return;
        }

        Log.d(TAG, String.format(
                "📬 Publication OK: Elem=0x%04X → Pub=0x%04X",
                elemAddr, publishAddr));

        cancelRetry(retryKey);
        markDirectionComplete(elemAddr, publishAddr);

        // Check if both directions complete for this pair
        for (AreaItem area : areaItems) {
            for (ElementRow row : area.rows) {
                if (row.serverAddr == -1) continue;

                boolean isThisPair =
                        (elemAddr == row.serverAddr && publishAddr == row.clientAddr) ||
                                (elemAddr == row.receiveAddr && publishAddr == row.serverAddr);

                if (!isThisPair) continue;

                boolean s2cDone = isDirectionComplete(row.serverAddr, row.clientAddr);
                boolean c2sDone = (row.receiveAddr == -1)
                        || isDirectionComplete(row.receiveAddr, row.serverAddr);

                if (s2cDone && c2sDone) {
                    Log.d(TAG, String.format(
                            "🎉 BOTH DIRECTIONS COMPLETE: Server=0x%04X ↔ Client=0x%04X",
                            row.serverAddr, row.clientAddr));
                    markPublicationSetupComplete(row.serverAddr, row.clientAddr);
                    Toast.makeText(this,
                            String.format("✅ Publication complete: 0x%04X ↔ 0x%04X",
                                    row.serverAddr, row.clientAddr),
                            Toast.LENGTH_SHORT).show();
                }
                return;
            }
        }
    }
    // =========================================================================
    // RETRY LOGIC
    // =========================================================================

    private void scheduleRetry(int fromAddr, int toAddr, String retryKey) {
        int currentRetry = mRetryCount.getOrDefault(retryKey, 0);

        if (currentRetry >= MAX_RETRIES) {
            Log.e(TAG, String.format(
                    "❌ Max retries (%d) reached for 0x%04X → 0x%04X — giving up",
                    MAX_RETRIES, fromAddr, toAddr));
            mRetryCount.remove(retryKey);
            cancelRetry(retryKey);
            Toast.makeText(this,
                    String.format("Publication permanently failed: 0x%04X → 0x%04X",
                            fromAddr, toAddr),
                    Toast.LENGTH_LONG).show();
            return;
        }

        mRetryCount.put(retryKey, currentRetry + 1);
        long delay = RETRY_DELAY_MS * (currentRetry + 1);

        Log.d(TAG, String.format(
                "🔄 Scheduling retry %d/%d for 0x%04X → 0x%04X in %dms",
                currentRetry + 1, MAX_RETRIES, fromAddr, toAddr, delay));

        // Cancel existing retry for this key if any
        cancelRetry(retryKey);

        Runnable retryRunnable = () -> {
            mRetryRunnables.remove(retryKey);
            Log.d(TAG, String.format(
                    "🔁 Executing retry %d/%d for 0x%04X → 0x%04X",
                    mRetryCount.getOrDefault(retryKey, currentRetry + 1),
                    MAX_RETRIES, fromAddr, toAddr));
            retryPublication(fromAddr, toAddr);
        };

        mRetryRunnables.put(retryKey, retryRunnable);
        mHandler.postDelayed(retryRunnable, delay);
    }

    /**
     * Cancels a pending retry runnable if it exists.
     */
    private void cancelRetry(String retryKey) {
        Runnable existing = mRetryRunnables.remove(retryKey);
        if (existing != null) {
            mHandler.removeCallbacks(existing);
            Log.d(TAG, "🚫 Cancelled pending retry for key: " + retryKey);
        }
    }

    private void retryPublication(int fromAddr, int toAddr) {
        if (areaItems.isEmpty()) {
            Log.w(TAG, "retryPublication: areaItems empty — rebuilding list first");
            buildList();
        }

        for (AreaItem area : areaItems) {
            for (ElementRow row : area.rows) {
                if (row.serverAddr == -1) continue;

                // Match either direction
                boolean isClientToServer = (row.clientAddr == fromAddr && row.serverAddr == toAddr);
                boolean isServerToClient = (row.serverAddr == fromAddr && row.clientAddr == toAddr);

                if (!isClientToServer && !isServerToClient) continue;

                Log.d(TAG, String.format(
                        "🔁 Retrying: %s for area=%s Client=0x%04X Server=0x%04X",
                        isClientToServer ? "Client→Server" : "Server→Client",
                        area.getTopName(), row.clientAddr, row.serverAddr));

                ProvisionedMeshNode clientNode = findNodeByAddress(row.clientAddr);
                ProvisionedMeshNode serverNode = findNodeByAddress(row.serverAddr);

                if (clientNode == null || serverNode == null) {
                    Log.e(TAG, "retryPublication: node not found — abort retry");
                    return;
                }

                List<ApplicationKey> appKeys = vm.getNetworkLiveData().getAppKeys();
                if (appKeys == null || appKeys.isEmpty()) {
                    Log.e(TAG, "retryPublication: no AppKey — abort retry");
                    return;
                }
                int appKeyIndex = appKeys.get(0).getKeyIndex();

                if (isClientToServer) {
                    setupPublication(clientNode, row.clientAddr,
                            GENERIC_ONOFF_CLIENT, row.serverAddr,
                            appKeyIndex, "Client→Server [RETRY]");
                } else {
                    setupPublication(serverNode, row.serverAddr,
                            GENERIC_ONOFF_SERVER, row.clientAddr,
                            appKeyIndex, "Server→Client [RETRY]");
                }
                return;
            }
        }

        Log.e(TAG, String.format(
                "retryPublication: no matching pair found for 0x%04X → 0x%04X",
                fromAddr, toAddr));
    }

    private static String retryKey(int fromAddr, int toAddr) {
        return fromAddr + "_" + toAddr;
    }

    // =========================================================================
    // AUTO PUBLICATION SETUP
    // =========================================================================

    private void setupPublicationsForNewPairs() {
        if (areaItems.isEmpty()) return;

        for (AreaItem area : areaItems) {
            for (ElementRow row : area.rows) {
                if (row.serverAddr == -1) continue;

                // S→C direction: serverAddr → clientAddr
                boolean serverToClientDone = isDirectionComplete(row.serverAddr, row.clientAddr);
                // C→S direction: receiveAddr → serverAddr
                boolean clientToServerDone = (row.receiveAddr == -1)
                        || isDirectionComplete(row.receiveAddr, row.serverAddr);

                if (!serverToClientDone || !clientToServerDone) {
                    Log.d(TAG, "🔄 Incomplete: " + area.getTopName()
                            + String.format(" Server=0x%04X Client=0x%04X ReceiveElem=0x%04X",
                            row.serverAddr, row.clientAddr,
                            row.receiveAddr != -1 ? row.receiveAddr : 0));
                    Log.d(TAG, String.format("   S→C: %s, C→S: %s",
                            serverToClientDone ? "DONE" : "PENDING",
                            clientToServerDone ? "DONE" : "PENDING"));

                    setupMissingPublications(row, serverToClientDone, clientToServerDone);
                }
            }
        }
    }
    private void setupMissingPublications(ElementRow row,
                                          boolean serverToClientDone,
                                          boolean clientToServerDone) {
        // ── Direction 1: Server → Client (elementId based) ────────────────────
        if (!serverToClientDone && row.serverAddr != -1) {
            ProvisionedMeshNode serverNode = findNodeByAddress(row.serverAddr);
            if (serverNode != null) {
                List<ApplicationKey> appKeys = vm.getNetworkLiveData().getAppKeys();
                if (appKeys != null && !appKeys.isEmpty()) {
                    mRetryCount.remove(retryKey(row.serverAddr, row.clientAddr));
                    setupPublicationWithDelay(serverNode, row.serverAddr,
                            GENERIC_ONOFF_SERVER, row.clientAddr,
                            appKeys.get(0).getKeyIndex(), "Server→Client", 0);
                }
            }
        }

        // ── Direction 2: Client → Server (receiveId based) ────────────────────
        if (!clientToServerDone && row.receiveAddr != -1 && row.serverAddr != -1) {
            ProvisionedMeshNode clientNode = findNodeByAddress(row.receiveAddr);
            if (clientNode != null) {
                List<ApplicationKey> appKeys = vm.getNetworkLiveData().getAppKeys();
                if (appKeys != null && !appKeys.isEmpty()) {
                    mRetryCount.remove(retryKey(row.receiveAddr, row.serverAddr));
                    setupPublicationWithDelay(clientNode, row.receiveAddr,
                            GENERIC_ONOFF_CLIENT, row.serverAddr,
                            appKeys.get(0).getKeyIndex(), "Client→Server", 1500);
                }
            }
        }
    }

    private void setupPublicationWithDelay(ProvisionedMeshNode node, int sourceAddr,
                                           int modelId, int targetAddr, int appKeyIndex,
                                           String direction, long delay) {
        mHandler.postDelayed(() ->
                        setupPublication(node, sourceAddr, modelId, targetAddr, appKeyIndex, direction),
                delay);
    }

    private void setupPublication(ProvisionedMeshNode node, int sourceElementAddr,
                                  int modelId, int targetAddress, int appKeyIndex,
                                  String direction) {
        Log.d(TAG, String.format(
                "📤 %s: Node=0x%04X Elem=0x%04X Model=0x%04X → 0x%04X",
                direction, node.getUnicastAddress(), sourceElementAddr, modelId, targetAddress));

        Element element = node.getElements().get(sourceElementAddr);
        if (element == null) {
            Log.e(TAG, "Element not found: 0x" + String.format("%04X", sourceElementAddr));
            return;
        }

        MeshModel model = element.getMeshModels().get(modelId);
        if (model == null) {
            Log.e(TAG, "Model 0x" + String.format("%04X", modelId) + " not found in element");
            return;
        }

        try {
            ConfigModelPublicationSet publicationSet = new ConfigModelPublicationSet(
                    sourceElementAddr,
                    targetAddress,
                    appKeyIndex,
                    CREDENTIAL_FLAG,
                    DEFAULT_PUBLISH_TTL,
                    PUBLICATION_STEPS,
                    PUBLICATION_RESOLUTION,
                    DEFAULT_RETRANSMIT_COUNT,
                    DEFAULT_RETRANSMIT_INTERVAL,
                    modelId
            );
            vm.getMeshManagerApi().createMeshPdu(node.getUnicastAddress(), publicationSet);
            Log.d(TAG, "✅ Publication PDU sent: " + direction);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Failed to build publication set: " + e.getMessage());
        }
    }

    private ProvisionedMeshNode findNodeByAddress(int address) {
        List<ProvisionedMeshNode> nodes = vm.getAllProvisionedNodes();
        if (nodes == null) return null;
        for (ProvisionedMeshNode node : nodes) {
            if (node.getUnicastAddress() == address) return node;
            for (Element element : node.getElements().values()) {
                if (element.getElementAddress() == address) return node;
            }
        }
        return null;
    }

    // =========================================================================
    // PERSISTENCE HELPERS
    // =========================================================================

    private void markDirectionComplete(int fromAddr, int toAddr) {
        prefs.edit()
                .putBoolean("dir_complete_" + fromAddr + "_" + toAddr, true)
                .apply();
        Log.d(TAG, String.format("✅ Direction complete: 0x%04X → 0x%04X", fromAddr, toAddr));
    }

    private boolean isDirectionComplete(int fromAddr, int toAddr) {
        return prefs.getBoolean("dir_complete_" + fromAddr + "_" + toAddr, false);
    }

    private void markPublicationSetupComplete(int clientAddr, int serverAddr) {
        prefs.edit()
                .putBoolean("pub_setup_complete_" + clientAddr + "_" + serverAddr, true)
                .apply();
        Log.d(TAG, String.format("🎉 Full setup complete: 0x%04X ↔ 0x%04X",
                clientAddr, serverAddr));
    }

    private boolean isPublicationSetupComplete(int clientAddr, int serverAddr) {
        return prefs.getBoolean("pub_setup_complete_" + clientAddr + "_" + serverAddr, false);
    }

    // =========================================================================
    // BUILD LIST
    // =========================================================================

    private void buildList() {
        Set<String> ids = vm.getProvisionedDeviceIds().getValue();

        RecyclerView rv          = findViewById(R.id.recyclerView);
        LinearLayout emptyView   = findViewById(R.id.emptyView);
        if (rv == null || emptyView == null) return;

        if (ids == null || ids.isEmpty()) {
            emptyView.setVisibility(View.VISIBLE);
            rv.setVisibility(View.GONE);
            return;
        }

        areaItems.clear();
        for (String id : ids) {
            List<ElementRow> rows = getElementRows(id);
            if (!rows.isEmpty()) areaItems.add(new AreaItem(id, rows));
        }

        if (areaItems.isEmpty()) {
            emptyView.setVisibility(View.VISIBLE);
            rv.setVisibility(View.GONE);
        } else {
            emptyView.setVisibility(View.GONE);
            rv.setVisibility(View.VISIBLE);
            if (rv.getAdapter() == null) {
                rv.setLayoutManager(new LinearLayoutManager(this));
                rv.setAdapter(new AreaAdapter());
            } else {
                rv.getAdapter().notifyDataSetChanged();
            }
        }
    }

    private List<ElementRow> getElementRows(String areaId) {
        List<ElementRow> rows = new ArrayList<>();
        if (areaId == null) return rows;

        String name       = areaId.contains(":") ? areaId.split(":")[1].trim() : areaId;
        String key        = name.toLowerCase();
        String clientArea = areaId.contains(":")
                ? areaId.split(":")[0].trim().toUpperCase() : "";

        Map<Integer, Integer> clientAddrMap = new TreeMap<>();
        SharedPreferences storePrefs = ClientServerElementStore.getPrefsPublic();
        if (storePrefs != null) {
            for (Map.Entry<String, ?> e : storePrefs.getAll().entrySet()) {
                String k = e.getKey();
                if (!k.startsWith("element_addr_")) continue;
                String rest = k.substring("element_addr_".length());
                int sep = rest.lastIndexOf("_");
                if (sep == -1) continue;
                String kName  = rest.substring(0, sep).toLowerCase();
                String kIndex = rest.substring(sep + 1);
                if (kName.equals(key)) {
                    try {
                        clientAddrMap.put(Integer.parseInt(kIndex), (Integer) e.getValue());
                    } catch (Exception ignored) {}
                }
            }
        }

        for (Map.Entry<Integer, Integer> entry : clientAddrMap.entrySet()) {
            int svgId      = entry.getKey();
            int clientAddr = entry.getValue();
            int serverAddr = -1;
            int receiveAddr = -1;

            String serverStoreKey = ClientServerElementStore.getKeyBySvgElementIdAndArea(
                    svgId, clientArea.toLowerCase());

            if (serverStoreKey != null) {
                // Server address
                int sAddr = ClientServerElementStore.getServerUnicastAddress(serverStoreKey);
                if (sAddr != -1 && isNodeStillProvisioned(sAddr)) {
                    serverAddr = sAddr;
                }

                // receiveId → client element address for C→S direction
                String receiveId = ClientServerElementStore.getReceiveId(serverStoreKey);
                if (receiveId != null && !receiveId.trim().isEmpty()) {
                    try {
                        int receiveIndex = Integer.parseInt(receiveId.trim());
                        int rAddr = ClientServerElementStore.getClientAddress(
                                key, receiveIndex);
                        if (rAddr != -1) receiveAddr = rAddr;
                    } catch (NumberFormatException ignored) {}
                }
            }

            rows.add(new ElementRow(svgId, clientAddr, serverAddr, receiveAddr));
        }
        return rows;
    }
    private boolean isNodeStillProvisioned(int addr) {
        if (addr == -1) return false;
        List<ProvisionedMeshNode> nodes = vm.getAllProvisionedNodes();
        if (nodes == null) return false;
        for (ProvisionedMeshNode n : nodes) {
            if (n.getUnicastAddress() == addr) return true;
        }
        return false;
    }

    // =========================================================================
    // UI
    // =========================================================================

    private void showDetail(AreaItem area) {
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        View v = getLayoutInflater().inflate(R.layout.sheet_area_detail, null);
        TextView tvTitle    = v.findViewById(R.id.tvTitle);
        TextView tvSubtitle = v.findViewById(R.id.tvSubtitle);
        RecyclerView rv     = v.findViewById(R.id.rvRows);
        tvSubtitle.setText(area.getTopName());
        tvTitle.setText(area.getBottomName());
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(new RowAdapter(area.rows));
        v.findViewById(R.id.btnClose).setOnClickListener(x -> sheet.dismiss());
        sheet.setContentView(v);
        sheet.show();
    }

    // =========================================================================
    // ADAPTERS
    // =========================================================================

    private class AreaAdapter extends RecyclerView.Adapter<AreaAdapter.VH> {
        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_area, parent, false));
        }
        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            AreaItem a = areaItems.get(position);
            h.tvName.setText(a.getTopName());
            h.tvId.setText(a.getBottomName());
            h.tvCount.setText(a.rows.size() + " element(s)");
            h.card.setOnClickListener(v -> showDetail(a));
        }
        @Override public int getItemCount() { return areaItems.size(); }

        class VH extends RecyclerView.ViewHolder {
            MaterialCardView card;
            TextView tvName, tvId, tvCount;
            VH(View v) {
                super(v);
                card    = v.findViewById(R.id.card);
                tvName  = v.findViewById(R.id.tvName);
                tvId    = v.findViewById(R.id.tvId);
                tvCount = v.findViewById(R.id.tvCount);
            }
        }
    }

    private static class RowAdapter extends RecyclerView.Adapter<RowAdapter.VH> {
        private final List<ElementRow> rows;
        RowAdapter(List<ElementRow> rows) { this.rows = rows; }
        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_element_row, parent, false));
        }
        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            ElementRow r = rows.get(position);
            h.tvElem.setText("Element " + r.index);
            h.tvClient.setText(String.format("0x%04X", r.clientAddr));
            h.tvServer.setText(r.serverAddr != -1
                    ? String.format("0x%04X", r.serverAddr) : "Not assigned");
        }
        @Override public int getItemCount() { return rows.size(); }

        static class VH extends RecyclerView.ViewHolder {
            TextView tvElem, tvClient, tvServer;
            VH(View v) {
                super(v);
                tvElem   = v.findViewById(R.id.tvElem);
                tvClient = v.findViewById(R.id.tvClient);
                tvServer = v.findViewById(R.id.tvServer);
            }
        }
    }

    // =========================================================================
    // DATA MODELS
    // =========================================================================

    static class AreaItem {
        final String areaId;
        final List<ElementRow> rows;
        AreaItem(String id, List<ElementRow> rows) { this.areaId = id; this.rows = rows; }
        String getTopName() {
            if (areaId == null) return "";
            return (areaId.contains(":") ? areaId.split(":")[0].trim() : areaId) + " Control Node";
        }
        String getBottomName() {
            if (areaId == null || !areaId.contains(":")) return "";
            return areaId.split(":")[1].trim();
        }
    }

    // ── ElementRow — receiveAddr ─────────────────────────────────
    static class ElementRow {
        final int index, clientAddr, serverAddr, receiveAddr;
        // receiveAddr = client element address used for Client→Server direction
        ElementRow(int i, int c, int s, int r) {
            index = i; clientAddr = c; serverAddr = s; receiveAddr = r;
        }
    }
}