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
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import dagger.hilt.android.AndroidEntryPoint;
import no.nordicsemi.android.swaromapmesh.MainActivity;
import no.nordicsemi.android.swaromapmesh.R;

@AndroidEntryPoint
public class AreaListActivity extends AppCompatActivity {

    private static final String TAG         = "AreaListActivity";
    private static final int    TYPE_HEADER = 0;
    private static final int    TYPE_AREA   = 1;
    private RecyclerView      rvAreas;
    private LinearLayout      emptyView;
    private TextView          tvSiteTitle;
    private String            svgUriString;
    private ArrayList<String> areaList;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    // ==================== LIFECYCLE ====================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_area_list);

        rvAreas     = findViewById(R.id.rvAreas);
        emptyView   = findViewById(R.id.emptyView);
        tvSiteTitle = findViewById(R.id.tvSiteTitle);
        svgUriString = getIntent().getStringExtra("svg_uri");
        areaList     = getIntent().getStringArrayListExtra("area_list");

        Log.d(TAG, "Areas received: " + areaList);

        if (areaList == null || areaList.isEmpty()) {
            showEmpty();
            return;
        }

        if (svgUriString != null) {
            Uri    uri  = Uri.parse(svgUriString);
            String path = uri.getLastPathSegment();
            if (path != null) {
                String name = path.replace(".svg", "").replace("_", " ").toUpperCase();
                tvSiteTitle.setText(name);
            }

            SharedPreferences prefs          = getSharedPreferences("app_prefs", MODE_PRIVATE);
            String            savedCountsJson = prefs.getString("saved_counts", null);

            if (savedCountsJson != null) {
                int[] counts = new com.google.gson.Gson().fromJson(savedCountsJson, int[].class);
                showAreas(areaList, counts);
            } else {
                countDevicesPerArea(uri, areaList);
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

    // ==================== SVG ICON LOADER ====================

    private void loadAreaIcon(ImageView imageView, String areaName) {
        String fileName = getSvgFileName(areaName);
        try {
            InputStream    is  = getAssets().open("area_icons/" + fileName);
            SVG            svg = SVG.getFromInputStream(is);
            imageView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            imageView.setImageDrawable(new PictureDrawable(svg.renderToPicture()));
            is.close();
        } catch (Exception e) {
            Log.e(TAG, "SVG load failed: area_icons/" + fileName, e);
            imageView.setImageResource(R.drawable.ic_settings); // fallback
        }
    }

    // ==================== ICON MAPPER ====================
    private String getSvgFileName(String areaId) {
        if (areaId == null) return "Corridor.svg";

        switch (areaId) {
            case "PDRI":        return "Corridor.svg";
            case "VCRI":        return "Wet Kitchen.svg";
            case "BCI":         return "Powder room.svg";
            case "ENGRI":       return "Restaurant Close.svg";
            case "VCRED":       return "Corridor.svg";
            case "VCRACD":      return "Corridor.svg";
            case "VCRPSD_1":    return "Corridor.svg";
            case "VCRPSD_2":    return "Corridor.svg";
            case "VCRPSD_3":    return "Corridor.svg";
            case "VCRSFD_1":    return "Corridor.svg";
            case "VCRSFD_2":    return "Corridor.svg";
            case "VCRRN6AM_3":  return "Corridor.svg";
            case "VCRRN6AM_4":  return "Corridor.svg";
            case "VCRRN6AM_9":  return "Corridor.svg";
            default:            return "Corridor.svg";
        }
    }

    // ==================== DEVICE COUNT ====================

    private void countDevicesPerArea(Uri uri, ArrayList<String> areas) {
        executor.execute(() -> {
            int[] counts = new int[areas.size()];
            try {
                InputStream is;
                String uriString = uri.toString();
                if (uriString.startsWith("file://")) {
                    is = new FileInputStream(new File(uri.getPath()));
                } else {
                    is = getContentResolver().openInputStream(uri);
                }
                if (is != null) {
                    Document doc = parseDocument(is);
                    is.close();
                    if (doc != null) {
                        Element iconsGroup = findElementById(doc.getDocumentElement(), "Icons");
                        if (iconsGroup != null) {
                            for (int i = 0; i < areas.size(); i++) {
                                Element areaEl = findElementById(iconsGroup, areas.get(i));
                                if (areaEl != null) counts[i] = countLeafIcons(areaEl);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Count error", e);
            }
            runOnUiThread(() -> showAreas(areas, counts));
        });
    }

    private int countLeafIcons(Element el) {
        String id = el.getAttribute("id");
        if (!id.isEmpty() && hasDirectRectChild(el) && !hasDirectGChild(el)) return 1;
        int count = 0;
        NodeList children = el.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element) {
                String tag = ((Element) child).getTagName().toLowerCase();
                if (tag.contains(":")) tag = tag.substring(tag.indexOf(':') + 1);
                if ("g".equals(tag)) count += countLeafIcons((Element) child);
            }
        }
        return count;
    }

    private boolean hasDirectRectChild(Element el) {
        NodeList children = el.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element) {
                String tag = ((Element) child).getTagName().toLowerCase();
                if (tag.contains(":")) tag = tag.substring(tag.indexOf(':') + 1);
                if ("rect".equals(tag)) return true;
            }
        }
        return false;
    }

    private boolean hasDirectGChild(Element el) {
        NodeList children = el.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element) {
                String tag = ((Element) child).getTagName().toLowerCase();
                if (tag.contains(":")) tag = tag.substring(tag.indexOf(':') + 1);
                if ("g".equals(tag)) return true;
            }
        }
        return false;
    }

    // ==================== UI ====================

    private void showAreas(ArrayList<String> areas, int[] counts) {
        if (areas.isEmpty()) { showEmpty(); return; }

        List<ListItem> items = new ArrayList<>();
        items.add(new ListItem("AREAS"));
        for (int i = 0; i < areas.size(); i++) {
            items.add(new ListItem(areas.get(i), counts[i], i == areas.size() - 1));
        }

        if (rvAreas != null) {
            rvAreas.setVisibility(View.VISIBLE);
            rvAreas.setLayoutManager(new LinearLayoutManager(this));
            rvAreas.setAdapter(new AreaAdapter(items,
                    (imageView, areaId) -> loadAreaIcon(imageView, areaId),
                    areaId -> {
                        Log.d(TAG, "Area clicked: " + areaId);
                        Intent intent = new Intent(this, MainActivity.class);
                        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                        intent.putExtra("navigate_to_network", true);
                        intent.putExtra("focus_area_id", areaId);
                        intent.putExtra("from_area_list", true);
                        intent.putExtra("svg_uri", svgUriString);
                        startActivity(intent);
                        finish();
                    }
            ));
        }
        if (emptyView != null) emptyView.setVisibility(View.GONE);
    }

    private void showEmpty() {
        if (rvAreas   != null) rvAreas.setVisibility(View.GONE);
        if (emptyView != null) emptyView.setVisibility(View.VISIBLE);
    }

    // ==================== DATA MODEL ====================

    static class ListItem {
        boolean isHeader;
        String  label;
        int     count;
        boolean isLast;

        ListItem(String headerText) {
            this.isHeader = true;
            this.label    = headerText;
        }

        ListItem(String areaId, int count, boolean isLast) {
            this.isHeader = false;
            this.label    = areaId;
            this.count    = count;
            this.isLast   = isLast;
        }
    }

    // ==================== ADAPTER ====================

    interface IconLoader  { void load(ImageView iv, String areaId); }
    interface OnAreaClick { void onClick(String areaId); }

    static class AreaAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        private final List<ListItem> items;
        private final IconLoader     iconLoader;
        private final OnAreaClick    listener;

        AreaAdapter(List<ListItem> items, IconLoader iconLoader, OnAreaClick listener) {
            this.items      = items;
            this.iconLoader = iconLoader;
            this.listener   = listener;
        }

        @Override public int getItemViewType(int position) {
            return items.get(position).isHeader ? TYPE_HEADER : TYPE_AREA;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inf = LayoutInflater.from(parent.getContext());
            if (viewType == TYPE_HEADER)
                return new HeaderVH(inf.inflate(R.layout.item_area_header, parent, false));
            else
                return new AreaVH(inf.inflate(R.layout.item_maparea, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            ListItem item = items.get(position);
            if (holder instanceof HeaderVH) {
                ((HeaderVH) holder).tvHeader.setText(item.label);

            } else if (holder instanceof AreaVH) {
                AreaVH vh = (AreaVH) holder;

                vh.tvAreaName.setText(item.label.replace("_", " "));
                iconLoader.load(vh.ivAreaIcon, item.label);

                if (item.count > 0) {
                    vh.tvDeviceCount.setVisibility(View.VISIBLE);
                    vh.tvDeviceCount.setText(String.valueOf(item.count));
                } else {
                    vh.tvDeviceCount.setVisibility(View.GONE);
                }

                // Divider
                vh.divider.setVisibility(item.isLast ? View.GONE : View.VISIBLE);

                // Click
                vh.itemView.setOnClickListener(v -> listener.onClick(item.label));
            }
        }

        @Override public int getItemCount() { return items.size(); }

        static class HeaderVH extends RecyclerView.ViewHolder {
            TextView tvHeader;
            HeaderVH(View v) { super(v); tvHeader = v.findViewById(R.id.tvHeader); }
        }

        static class AreaVH extends RecyclerView.ViewHolder {
            TextView  tvAreaName;
            TextView  tvDeviceCount;
            ImageView ivAreaIcon;
            View      divider;
            AreaVH(View v) {
                super(v);
                tvAreaName    = v.findViewById(R.id.tvAreaName);
                tvDeviceCount = v.findViewById(R.id.tvDeviceCount);
                ivAreaIcon    = v.findViewById(R.id.ivAreaIcon);
                divider       = v.findViewById(R.id.divider);
            }
        }
    }

    // ==================== XML HELPERS ====================

    private Document parseDocument(InputStream inputStream) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setValidating(false);
            try {
                factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
                factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
                factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            } catch (Exception ignored) {}
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setEntityResolver((pub, sys) ->
                    new org.xml.sax.InputSource(new java.io.StringReader("")));
            Document doc = builder.parse(inputStream);
            doc.getDocumentElement().normalize();
            return doc;
        } catch (Exception e) {
            Log.e(TAG, "parseDocument error", e);
            return null;
        }
    }

    private Element findElementById(Element root, String targetId) {
        if (targetId.equals(root.getAttribute("id"))) return root;
        NodeList children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element) {
                Element found = findElementById((Element) child, targetId);
                if (found != null) return found;
            }
        }
        return null;
    }
}