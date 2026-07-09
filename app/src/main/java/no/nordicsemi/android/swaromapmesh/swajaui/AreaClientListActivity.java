package no.nordicsemi.android.swaromapmesh.swajaui;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.card.MaterialCardView;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dagger.hilt.android.AndroidEntryPoint;
import no.nordicsemi.android.swaromapmesh.R;
import no.nordicsemi.android.swaromapmesh.transport.Element;
import no.nordicsemi.android.swaromapmesh.transport.ProvisionedMeshNode;
import no.nordicsemi.android.swaromapmesh.viewmodels.ClientServerElementStore;
import no.nordicsemi.android.swaromapmesh.viewmodels.SharedViewModel;

@AndroidEntryPoint
public class AreaClientListActivity extends AppCompatActivity {

    private static final String TAG = "AreaClientListActivity";
    private static final String PREFS_NAME = "mesh_prefs";

    private SharedViewModel vm;
    private SharedPreferences prefs;
    private final List<AreaItem> areaItems = new ArrayList<>();
    private RecyclerView rv;
    private LinearLayout emptyView;
    private AreaAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_area_client_list);

        vm    = new ViewModelProvider(this).get(SharedViewModel.class);
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        ClientServerElementStore.init(getApplicationContext());

        rv        = findViewById(R.id.recyclerView);
        emptyView = findViewById(R.id.emptyView);

        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new AreaAdapter();
        rv.setAdapter(adapter);

        vm.getProvisionedDeviceIds().observe(this, ids -> buildList());
    }

    @Override
    protected void onResume() {
        super.onResume();
        buildList();
    }

    // =========================================================================
    // BUILD LIST — sirf ek baar build karo, duplicate entries nahi
    // =========================================================================

    private void buildList() {
        areaItems.clear();

        List<ProvisionedMeshNode> allNodes = vm.getAllProvisionedNodes();
        if (allNodes == null || allNodes.isEmpty()) {
            emptyView.setVisibility(View.VISIBLE);
            rv.setVisibility(View.GONE);
            return;
        }

        // 1. Identify all Control Nodes
        List<ProvisionedMeshNode> controlNodes = new ArrayList<>();
        for (ProvisionedMeshNode n : allNodes) {
            if (n.getNodeName() != null && n.getNodeName().toLowerCase().contains("control node")) {
                controlNodes.add(n);
            }
        }

        if (controlNodes.isEmpty()) {
            emptyView.setVisibility(View.VISIBLE);
            rv.setVisibility(View.GONE);
            return;
        }

        // 2. Build rows for each Control Node
        for (ProvisionedMeshNode cn : controlNodes) {
            String area = extractAreaFromName(cn.getNodeName());
            List<ElementRow> rows = getElementsForControlNode(cn);
            if (!rows.isEmpty()) {
                areaItems.add(new AreaItem(area, rows));
            }
        }

        if (areaItems.isEmpty()) {
            emptyView.setVisibility(View.VISIBLE);
            rv.setVisibility(View.GONE);
        } else {
            emptyView.setVisibility(View.GONE);
            rv.setVisibility(View.VISIBLE);
            adapter.notifyDataSetChanged();
        }
    }

    private String extractAreaFromName(String name) {
        if (name == null) return "Unknown";
        String lower = name.toLowerCase();
        if (lower.startsWith("control node ")) {
            return name.substring("control node ".length());
        }
        return name;
    }

    private List<ElementRow> getElementsForControlNode(ProvisionedMeshNode node) {
        List<ElementRow> rows = new ArrayList<>();
        int baseAddr = node.getUnicastAddress();
        
        Log.d(TAG, "🔍 SCANNING Control Node: " + node.getNodeName() + " (0x" + String.format("%04X", baseAddr) + ")");

        List<Element> elements = new ArrayList<>(node.getElements().values());
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            elements.sort(java.util.Comparator.comparingInt(no.nordicsemi.android.swaromapmesh.transport.Element::getElementAddress));
        }

        Set<String> provisionedKeys = ClientServerElementStore.getProvisionedKeys();

        for (Element el : elements) {
            int clientAddr = el.getElementAddress();
            int eid = clientAddr - baseAddr;
            if (eid == 0) continue; // Skip foundation Config Server element

            // generic onoff client model
            no.nordicsemi.android.swaromapmesh.transport.MeshModel clientModel = el.getMeshModels().get(0x1001);
            if (clientModel == null) continue;

            int srvAddr = -1;
            String serverName = "Not Configured";
            
            // 1. Check mesh for actual publication destination
            if (clientModel.getPublicationSettings() != null) {
                int pub = clientModel.getPublicationSettings().getPublishAddress();
                if (pub != 0 && pub != 0xFFFF) {
                    srvAddr = pub;
                }
            }

            // 2. Fallback to app store lookup by EID
            if (srvAddr == -1) {
                for (String key : provisionedKeys) {
                    if (ClientServerElementStore.getServerSvgElementId(key) == eid) {
                        srvAddr = ClientServerElementStore.getServerUnicastAddress(key);
                        break;
                    }
                }
            }

            // 3. Find name for the address
            if (srvAddr != -1) {
                ProvisionedMeshNode sNode = findNodeByAddress(srvAddr);
                serverName = (sNode != null) ? sNode.getNodeName() : "Node 0x" + String.format("%04X", srvAddr);
            }

            boolean s2cDone = (srvAddr != -1) && isDirectionComplete(srvAddr, clientAddr);
            boolean c2sDone = (srvAddr != -1) && isDirectionComplete(clientAddr, srvAddr);
            boolean fullyDone = (srvAddr != -1) && isPublicationSetupComplete(clientAddr, srvAddr);

            Log.d(TAG, "   └─ Elem " + eid + " (0x" + String.format("%04X", clientAddr) + ") -> Svr: " + serverName + " [" + (srvAddr != -1 ? "0x" + String.format("%04X", srvAddr) : "NONE") + "]");

            rows.add(new ElementRow(eid, clientAddr, srvAddr, -1, serverName,
                    s2cDone, c2sDone, fullyDone));
        }
        return rows;
    }

    private boolean isDirectionComplete(int fromAddr, int toAddr) {
        return prefs.getBoolean("dir_complete_" + fromAddr + "_" + toAddr, false);
    }

    private boolean isPublicationSetupComplete(int clientAddr, int serverAddr) {
        return prefs.getBoolean("pub_setup_complete_" + clientAddr + "_" + serverAddr, false);
    }

    private ProvisionedMeshNode findNodeByAddress(int addr) {
        if (addr == -1) return null;
        List<ProvisionedMeshNode> nodes = vm.getAllProvisionedNodes();
        if (nodes != null) {
            for (ProvisionedMeshNode n : nodes) {
                if (n.getUnicastAddress() == addr) return n;
                for (no.nordicsemi.android.swaromapmesh.transport.Element el : n.getElements().values()) {
                    if (el.getElementAddress() == addr) return n;
                }
            }
        }
        return null;
    }

    // =========================================================================
    // UI
    // =========================================================================

    private void showDetail(AreaItem area) {
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        View v = getLayoutInflater().inflate(R.layout.sheet_area_detail, null);
        TextView tvTitle    = v.findViewById(R.id.tvTitle);
        TextView tvSubtitle = v.findViewById(R.id.tvSubtitle);
        RecyclerView rvRows = v.findViewById(R.id.rvRows);
        tvSubtitle.setText(area.areaId.toUpperCase());
        tvTitle.setText(area.areaId + " area");
        rvRows.setLayoutManager(new LinearLayoutManager(this));
        rvRows.setAdapter(new RowAdapter(area.rows));
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
            h.tvName.setText(a.areaId.toUpperCase() + " Control Node");
            h.tvId.setText(a.areaId);
            h.tvCount.setText(a.rows.size() + " server(s)");
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
            h.tvElem.setText("Elem " + r.index);
            h.tvClient.setText(String.format("0x%04X", r.clientAddr));
            
            if (r.serverAddr != -1 && r.serverAddr != 0) {
                h.tvServer.setText(String.format("0x%04X\n%s", r.serverAddr, r.serverName));
            } else {
                h.tvServer.setText("Not Configured");
            }
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
        AreaItem(String id, List<ElementRow> rows) {
            this.areaId = id;
            this.rows   = rows;
        }
    }

    static class ElementRow {
        final int index, clientAddr, serverAddr, receiveAddr;
        final String serverName;
        final boolean s2cDone, c2sDone, fullyDone;

        ElementRow(int i, int c, int s, int r, String name,
                   boolean s2c, boolean c2s, boolean full) {
            index       = i;
            clientAddr  = c;
            serverAddr  = s;
            receiveAddr = r;
            serverName  = name;
            s2cDone     = s2c;
            c2sDone     = c2s;
            fullyDone   = full;
        }
    }
}