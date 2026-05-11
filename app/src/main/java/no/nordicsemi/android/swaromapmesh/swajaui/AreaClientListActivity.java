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
        Set<String> ids = vm.getProvisionedDeviceIds().getValue();

        if (ids == null || ids.isEmpty()) {
            emptyView.setVisibility(View.VISIBLE);
            rv.setVisibility(View.GONE);
            return;
        }

        areaItems.clear();

        // Area prefix ke hisaab se group karo
        java.util.Map<String, List<String>> areaToKeys = new java.util.LinkedHashMap<>();
        for (String id : ids) {
            String area = id.contains(":") ? id.split(":")[0].trim().toLowerCase() : "";
            areaToKeys.computeIfAbsent(area, k -> new ArrayList<>()).add(id);
        }

        for (Map.Entry<String, List<String>> entry : areaToKeys.entrySet()) {
            String areaPrefix = entry.getKey();
            List<ElementRow> rows = getElementRowsForArea(areaPrefix, entry.getValue());
            if (!rows.isEmpty()) {
                areaItems.add(new AreaItem(areaPrefix, rows));
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
    // GET ELEMENT ROWS — area ke saare server keys ke liye rows
    // =========================================================================

    private List<ElementRow> getElementRowsForArea(String areaPrefix, List<String> serverKeys) {
        List<ElementRow> rows = new ArrayList<>();

        for (String serverKey : serverKeys) {
            int serverAddr = ClientServerElementStore.getServerUnicastAddress(serverKey);
            if (serverAddr == -1) continue;
            if (!isNodeStillProvisioned(serverAddr)) continue;
            if (!isServerNode(serverAddr)) continue;

            int svgElementId = ClientServerElementStore.getServerSvgElementId(serverKey);
            if (svgElementId == -1) {
                Log.w(TAG, "svgElementId=-1 for " + serverKey);
                continue;
            }

            String clientName = findClientForServer(areaPrefix, svgElementId);
            if (clientName == null) {
                Log.w(TAG, "no client for elementId=" + svgElementId + " area=" + areaPrefix);
                continue;
            }

            int clientAddr = ClientServerElementStore.getClientAddress(clientName, svgElementId);
            if (clientAddr == -1) continue;

            int receiveAddr = -1;
            String receiveIdStr = ClientServerElementStore.getReceiveId(serverKey);
            if (receiveIdStr != null && !receiveIdStr.trim().isEmpty()) {
                try {
                    int receiveIndex = Integer.parseInt(receiveIdStr.trim());
                    // receiveId aur elementId same hain toh receiveAddr = clientAddr hi hoga
                    // lekin same address dobara set nahi karna — skip karo agar same ho
                    int rAddr = ClientServerElementStore.getClientAddress(clientName, receiveIndex);
                    if (rAddr != -1 && rAddr != clientAddr) {
                        receiveAddr = rAddr;
                    }
                } catch (NumberFormatException ignored) {}
            }

            boolean s2cDone = isDirectionComplete(serverAddr, clientAddr);
            boolean c2sDone = receiveAddr == -1 || isDirectionComplete(receiveAddr, serverAddr);
            boolean fullyDone = isPublicationSetupComplete(clientAddr, serverAddr);

            Log.d(TAG, "Row: " + serverKey
                    + " elementId=" + svgElementId
                    + " client=0x" + String.format("%04X", clientAddr)
                    + " server=0x" + String.format("%04X", serverAddr)
                    + " receive=0x" + String.format("%04X", receiveAddr)
                    + " s2c=" + s2cDone + " c2s=" + c2sDone + " done=" + fullyDone);

            rows.add(new ElementRow(svgElementId, clientAddr, serverAddr, receiveAddr,
                    s2cDone, c2sDone, fullyDone));
        }
        return rows;
    }

    // =========================================================================
    // HELPERS
    // =========================================================================

    @Nullable
    private String findClientForServer(String areaPrefix, int svgElementId) {
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
                        areaMatch = true; break;
                    }
                }
                if (!areaMatch) continue;
            }
            return storedName;
        }

        int fallback = ClientServerElementStore.getClientAddress(
                areaPrefix.toLowerCase(), svgElementId);
        return fallback != -1 ? areaPrefix.toLowerCase() : null;
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

    private boolean isServerNode(int unicastAddr) {
        List<ProvisionedMeshNode> nodes = vm.getAllProvisionedNodes();
        if (nodes == null) return false;
        for (ProvisionedMeshNode node : nodes) {
            for (Element el : node.getElements().values()) {
                if (el.getElementAddress() == unicastAddr) {
                    return el.getMeshModels().containsKey(0x1000);
                }
            }
        }
        return false;
    }

    private boolean isDirectionComplete(int fromAddr, int toAddr) {
        return prefs.getBoolean("dir_complete_" + fromAddr + "_" + toAddr, false);
    }

    private boolean isPublicationSetupComplete(int clientAddr, int serverAddr) {
        return prefs.getBoolean("pub_setup_complete_" + clientAddr + "_" + serverAddr, false);
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
        AreaItem(String id, List<ElementRow> rows) {
            this.areaId = id;
            this.rows   = rows;
        }
    }

    static class ElementRow {
        final int index, clientAddr, serverAddr, receiveAddr;
        final boolean s2cDone, c2sDone, fullyDone;

        ElementRow(int i, int c, int s, int r,
                   boolean s2c, boolean c2s, boolean full) {
            index       = i;
            clientAddr  = c;
            serverAddr  = s;
            receiveAddr = r;
            s2cDone     = s2c;
            c2sDone     = c2s;
            fullyDone   = full;
        }
    }
}