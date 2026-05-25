package no.nordicsemi.android.swaromapmesh.swajaui;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.util.SparseArray;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

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

import dagger.hilt.android.AndroidEntryPoint;
import no.nordicsemi.android.swaromapmesh.R;
import no.nordicsemi.android.swaromapmesh.transport.Element;
import no.nordicsemi.android.swaromapmesh.transport.MeshModel;
import no.nordicsemi.android.swaromapmesh.transport.ProvisionedMeshNode;
import no.nordicsemi.android.swaromapmesh.viewmodels.ClientServerElementStore;
import no.nordicsemi.android.swaromapmesh.viewmodels.SharedViewModel;

@AndroidEntryPoint
public class AreaClientListActivity extends AppCompatActivity {

    private static final String TAG        = "AreaClientListActivity";
    private static final String PREFS_NAME = "mesh_prefs";

    private SharedViewModel      vm;
    private SharedPreferences    prefs;
    private final List<AreaItem> areaItems = new ArrayList<>();
    private RecyclerView         rv;
    private LinearLayout         emptyView;
    private AreaAdapter          adapter;

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

        vm.getNodes().observe(this, nodes -> buildList());
    }

    @Override
    protected void onResume() {
        super.onResume();
        buildList();
    }

    // =========================================================================
    // BUILD LIST
    // =========================================================================

    private void buildList() {
        List<ProvisionedMeshNode> nodes = vm.getAllProvisionedNodes();

        if (nodes == null || nodes.isEmpty()) {
            emptyView.setVisibility(View.VISIBLE);
            rv.setVisibility(View.GONE);
            return;
        }

        // unicast → node name map
        Map<Integer, String> uniToName = new HashMap<>();
        for (ProvisionedMeshNode n : nodes) {
            if (n.getNodeName() != null) {
                uniToName.put(n.getUnicastAddress(), n.getNodeName());
                for (Element el : n.getElements().values())
                    uniToName.put(el.getElementAddress(), n.getNodeName());
            }
        }

        areaItems.clear();

        // Find all client nodes (Control Nodes)
        for (ProvisionedMeshNode node : nodes) {
            if (node.getNodeName() == null) continue;
            if (!node.getNodeName().trim().toLowerCase().startsWith("control node")) continue;

            List<ElementRow> rows = buildRowsForClientNode(node, uniToName);
            if (!rows.isEmpty()) {
                areaItems.add(new AreaItem(node.getNodeName(), rows));
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

    // =========================================================================
    // BUILD ROWS — same logic as TestProvisionActivity.showClientElements
    // =========================================================================

    private List<ElementRow> buildRowsForClientNode(
            ProvisionedMeshNode clientNode,
            Map<Integer, String> uniToName) {

        List<Element> sorted = new ArrayList<>(clientNode.getElements().values());
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            sorted.sort((a, b) ->
                    Integer.compare(a.getElementAddress(), b.getElementAddress()));
        }

        int base = clientNode.getUnicastAddress();

        // elementIndex → ElementRow  (only mapped elements)
        SparseArray<ElementRow> mapped = new SparseArray<>();
        int maxIndex = 0;

        for (Element el : sorted) {
            MeshModel model = el.getMeshModels().get(0x1001); // OnOff Client
            if (model == null) continue;

            // Skip unbound elements
            if (model.getBoundAppKeyIndexes() == null
                    || model.getBoundAppKeyIndexes().isEmpty()) continue;

            int elemAddr  = el.getElementAddress();
            int elemIndex = elemAddr - base;

            int    serverAddr = -1;
            String serverName = "";

            if (model.getPublicationSettings() != null) {
                int pubAddr = model.getPublicationSettings().getPublishAddress();
                if (pubAddr != 0) {
                    serverAddr = pubAddr;
                    String name = uniToName.get(pubAddr);
                    if (name != null) {
                        serverName = name;
                    } else {
                        serverName = pubAddr >= 0xC000
                                ? "Group " + String.format("0x%04X", pubAddr)
                                : "Unknown " + String.format("0x%04X", pubAddr);
                    }
                }
            }

            boolean s2cDone   = serverAddr != -1 && isDirectionComplete(serverAddr, elemAddr);
            boolean c2sDone   = serverAddr != -1 && isDirectionComplete(elemAddr, serverAddr);
            boolean fullyDone = serverAddr != -1 && isPublicationSetupComplete(elemAddr, serverAddr);

            mapped.put(elemIndex, new ElementRow(
                    elemIndex, elemAddr, serverAddr, serverName,
                    s2cDone, c2sDone, fullyDone, false));

            if (elemIndex > maxIndex) maxIndex = elemIndex;
        }

        // Fill 0 to maxIndex — blank rows for missing indices
        List<ElementRow> rows = new ArrayList<>();
        for (int i = 0; i <= maxIndex; i++) {
            ElementRow row = mapped.get(i);
            if (row != null) {
                rows.add(row);
            } else {
                // Client address is always base + i
                int clientAddr = base + i;
                rows.add(new ElementRow(i, clientAddr, -1, "",
                        false, false, false, true));
            }
        }

        Log.d(TAG, "buildRowsForClientNode: " + clientNode.getNodeName()
                + " mapped=" + mapped.size() + " total=" + rows.size());
        return rows;
    }

    // =========================================================================
    // HELPERS
    // =========================================================================

    private boolean isDirectionComplete(int fromAddr, int toAddr) {
        return prefs.getBoolean("dir_complete_" + fromAddr + "_" + toAddr, false);
    }

    private boolean isPublicationSetupComplete(int clientAddr, int serverAddr) {
        return prefs.getBoolean("pub_setup_complete_" + clientAddr + "_" + serverAddr, false);
    }

    // =========================================================================
    // UI — Bottom sheet detail
    // =========================================================================

    private void showDetail(AreaItem area) {
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        View v = getLayoutInflater().inflate(R.layout.sheet_area_detail, null);
        TextView tvTitle    = v.findViewById(R.id.tvTitle);
        TextView tvSubtitle = v.findViewById(R.id.tvSubtitle);
        RecyclerView rvRows = v.findViewById(R.id.rvRows);
        tvTitle.setText(area.areaId);
        tvSubtitle.setText(area.areaId.toUpperCase());
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
            h.tvName.setText(a.areaId);
            h.tvId.setText(a.areaId.toUpperCase());
            h.tvCount.setText(String.valueOf(a.rows.size()));
            h.card.setOnClickListener(view -> showDetail(a));
        }

        @Override
        public int getItemCount() { return areaItems.size(); }

        class VH extends RecyclerView.ViewHolder {
            final MaterialCardView card;
            final TextView tvName, tvId, tvCount;

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
        private final List<ElementRow> rowList;

        RowAdapter(List<ElementRow> rowList) {
            this.rowList = rowList;
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_element_row, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            ElementRow r = rowList.get(position);
            h.tvElem.setText(String.valueOf(r.index));

            h.tvClient.setText(r.clientAddr != -1
                    ? String.format("0x%04X", r.clientAddr) : "");

            if (r.isEmpty || r.serverAddr == -1) {
                h.tvServer.setText("");
            } else {
                h.tvServer.setText(String.format("0x%04X", r.serverAddr));
            }
        }

        @Override
        public int getItemCount() { return rowList.size(); }

        static class VH extends RecyclerView.ViewHolder {
            final TextView tvElem, tvClient, tvServer;

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
        final int     index;
        final int     clientAddr;
        final int     serverAddr;
        final String  serverName;
        final boolean s2cDone, c2sDone, fullyDone, isEmpty;

        ElementRow(int index, int clientAddr, int serverAddr, String serverName,
                   boolean s2c, boolean c2s, boolean full, boolean empty) {
            this.index      = index;
            this.clientAddr = clientAddr;
            this.serverAddr = serverAddr;
            this.serverName = serverName;
            this.s2cDone    = s2c;
            this.c2sDone    = c2s;
            this.fullyDone  = full;
            this.isEmpty    = empty;
        }
    }
}