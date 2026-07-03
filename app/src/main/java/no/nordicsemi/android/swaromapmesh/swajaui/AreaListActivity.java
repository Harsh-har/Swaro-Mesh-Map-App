package no.nordicsemi.android.swaromapmesh.swajaui;

          import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;

/**
 * LEGACY: This activity is replaced by the Flutter AreaListScreen.
 * It is kept as a stub to avoid manifest errors but is no longer used for navigation.
 */
public class AreaListActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // This activity should no longer be reached as callers use FlutterNavigator.
        finish();
    }
}
