package no.nordicsemi.android.swaromapmesh;

import android.content.Context;
import android.content.Intent;
import io.flutter.embedding.android.FlutterActivity;
import io.flutter.embedding.engine.FlutterEngine;
import io.flutter.embedding.engine.FlutterEngineCache;
import io.flutter.embedding.engine.dart.DartExecutor;
import no.nordicsemi.android.swaromapmesh.viewmodels.SharedViewModel;

public class FlutterNavigator {
    public static final String ENGINE_ID = "mesh_engine";

    public static void init(Context context, SharedViewModel viewModel) {
        // Instantiate a FlutterEngine.
        FlutterEngine flutterEngine = new FlutterEngine(context);

        // Configure the bridge
        MeshMethodChannel bridge = new MeshMethodChannel(context, viewModel);
        bridge.init(flutterEngine);

        // Start executing Dart code to pre-warm the FlutterEngine.
        flutterEngine.getDartExecutor().executeDartEntrypoint(
            DartExecutor.DartEntrypoint.createDefault()
        );

        // Cache the FlutterEngine to be used by FlutterActivity.
        FlutterEngineCache
            .getInstance()
            .put(ENGINE_ID, flutterEngine);
    }

    public static void navigateToAreaList(Context context, String svgUri, String siteTitle) {
        Intent intent = FlutterActivity
                .withNewEngine()
                .initialRoute("areaList?svg_uri=" + svgUri + "&site_title=" + siteTitle)
                .build(context);
        intent.setClass(context, MeshFlutterActivity.class);
        context.startActivity(intent);
    }
}
