package no.nordicsemi.android.swaromapmesh.swajaui;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.PictureDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.caverock.androidsvg.SVG;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import dagger.hilt.android.AndroidEntryPoint;
import no.nordicsemi.android.swaromapmesh.MainActivity;
import no.nordicsemi.android.swaromapmesh.R;
import no.nordicsemi.android.swaromapmesh.viewmodels.ClientServerElementStore;

@AndroidEntryPoint
public class AreaListActivity extends AppCompatActivity {

    private static final String TAG = "AreaListActivity";

    // ── Dot colors ────────────────────────────────────────────────────────
    private static final int COLOR_GREEN  = 0xFF7CBB00;  // all provisioned
    private static final int COLOR_ORANGE = 0xFFF58700;  // partial

    private RecyclerView rvAreas;
    private LinearLayout emptyView;
    private TextView     tvSiteTitle;

    private String svgUriString;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    // ══════════════════════════════════════════════════════════════════════
    //  LIFECYCLE
    // ══════════════════════════════════════════════════════════════════════

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_area_list);

        // CRITICAL: init store so isProvisioned() works
        ClientServerElementStore.init(getApplicationContext());

        rvAreas     = findViewById(R.id.rvAreas);
        emptyView   = findViewById(R.id.emptyView);
        tvSiteTitle = findViewById(R.id.tvSiteTitle);

        svgUriString = getIntent().getStringExtra("svg_uri");
        if (svgUriString == null) {
            showEmpty();
            return;
        }

        SharedPreferences prefs = getSharedPreferences("app_prefs", MODE_PRIVATE);
        String siteTitle = prefs.getString("svg_name_" + svgUriString, "Imported Map");
        tvSiteTitle.setText(siteTitle);

        Uri uri = Uri.parse(svgUriString);

        executor.execute(() -> {
            LinkedHashMap<String, List<String>> areaMap =
                    SvgParserList.parseFloorAreas(getContentResolver(), uri);

            runOnUiThread(() -> {
                if (areaMap.isEmpty()) { showEmpty(); return; }
                buildList(areaMap);
            });
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    // ══════════════════════════════════════════════════════════════════════
    //  BUILD LIST
    // ══════════════════════════════════════════════════════════════════════

    private void buildList(LinkedHashMap<String, List<String>> areaMap) {
        List<ListItem> items = new ArrayList<>();

        boolean hasMultiFloor = false;
        for (String key : areaMap.keySet()) {
            if (key.contains("Floor") || key.equals("Ground_Floor") ||
                    key.equals("First_Floor") || key.equals("Terrace_Floor") ||
                    key.endsWith("_Floor")) {
                hasMultiFloor = true;
                break;
            }
        }

        if (hasMultiFloor) {
            for (Map.Entry<String, List<String>> entry : areaMap.entrySet()) {
                String       floorId = entry.getKey();
                List<String> areas   = entry.getValue();
                items.add(new ListItem(formatName(floorId), true, null, null));
                for (String areaId : areas) {
                    items.add(new ListItem(formatName(areaId), false, areaId, new ArrayList<>()));
                }
            }
        } else {
            for (Map.Entry<String, List<String>> entry : areaMap.entrySet()) {
                String       areaId    = entry.getKey();
                List<String> deviceIds = entry.getValue();

                if (areaId.equals("Relation") || areaId.equals("Devices") ||
                        areaId.equals("Icons")   || areaId.equals("selection_layer")) {
                    continue;
                }

                items.add(new ListItem(formatName(areaId), false, areaId, deviceIds));
                Log.d(TAG, "Area: " + areaId + " devices=" + deviceIds.size());
            }
        }

        if (items.isEmpty()) { showEmpty(); return; }

        rvAreas.setLayoutManager(new LinearLayoutManager(this));
        rvAreas.setAdapter(new AreaAdapter(items));
        rvAreas.setVisibility(View.VISIBLE);
        emptyView.setVisibility(View.GONE);
    }

    private int getDotColor(List<String> deviceIds) {
        if (deviceIds == null || deviceIds.isEmpty()) return 0;

        int total       = deviceIds.size();
        int provisioned = 0;

        for (String rawId : deviceIds) {
            if (isDeviceProvisioned(rawId)) provisioned++;
        }

        Log.d(TAG, "getDotColor: total=" + total + " provisioned=" + provisioned);

        if (provisioned == 0)     return 0;
        if (provisioned == total) return COLOR_GREEN;
        return COLOR_ORANGE;
    }

    private boolean isDeviceProvisioned(String svgDeviceId) {
        if (svgDeviceId == null || svgDeviceId.isEmpty()) return false;

        Set<String> provisionedKeys = ClientServerElementStore.getProvisionedKeys();
        if (provisionedKeys == null || provisionedKeys.isEmpty()) return false;

        // 1. Direct exact match
        if (provisionedKeys.contains(svgDeviceId)) {
            Log.d(TAG, "  [✅ DIRECT] " + svgDeviceId);
            return true;
        }

        // 2. Case-insensitive direct match
        String svgLower = svgDeviceId.toLowerCase(Locale.ROOT);
        for (String key : provisionedKeys) {
            if (key.toLowerCase(Locale.ROOT).equals(svgLower)) {
                Log.d(TAG, "  [✅ CASE] " + svgDeviceId + " → " + key);
                return true;
            }
        }

        Log.d(TAG, "  [❌ NO MATCH] " + svgDeviceId);
        return false;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  ICON LOADER
    // ══════════════════════════════════════════════════════════════════════

    private void loadAreaIcon(ImageView iv, String areaLabel) {
        try {
            String file = getIconFileName(areaLabel);
            InputStream is = getAssets().open("area_icons/" + file);
            SVG svg = SVG.getFromInputStream(is);
            iv.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            iv.setImageDrawable(new PictureDrawable(svg.renderToPicture()));
            is.close();
        } catch (Exception e) {
            try {
                InputStream is = getAssets().open("area_icons/Corridor.svg");
                SVG svg = SVG.getFromInputStream(is);
                iv.setImageDrawable(new PictureDrawable(svg.renderToPicture()));
                is.close();
            } catch (Exception ex) {
                iv.setImageResource(R.drawable.ic_settings);
            }
        }
    }

    private String getIconFileName(String label) {
        if (label == null) return "Corridor.svg";
        String lower = label.toLowerCase(Locale.ROOT);
        if (lower.contains("casting"))                            return "Powder room.svg";
        if (lower.contains("vacuum") || lower.contains("casting"))   return "Casting.svg";
        if (lower.contains("engineering"))                           return "Engineering.svg";
        if (lower.contains("recreational"))                          return "Recreation.svg";
        if (lower.contains("account") || lower.contains("department")) return "Accounts.svg";
        if (lower.contains("3d") || lower.contains("printing"))     return "3DPrinting.svg";
        if (lower.contains("washroom") || lower.contains("bathroom")) return "Washroom.svg";
        if (lower.contains("entrance"))                              return "Entrance.svg";
        if (lower.contains("kitchen"))                               return "Kitchen.svg";
        if (lower.contains("balcony"))                               return "Balcony.svg";
        if (lower.contains("stairs"))                                return "Stairs.svg";
        if (lower.contains("living"))                                return "LivingRoom.svg";
        if (lower.contains("bedroom"))                               return "Bedroom.svg";
        if (lower.contains("lounge"))                                return "Lounge.svg";
        if (lower.contains("smt") || lower.contains("surface"))     return "Production.svg";
        return "Corridor.svg";
    }

    // ══════════════════════════════════════════════════════════════════════
    //  HELPERS
    // ══════════════════════════════════════════════════════════════════════

    private String formatName(String id) {
        if (id == null) return "";

        // Remove known room-code suffixes like _MBDR, _GBDR, _PBDR, _LVR, _KTN, etc.
        String cleaned = id.replaceAll("_[A-Z0-9]{2,6}$", "");

        // Replace remaining underscores/hyphens with spaces
        return cleaned.replace("_", " ").replace("-", " ").trim();
    }

    private void showEmpty() {
        rvAreas.setVisibility(View.GONE);
        emptyView.setVisibility(View.VISIBLE);
    }

    // ══════════════════════════════════════════════════════════════════════
    //  MODEL
    // ══════════════════════════════════════════════════════════════════════

    static class ListItem {
        final String       label;
        final boolean      isHeader;
        final String       areaId;
        final List<String> deviceIds;

        ListItem(String label, boolean isHeader, String areaId, List<String> deviceIds) {
            this.label     = label;
            this.isHeader  = isHeader;
            this.areaId    = areaId;
            this.deviceIds = deviceIds != null ? deviceIds : new ArrayList<>();
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  ADAPTER
    // ══════════════════════════════════════════════════════════════════════

    class AreaAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        final List<ListItem> items;

        AreaAdapter(List<ListItem> items) { this.items = items; }

        @Override
        public int getItemViewType(int pos) { return items.get(pos).isHeader ? 0 : 1; }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(
                @NonNull ViewGroup parent, int type) {
            LayoutInflater inf = LayoutInflater.from(parent.getContext());
            if (type == 0) {
                return new HeaderVH(inf.inflate(R.layout.item_area_header, parent, false));
            } else {
                return new AreaVH(inf.inflate(R.layout.item_maparea, parent, false));
            }
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int pos) {
            ListItem item = items.get(pos);

            if (holder instanceof HeaderVH) {
                ((HeaderVH) holder).tv.setText(item.label);

            } else {
                AreaVH vh = (AreaVH) holder;
                vh.name.setText(item.label);
                loadAreaIcon(vh.icon, item.label);

                // ── Status dot ────────────────────────────────────────────
                int dotColor = getDotColor(item.deviceIds);
                if (dotColor == 0) {
                    vh.statusDot.setVisibility(View.INVISIBLE);
                } else {
                    vh.statusDot.setVisibility(View.VISIBLE);
                    vh.statusDot.getBackground().mutate().setTint(dotColor);
                }

                // ── Click ─────────────────────────────────────────────────
                vh.itemView.setOnClickListener(v -> {
                    String navigateAreaId = item.areaId != null ? item.areaId : item.label;

                    SharedPreferences prefs = getSharedPreferences("app_prefs", MODE_PRIVATE);
                    String svgName = prefs.getString("svg_name_" + svgUriString, "");

                    Intent i = new Intent(AreaListActivity.this, MainActivity.class);
                    i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    i.putExtra("navigate_to_network", true);
                    i.putExtra("focus_area_id",       navigateAreaId);
                    i.putExtra("from_area_list",       true);
                    i.putExtra("svg_uri",              svgUriString);
                    i.putExtra("svg_name",             svgName);
                    startActivity(i);
                });
            }
        }

        @Override
        public int getItemCount() { return items.size(); }

        class HeaderVH extends RecyclerView.ViewHolder {
            TextView tv;
            HeaderVH(View v) { super(v); tv = v.findViewById(R.id.tvHeader); }
        }

        class AreaVH extends RecyclerView.ViewHolder {
            TextView  name, count;
            ImageView icon;
            View      statusDot;

            AreaVH(View v) {
                super(v);
                name      = v.findViewById(R.id.tvAreaName);
                count     = v.findViewById(R.id.tvDeviceCount);
                icon      = v.findViewById(R.id.ivAreaIcon);
                statusDot = v.findViewById(R.id.vStatusDot);
            }
        }
    }
}