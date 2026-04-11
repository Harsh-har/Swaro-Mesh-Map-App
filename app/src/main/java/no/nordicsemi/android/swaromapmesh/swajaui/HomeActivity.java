package no.nordicsemi.android.swaromapmesh.swajaui;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;

import no.nordicsemi.android.swaromapmesh.databinding.ActivityHomeBinding;

public class HomeActivity extends AppCompatActivity {

    private ActivityHomeBinding binding;
    private static final String PREFS_NAME = "app_prefs";
    private static final String KEY_SVG_URI = "saved_svg_uri";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Check if map already imported → skip all screens, go directly to AreaListActivity
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String savedUri = prefs.getString(KEY_SVG_URI, null);
        Log.d("HomeActivity", "Saved URI: " + savedUri);

        if (savedUri != null) {
            Uri uri = Uri.parse(savedUri);
            ArrayList<String> areaList = SvgParser.parseAreaIds(getContentResolver(), uri);
            Log.d("HomeActivity", "Area list size: " + areaList.size());

            if (!areaList.isEmpty()) {
                Intent intent = new Intent(this, AreaListActivity.class);
                intent.putExtra("svg_uri", savedUri);
                intent.putStringArrayListExtra("area_list", areaList);
                // Clear back stack completely
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(intent);
                finish();
                return;
            }
        }

        // Normal first-time flow
        binding = ActivityHomeBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.btnGetStarted.setOnClickListener(v -> {
            Intent intent = new Intent(HomeActivity.this, IdentifyActivity.class);
            startActivity(intent);
        });
    }
}