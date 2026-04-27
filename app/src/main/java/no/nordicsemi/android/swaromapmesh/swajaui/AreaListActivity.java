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

@AndroidEntryPoint
public class AreaListActivity extends AppCompatActivity {

    private static final String TAG = "AreaListActivity";

    private RecyclerView rvAreas;
    private LinearLayout emptyView;
    private TextView tvSiteTitle;

    private String svgUriString;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_area_list);

        rvAreas      = findViewById(R.id.rvAreas);
        emptyView    = findViewById(R.id.emptyView);
        tvSiteTitle  = findViewById(R.id.tvSiteTitle);

        svgUriString = getIntent().getStringExtra("svg_uri");

        SharedPreferences prefs = getSharedPreferences("app_prefs", MODE_PRIVATE);
        String siteTitle = prefs.getString(
                "svg_name_" + svgUriString, "Imported Map");
        tvSiteTitle.setText(siteTitle);

        if (svgUriString == null) { showEmpty(); return; }

        Uri uri = Uri.parse(svgUriString);

        // Parse areas on background thread
        executor.execute(() -> {
            LinkedHashMap<String, List<String>> areaMap =
                    SvgParser.parseFloorAreas(getContentResolver(), uri);

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

    // ── Build RecyclerView items (Top-level areas only) ───────────────────────

    private void buildList(LinkedHashMap<String, List<String>> areaMap) {
        List<ListItem> items = new ArrayList<>();

        // Check if this is a multi-floor structure (has floor-like names)
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
            // Multi-floor structure - show floors as headers
            for (Map.Entry<String, List<String>> entry : areaMap.entrySet()) {
                String floorId = entry.getKey();
                List<String> areas = entry.getValue();

                // Floor header
                items.add(new ListItem(formatName(floorId), true, null));

                // Areas under this floor
                for (String areaId : areas) {
                    items.add(new ListItem(formatName(areaId), false, areaId));
                }
            }
        } else {
            // Single floor structure - show areas directly (no headers)
            for (Map.Entry<String, List<String>> entry : areaMap.entrySet()) {
                String areaId = entry.getKey();
                List<String> devices = entry.getValue();

                // Only add the area itself, not its child devices
                // Skip known non-area entries
                if (areaId.equals("Relation") || areaId.equals("Devices") ||
                        areaId.equals("Icons") || areaId.equals("selection_layer")) {
                    continue;
                }

                items.add(new ListItem(formatName(areaId), false, areaId));
                Log.d(TAG, "Added area: " + areaId + " with " + devices.size() + " devices");
            }
        }

        if (items.isEmpty()) {
            showEmpty();
            return;
        }

        rvAreas.setLayoutManager(new LinearLayoutManager(this));
        rvAreas.setAdapter(new AreaAdapter(items));
        rvAreas.setVisibility(View.VISIBLE);
        emptyView.setVisibility(View.GONE);
    }

    /** Format name: "Ground_Floor" → "Ground Floor", "Production_Room" → "Production Room" */
    private String formatName(String id) {
        if (id == null) return "";
        return id.replace("_", " ").replace("-", " ");
    }

    private void showEmpty() {
        rvAreas.setVisibility(View.GONE);
        emptyView.setVisibility(View.VISIBLE);
    }

    // ── Icon loader ───────────────────────────────────────────────────────────

    private void loadAreaIcon(ImageView iv, String areaId) {
        try {
            String file = getIconFileName(areaId);
            InputStream is = getAssets().open("area_icons/" + file);
            SVG svg = SVG.getFromInputStream(is);
            iv.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            iv.setImageDrawable(new PictureDrawable(svg.renderToPicture()));
            is.close();
        } catch (Exception e) {
            // Try default icon
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

    private String getIconFileName(String areaId) {
        if (areaId == null) return "Corridor.svg";

        // Map area names to icons
        String lowerArea = areaId.toLowerCase();

        if (lowerArea.contains("production")) return "Production.svg";
        if (lowerArea.contains("vacuum") || lowerArea.contains("casting")) return "Casting.svg";
        if (lowerArea.contains("engineering")) return "Engineering.svg";
        if (lowerArea.contains("recreational")) return "Recreation.svg";
        if (lowerArea.contains("account") || lowerArea.contains("department")) return "Accounts.svg";
        if (lowerArea.contains("3d") || lowerArea.contains("printing")) return "3DPrinting.svg";
        if (lowerArea.contains("washroom") || lowerArea.contains("bathroom")) return "Washroom.svg";
        if (lowerArea.contains("entrance")) return "Entrance.svg";
        if (lowerArea.contains("kitchen")) return "Kitchen.svg";
        if (lowerArea.contains("balcony")) return "Balcony.svg";
        if (lowerArea.contains("stairs")) return "Stairs.svg";

        return "Corridor.svg";
    }

    // ── Model ─────────────────────────────────────────────────────────────────

    static class ListItem {
        final String label;
        final boolean isHeader;      // true = floor header (for multi-floor), false = area
        final String areaId;         // actual area ID for navigation

        ListItem(String label, boolean isHeader, String areaId) {
            this.label = label;
            this.isHeader = isHeader;
            this.areaId = areaId;
        }
    }

    // ── Adapter ───────────────────────────────────────────────────────────────

    class AreaAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        final List<ListItem> items;

        AreaAdapter(List<ListItem> items) { this.items = items; }

        @Override
        public int getItemViewType(int pos) {
            return items.get(pos).isHeader ? 0 : 1;
        }

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
        public void onBindViewHolder(
                @NonNull RecyclerView.ViewHolder holder, int pos) {

            ListItem item = items.get(pos);

            if (holder instanceof HeaderVH) {
                // Floor header row (only for multi-floor)
                ((HeaderVH) holder).tv.setText(item.label);

            } else {
                // Area row
                AreaVH vh = (AreaVH) holder;
                vh.name.setText(item.label);
                vh.count.setText("");  // Count hidden
                loadAreaIcon(vh.icon, item.label);

                vh.itemView.setOnClickListener(v -> {
                    String navigateAreaId = item.areaId != null ? item.areaId : item.label;

                    SharedPreferences prefs = getSharedPreferences("app_prefs", MODE_PRIVATE);
                    String svgName = prefs.getString("svg_name_" + svgUriString, "");

                    Intent i = new Intent(AreaListActivity.this, MainActivity.class);
                    i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    i.putExtra("navigate_to_network", true);
                    i.putExtra("focus_area_id", navigateAreaId);
                    i.putExtra("from_area_list", true);
                    i.putExtra("svg_uri", svgUriString);
                    i.putExtra("svg_name", svgName);
                    startActivity(i);
                });
            }
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        // ViewHolders
        class HeaderVH extends RecyclerView.ViewHolder {
            TextView tv;
            HeaderVH(View v) {
                super(v);
                tv = v.findViewById(R.id.tvHeader);
            }
        }

        class AreaVH extends RecyclerView.ViewHolder {
            TextView name, count;
            ImageView icon;
            AreaVH(View v) {
                super(v);
                name = v.findViewById(R.id.tvAreaName);
                count = v.findViewById(R.id.tvDeviceCount);
                icon = v.findViewById(R.id.ivAreaIcon);
            }
        }
    }
}