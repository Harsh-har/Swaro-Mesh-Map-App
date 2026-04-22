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
import java.util.TreeMap;

import dagger.hilt.android.AndroidEntryPoint;
import no.nordicsemi.android.swaromapmesh.R;
import no.nordicsemi.android.swaromapmesh.transport.ProvisionedMeshNode;
import no.nordicsemi.android.swaromapmesh.viewmodels.ClientServerElementStore;
import no.nordicsemi.android.swaromapmesh.viewmodels.SharedViewModel;

@AndroidEntryPoint
public class AreaClientListActivity extends AppCompatActivity {

    private static final String TAG        = "AreaClientListActivity";
    private static final String PREFS_NAME = "mesh_prefs";

    private SharedViewModel   vm;
    private SharedPreferences prefs;

    private final List<AreaItem> areaItems = new ArrayList<>();

    // =========================================================================
    // Lifecycle
    // =========================================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_area_client_list);

        vm    = new ViewModelProvider(this).get(SharedViewModel.class);
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        vm.getProvisionedDeviceIds().observe(this, ids -> buildList());

        buildList();
    }

    @Override
    protected void onResume() {
        super.onResume();
        buildList();
    }

    // =========================================================================
    // List builder
    // =========================================================================

    private void buildList() {
        Set<String> ids = vm.getProvisionedDeviceIds().getValue();

        RecyclerView rv        = findViewById(R.id.recyclerView);
        LinearLayout emptyView = findViewById(R.id.emptyView);

        if (rv == null || emptyView == null) return;

        if (ids == null || ids.isEmpty()) {
            emptyView.setVisibility(View.VISIBLE);
            rv.setVisibility(View.GONE);
            return;
        }

        areaItems.clear();

        for (String id : ids) {
            List<ElementRow> rows = getElementRows(id);
            if (!rows.isEmpty()) {
                areaItems.add(new AreaItem(id, rows));
            }
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

    // =========================================================================
    // getElementRows
    // index saved in element_addr_ = svgId = 0-based (0 to 39)
    // server svgId=4 → getClientAddress(key,4) → element at position 4 ✅
    // =========================================================================

    private List<ElementRow> getElementRows(String areaId) {
        List<ElementRow> rows = new ArrayList<>();
        if (areaId == null) return rows;

        // "VCRI:SW-CN01-AA" → key="sw-cn01-aa", clientArea="VCRI"
        String name       = areaId.contains(":") ? areaId.split(":")[1].trim() : areaId;
        String key        = name.toLowerCase();
        String clientArea = areaId.contains(":")
                ? areaId.split(":")[0].trim().toUpperCase()
                : "";

        // ── Step 1: collect element_addr_<key>_<index> entries ───────────────
        Map<Integer, Integer> map = new TreeMap<>();

        for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
            String k = e.getKey();
            if (!k.startsWith("element_addr_")) continue;

            String rest = k.substring("element_addr_".length());
            int    sep  = rest.lastIndexOf("_");
            if (sep == -1) continue;

            String kName  = rest.substring(0, sep).toLowerCase();
            String kIndex = rest.substring(sep + 1);

            if (kName.equals(key)) {
                try {
                    map.put(Integer.parseInt(kIndex), (Integer) e.getValue());
                } catch (Exception ignored) {}
            }
        }

        Log.d(TAG, "getElementRows: areaId='" + areaId
                + "' clientArea='" + clientArea
                + "' key='" + key
                + "' found " + map.size() + " entries");

        // ── Step 2: index = svgId (0-based) → reverse lookup server ─────────
        for (Map.Entry<Integer, Integer> e : map.entrySet()) {
            int svgId      = e.getKey();   // 0-based svgId
            int clientAddr = e.getValue();
            int serverAddr = -1;

            String serverStoreKey = ClientServerElementStore.getKeyBySvgElementId(svgId);

            Log.d(TAG, "  svgId=" + svgId
                    + " clientAddr=0x" + String.format("%04X", clientAddr)
                    + " serverStoreKey=" + serverStoreKey);

            if (serverStoreKey != null) {
                String serverArea = serverStoreKey.contains(":")
                        ? serverStoreKey.split(":")[0].trim().toUpperCase()
                        : "";

                boolean areaMatch = clientArea.isEmpty()
                        || serverArea.isEmpty()
                        || clientArea.equals(serverArea);

                if (areaMatch) {
                    int addr = ClientServerElementStore.getServerUnicastAddress(serverStoreKey);
                    if (addr != -1 && isNodeStillProvisioned(addr)) {
                        serverAddr = addr;
                    }
                }
            }

            rows.add(new ElementRow(svgId, clientAddr, serverAddr));
        }

        return rows;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

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
    // Bottom-sheet detail
    // =========================================================================

    private void showDetail(AreaItem area) {
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        View v = getLayoutInflater().inflate(R.layout.sheet_area_detail, null);

        TextView     tvTitle    = v.findViewById(R.id.tvTitle);
        TextView     tvSubtitle = v.findViewById(R.id.tvSubtitle);
        RecyclerView rv         = v.findViewById(R.id.rvRows);

        tvSubtitle.setText(area.getTopName());
        tvTitle.setText(area.getBottomName());

        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(new RowAdapter(area.rows));

        v.findViewById(R.id.btnClose).setOnClickListener(x -> sheet.dismiss());

        sheet.setContentView(v);
        sheet.show();
    }

    // =========================================================================
    // Adapters
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

        @Override
        public int getItemCount() { return areaItems.size(); }

        class VH extends RecyclerView.ViewHolder {
            MaterialCardView card;
            TextView         tvName, tvId, tvCount;

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
            // index is 0-based svgId → display as-is: Element 0 to Element 39
            h.tvElem.setText("Element " + r.index);
            h.tvClient.setText(String.format("0x%04X", r.clientAddr));
            h.tvServer.setText(r.serverAddr != -1
                    ? String.format("0x%04X", r.serverAddr)
                    : "Not assigned");
        }

        @Override
        public int getItemCount() { return rows.size(); }

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
    // Data models
    // =========================================================================

    static class AreaItem {
        final String           areaId;
        final List<ElementRow> rows;

        AreaItem(String id, List<ElementRow> rows) {
            this.areaId = id;
            this.rows   = rows;
        }

        String getTopName() {
            if (areaId == null) return "";
            String prefix = areaId.contains(":")
                    ? areaId.split(":")[0].trim()
                    : areaId;
            return prefix + " Control Node";
        }

        String getBottomName() {
            if (areaId == null || !areaId.contains(":")) return "";
            return areaId.split(":")[1].trim();
        }
    }

    static class ElementRow {
        final int index, clientAddr, serverAddr;

        ElementRow(int i, int c, int s) {
            index      = i;
            clientAddr = c;
            serverAddr = s;
        }
    }
}