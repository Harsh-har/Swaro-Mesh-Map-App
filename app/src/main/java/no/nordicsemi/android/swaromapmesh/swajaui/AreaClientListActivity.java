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
import androidx.appcompat.widget.Toolbar;
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
        // ✅ IMPORTANT: use activity_client_list (new layout, no attr colors)
        setContentView(R.layout.activity_area_client_list);

        vm    = new ViewModelProvider(this).get(SharedViewModel.class);
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Client Devices");
        }

        buildList();
    }

    // =========================================================================
    // Build list
    // =========================================================================

    private void buildList() {
        Set<String> provisionedIds = vm.getProvisionedDeviceIds().getValue();

        RecyclerView rv        = findViewById(R.id.recyclerView);
        LinearLayout emptyView = findViewById(R.id.emptyView);

        if (provisionedIds == null || provisionedIds.isEmpty()) {
            emptyView.setVisibility(View.VISIBLE);
            rv.setVisibility(View.GONE);
            return;
        }

        areaItems.clear();
        for (String id : provisionedIds) {
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
            rv.setLayoutManager(new LinearLayoutManager(this));
            rv.setAdapter(new AreaAdapter());
        }
    }

    // =========================================================================
    // Get element rows for one area
    // =========================================================================

    private List<ElementRow> getElementRows(String areaId) {
        List<ElementRow> rows = new ArrayList<>();
        if (areaId == null) return rows;

        // "PDRI:Relay Node1" → "relay node1"
        String deviceName = areaId;
        int colon = deviceName.lastIndexOf(":");
        if (colon != -1) deviceName = deviceName.substring(colon + 1).trim();
        String lowerName = deviceName.toLowerCase();

        // Client addresses: prefs key = "element_addr_{name}_{index}"
        Map<Integer, Integer> clientMap = new TreeMap<>();
        for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
            String key = e.getKey();
            if (!key.startsWith("element_addr_")) continue;

            String rest = key.substring("element_addr_".length());
            int    uIdx = rest.lastIndexOf("_");
            if (uIdx == -1) continue;

            String keyName  = rest.substring(0, uIdx).toLowerCase();
            String keyIndex = rest.substring(uIdx + 1);

            if (keyName.contains(lowerName) || lowerName.contains(keyName)) {
                try {
                    Object val = e.getValue();
                    if (val instanceof Integer) {
                        clientMap.put(Integer.parseInt(keyIndex), (Integer) val);
                    }
                } catch (NumberFormatException ignored) {}
            }
        }

        // For each client element find matching server unicast
        for (Map.Entry<Integer, Integer> e : clientMap.entrySet()) {
            int idx    = e.getKey();
            int client = e.getValue();
            int server = -1;

            for (String sk : ClientServerElementStore.getAllServerSvgKeys()) {
                if (ClientServerElementStore.getServerSvgElementId(sk) == idx) {
                    server = ClientServerElementStore.getServerUnicastAddress(sk);
                    break;
                }
            }
            rows.add(new ElementRow(idx, client, server));
        }

        return rows;
    }

    // =========================================================================
    // Show detail bottom sheet
    // =========================================================================

    private void showDetail(AreaItem area) {
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        View view = getLayoutInflater().inflate(R.layout.sheet_area_detail, null);

        TextView tvTitle    = view.findViewById(R.id.tvTitle);
        TextView tvSubtitle = view.findViewById(R.id.tvSubtitle);
        RecyclerView rv     = view.findViewById(R.id.rvRows);
        View btnClose       = view.findViewById(R.id.btnClose);

        if (tvTitle    != null) tvTitle.setText(area.displayName());
        if (tvSubtitle != null) tvSubtitle.setText(area.areaId);

        if (rv != null) {
            rv.setLayoutManager(new LinearLayoutManager(this));
            rv.setAdapter(new RowAdapter(area.rows));
        }

        if (btnClose != null) btnClose.setOnClickListener(v -> sheet.dismiss());

        sheet.setContentView(view);
        sheet.show();
    }

    // =========================================================================
    // AreaAdapter — main RecyclerView
    // =========================================================================

    private class AreaAdapter extends RecyclerView.Adapter<AreaAdapter.VH> {

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_area, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            AreaItem area = areaItems.get(position);

            if (h.tvName    != null) h.tvName.setText(area.displayName());
            if (h.tvId      != null) h.tvId.setText(area.areaId);
            if (h.tvCount   != null) h.tvCount.setText(area.rows.size() + " element(s)");

            if (h.tvPreview != null) {
                if (!area.rows.isEmpty()) {
                    ElementRow r = area.rows.get(0);
                    h.tvPreview.setText(
                            "Elem " + r.index
                                    + "  C:" + String.format("0x%04X", r.clientAddr)
                                    + "  S:" + (r.serverAddr != -1
                                    ? String.format("0x%04X", r.serverAddr) : "N/A"));
                    h.tvPreview.setVisibility(View.VISIBLE);
                } else {
                    h.tvPreview.setVisibility(View.GONE);
                }
            }

            if (h.card != null) {
                h.card.setOnClickListener(v -> showDetail(area));
            }
        }

        @Override
        public int getItemCount() { return areaItems.size(); }

        class VH extends RecyclerView.ViewHolder {
            MaterialCardView card;
            TextView         tvName, tvId, tvCount, tvPreview;

            VH(View v) {
                super(v);
                card      = v.findViewById(R.id.card);
                tvName    = v.findViewById(R.id.tvName);
                tvId      = v.findViewById(R.id.tvId);
                tvCount   = v.findViewById(R.id.tvCount);
            }
        }
    }

    // =========================================================================
    // RowAdapter — inside BottomSheet
    // =========================================================================

    private static class RowAdapter extends RecyclerView.Adapter<RowAdapter.VH> {

        private final List<ElementRow> rows;

        RowAdapter(List<ElementRow> rows) { this.rows = rows; }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_element_row, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            ElementRow r = rows.get(position);
            if (h.tvElem   != null) h.tvElem.setText("Element " + r.index);
            if (h.tvClient != null) h.tvClient.setText(String.format("0x%04X", r.clientAddr));
            if (h.tvServer != null) h.tvServer.setText(
                    r.serverAddr != -1
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
    // Data classes
    // =========================================================================

    static class AreaItem {
        final String          areaId;
        final List<ElementRow> rows;

        AreaItem(String areaId, List<ElementRow> rows) {
            this.areaId = areaId;
            this.rows   = rows;
        }

        /** "PDRI:Relay Node1" → "Relay Node1" */
        String displayName() {
            int c = areaId.lastIndexOf(":");
            return c != -1 ? areaId.substring(c + 1).trim() : areaId;
        }
    }

    static class ElementRow {
        final int index;
        final int clientAddr;
        final int serverAddr; // -1 = not yet provisioned

        ElementRow(int index, int clientAddr, int serverAddr) {
            this.index      = index;
            this.clientAddr = clientAddr;
            this.serverAddr = serverAddr;
        }
    }
}