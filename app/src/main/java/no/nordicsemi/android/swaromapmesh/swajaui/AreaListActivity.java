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
import com.google.gson.Gson;

import org.w3c.dom.*;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.xml.parsers.*;

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
    private ArrayList<String> areaList;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    // ==================== LIFECYCLE ====================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_area_list);

        rvAreas = findViewById(R.id.rvAreas);
        emptyView = findViewById(R.id.emptyView);
        tvSiteTitle = findViewById(R.id.tvSiteTitle);

        svgUriString = getIntent().getStringExtra("svg_uri");
        areaList = getIntent().getStringArrayListExtra("area_list");

        SharedPreferences prefs = getSharedPreferences("app_prefs", MODE_PRIVATE);

        // ==================== ✅ SITE TITLE FIX ====================

        String uri = svgUriString != null ? svgUriString : prefs.getString("saved_svg_uri", null);

        String siteTitle = null;
        if (uri != null) {
            siteTitle = prefs.getString("svg_name_" + uri, null);
        }

        if (siteTitle == null) siteTitle = "Imported Map";

        tvSiteTitle.setText(siteTitle);
        Log.d(TAG, "Site title: " + siteTitle);

        // ==========================================================

        Log.d(TAG, "Areas received: " + areaList);

        if (areaList == null || areaList.isEmpty()) {
            showEmpty();
            return;
        }

        if (svgUriString != null) {
            Uri uriObj = Uri.parse(svgUriString);

            String savedCountsJson = prefs.getString("saved_counts_" + uri, null);

            if (savedCountsJson != null) {
                int[] counts = new Gson().fromJson(savedCountsJson, int[].class);
                showAreas(areaList, counts);
            } else {
                countDevicesPerArea(uriObj, areaList, uri);
            }
        } else {
            showAreas(areaList, new int[areaList.size()]);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    // ==================== SVG ICON ====================

    private void loadAreaIcon(ImageView imageView, String areaName) {
        try {
            InputStream is = getAssets().open("area_icons/" + getSvgFileName(areaName));
            SVG svg = SVG.getFromInputStream(is);
            imageView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            imageView.setImageDrawable(new PictureDrawable(svg.renderToPicture()));
            is.close();
        } catch (Exception e) {
            imageView.setImageResource(R.drawable.ic_settings);
        }
    }

    private String getSvgFileName(String areaId) {
        if (areaId == null) return "Corridor.svg";
        switch (areaId) {
            case "PDRI": return "Corridor.svg";
            case "Kitchen": return "Wet Kitchen.svg";
            case "Vaccum Casting Room": return "Powder room.svg";
            case "Engineering Room": return "Restaurant Close.svg";
            default: return "Corridor.svg";
        }
    }

    // ==================== DEVICE COUNT ====================

    private void countDevicesPerArea(Uri uri, ArrayList<String> areas, String key) {
        executor.execute(() -> {
            int[] counts = new int[areas.size()];

            try {
                InputStream is = uri.toString().startsWith("file://")
                        ? new FileInputStream(new File(uri.getPath()))
                        : getContentResolver().openInputStream(uri);

                if (is != null) {
                    Document doc = parseDocument(is);
                    is.close();

                    if (doc != null) {
                        Element icons = findElementById(doc.getDocumentElement(), "Icons");

                        if (icons != null) {
                            for (int i = 0; i < areas.size(); i++) {
                                Element el = findElementById(icons, areas.get(i));
                                if (el != null) counts[i] = countLeafIcons(el);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Count error", e);
            }

            // ✅ SAVE COUNTS PER SVG
            SharedPreferences prefs = getSharedPreferences("app_prefs", MODE_PRIVATE);
            prefs.edit().putString("saved_counts_" + key, new Gson().toJson(counts)).apply();

            runOnUiThread(() -> showAreas(areas, counts));
        });
    }

    private int countLeafIcons(Element el) {
        if (el.getAttribute("id") != null && hasRect(el) && !hasGroup(el)) return 1;

        int count = 0;
        NodeList children = el.getChildNodes();

        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element) {
                count += countLeafIcons((Element) children.item(i));
            }
        }
        return count;
    }

    private boolean hasRect(Element el) {
        NodeList children = el.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element) {
                if (((Element) children.item(i)).getTagName().toLowerCase().contains("rect"))
                    return true;
            }
        }
        return false;
    }

    private boolean hasGroup(Element el) {
        NodeList children = el.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element) {
                if (((Element) children.item(i)).getTagName().toLowerCase().contains("g"))
                    return true;
            }
        }
        return false;
    }

    // ==================== UI ====================

    private void showAreas(ArrayList<String> areas, int[] counts) {
        List<ListItem> items = new ArrayList<>();
        items.add(new ListItem("AREAS"));

        for (int i = 0; i < areas.size(); i++) {
            items.add(new ListItem(areas.get(i), counts[i], i == areas.size() - 1));
        }

        rvAreas.setLayoutManager(new LinearLayoutManager(this));
        rvAreas.setAdapter(new AreaAdapter(items));

        rvAreas.setVisibility(View.VISIBLE);
        emptyView.setVisibility(View.GONE);
    }

    private void showEmpty() {
        rvAreas.setVisibility(View.GONE);
        emptyView.setVisibility(View.VISIBLE);
    }

    // ==================== MODEL ====================

    static class ListItem {
        boolean isHeader;
        String label;
        int count;
        boolean isLast;

        ListItem(String header) {
            isHeader = true;
            label = header;
        }

        ListItem(String label, int count, boolean isLast) {
            this.label = label;
            this.count = count;
            this.isLast = isLast;
        }
    }

    // ==================== ADAPTER ====================

    class AreaAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        List<ListItem> items;

        AreaAdapter(List<ListItem> items) {
            this.items = items;
        }

        @Override public int getItemViewType(int pos) {
            return items.get(pos).isHeader ? 0 : 1;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup p, int type) {
            LayoutInflater inf = LayoutInflater.from(p.getContext());
            return type == 0
                    ? new HeaderVH(inf.inflate(R.layout.item_area_header, p, false))
                    : new AreaVH(inf.inflate(R.layout.item_maparea, p, false));
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int pos) {
            ListItem item = items.get(pos);

            if (h instanceof HeaderVH) {
                ((HeaderVH) h).tv.setText(item.label);
            } else {
                AreaVH vh = (AreaVH) h;

                vh.name.setText(item.label.replace("_", " "));
                vh.count.setText(item.count > 0 ? String.valueOf(item.count) : "");

                loadAreaIcon(vh.icon, item.label);

                vh.itemView.setOnClickListener(v -> {
                    Log.d(TAG, "Area clicked: " + item.label);

                    Intent i = new Intent(AreaListActivity.this, MainActivity.class);

                    // ✅ IMPORTANT FIX
                    i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);

                    i.putExtra("navigate_to_network", true);
                    i.putExtra("focus_area_id", item.label);
                    i.putExtra("from_area_list", true);
                    i.putExtra("svg_uri", svgUriString);

                    startActivity(i);
                });
            }
        }

        @Override public int getItemCount() { return items.size(); }

        class HeaderVH extends RecyclerView.ViewHolder {
            TextView tv;
            HeaderVH(View v) { super(v); tv = v.findViewById(R.id.tvHeader); }
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

    // ==================== XML ====================

    private Document parseDocument(InputStream is) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            DocumentBuilder b = f.newDocumentBuilder();
            return b.parse(is);
        } catch (Exception e) {
            return null;
        }
    }

    private Element findElementById(Element root, String id) {
        if (id.equals(root.getAttribute("id"))) return root;

        NodeList children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element) {
                Element found = findElementById((Element) children.item(i), id);
                if (found != null) return found;
            }
        }
        return null;
    }

}