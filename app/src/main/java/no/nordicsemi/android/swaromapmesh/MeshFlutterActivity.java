package no.nordicsemi.android.swaromapmesh;

import androidx.annotation.NonNull;
import androidx.lifecycle.ViewModelProvider;
import io.flutter.embedding.android.FlutterFragmentActivity;
import io.flutter.embedding.engine.FlutterEngine;
import dagger.hilt.android.AndroidEntryPoint;
import no.nordicsemi.android.swaromapmesh.viewmodels.SharedViewModel;

@AndroidEntryPoint
public class MeshFlutterActivity extends FlutterFragmentActivity {
    
    @Override
    public void configureFlutterEngine(@NonNull FlutterEngine flutterEngine) {
        super.configureFlutterEngine(flutterEngine);
        
        SharedViewModel viewModel = new ViewModelProvider(this).get(SharedViewModel.class);
        MeshMethodChannel bridge = new MeshMethodChannel(this, viewModel);
        bridge.init(flutterEngine);
    }
}
