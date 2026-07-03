package no.nordicsemi.android.swaromapmesh;

import android.content.Context;
import androidx.annotation.NonNull;
import io.flutter.embedding.engine.FlutterEngine;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import no.nordicsemi.android.swaromapmesh.viewmodels.SharedViewModel;

import android.content.Intent;
import android.net.Uri;
import java.util.*;
import no.nordicsemi.android.swaromapmesh.swajaui.SvgParserList;
import no.nordicsemi.android.swaromapmesh.viewmodels.ClientServerElementStore;

/**
 * Acts as the bridge between Flutter and the existing Java Mesh logic.
 */
public class MeshMethodChannel implements MethodChannel.MethodCallHandler {
    private static final String CHANNEL = "no.nordicsemi.android.mesh/bridge";
    
    private final Context context;
    private final SharedViewModel viewModel;
    private MethodChannel channel;

    public MeshMethodChannel(Context context, SharedViewModel viewModel) {
        this.context = context;
        this.viewModel = viewModel;
    }

    public void init(@NonNull FlutterEngine flutterEngine) {
        channel = new MethodChannel(flutterEngine.getDartExecutor().getBinaryMessenger(), CHANNEL);
        channel.setMethodCallHandler(this);
    }

    private void handleGetAreaList(MethodCall call, MethodChannel.Result result) {
        String svgUriString = call.argument("svg_uri");
        if (svgUriString == null || svgUriString.isEmpty()) {
            result.error("INVALID_ARGUMENT", "svg_uri is null or empty", null);
            return;
        }

        Uri uri = Uri.parse(svgUriString);
        LinkedHashMap<String, List<String>> areaMap = 
            SvgParserList.parseFloorAreas(context.getContentResolver(), uri);
        
        List<Map<String, Object>> items = new ArrayList<>();
        
        boolean hasMultiFloor = false;
        for (String key : areaMap.keySet()) {
            if (isFloorName(key)) {
                hasMultiFloor = true;
                break;
            }
        }

        if (hasMultiFloor) {
            for (Map.Entry<String, List<String>> entry : areaMap.entrySet()) {
                String floorId = entry.getKey();
                if (!isFloorName(floorId)) continue;

                List<String> areas = entry.getValue();
                items.add(createListItem(formatName(floorId), true, null, null));
                for (String areaId : areas) {
                    List<String> deviceIds = areaMap.get(areaId);
                    items.add(createListItem(formatName(areaId), false, areaId, deviceIds));
                }
            }
        } else {
            for (Map.Entry<String, List<String>> entry : areaMap.entrySet()) {
                String areaId = entry.getKey();
                List<String> deviceIds = entry.getValue();
                if (areaId.equals("Relation") || areaId.equals("Devices") ||
                    areaId.equals("Icons") || areaId.equals("selection_layer")) {
                    continue;
                }
                items.add(createListItem(formatName(areaId), false, areaId, deviceIds));
            }
        }
        result.success(items);
    }

    private void handleNavigateToMap(MethodCall call, MethodChannel.Result result) {
        String areaId = call.argument("areaId");
        String svgUri = call.argument("svg_uri");

        Intent i = new Intent(context, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        i.putExtra("navigate_to_network", true);
        i.putExtra("focus_area_id", areaId);
        i.putExtra("from_area_list", true);
        i.putExtra("svg_uri", svgUri);
        context.startActivity(i);
        result.success(true);
    }

    private Map<String, Object> createListItem(String label, boolean isHeader, String areaId, List<String> deviceIds) {
        Map<String, Object> item = new HashMap<>();
        item.put("label", label);
        item.put("isHeader", isHeader);
        item.put("areaId", areaId);
        item.put("deviceIds", deviceIds != null ? deviceIds : new ArrayList<String>());
        item.put("dotColor", getDotColor(deviceIds));
        return item;
    }

    private int getDotColor(List<String> deviceIds) {
        if (deviceIds == null || deviceIds.isEmpty()) return 0;
        int total = deviceIds.size();
        int provisioned = 0;
        for (String rawId : deviceIds) {
            if (ClientServerElementStore.isProvisioned(rawId)) provisioned++;
        }
        if (provisioned == 0) return 0;
        if (provisioned == total) return 1; // Green
        return 2; // Orange
    }

    private boolean isFloorName(String name) {
        if (name == null) return false;
        return name.contains("Floor") || name.equals("Ground_Floor") ||
                name.equals("First_Floor") || name.equals("Terrace_Floor") ||
                name.endsWith("_Floor");
    }

    private String formatName(String id) {
        if (id == null) return "";
        return id.replace("_", " ").replace("-", " ");
    }

    @Override
    public void onMethodCall(@NonNull MethodCall call, @NonNull MethodChannel.Result result) {
        switch (call.method) {
            case "getAreaList":
                handleGetAreaList(call, result);
                break;
                
            case "navigateToMap":
                handleNavigateToMap(call, result);
                break;

            case "getNetworkNodes":
                // Logic to get nodes from viewModel and return as a List of Maps
                result.success(null); // Replace with actual data
                break;
                
            case "connectToProxy":
                String mac = call.argument("mac");
                // viewModel.connect(mac);
                result.success(true);
                break;
                
            case "sendGenericOnOff":
                int address = call.argument("address");
                boolean state = call.argument("state");
                // viewModel.sendOnOff(address, state);
                result.success(true);
                break;

            case "getSvgData":
                // Return the saved SVG URI and Area List
                result.success(null);
                break;

            default:
                result.notImplemented();
                break;
        }
    }
    
    // Call this from Java when Mesh status changes to update Flutter UI
    public void notifyStatusChanged(String status) {
        if (channel != null) {
            channel.invokeMethod("onStatusChanged", status);
        }
    }
}
