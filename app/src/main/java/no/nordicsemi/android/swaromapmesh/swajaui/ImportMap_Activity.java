package no.nordicsemi.android.swaromapmesh.swajaui;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.lifecycle.ViewModelProvider;

import java.util.ArrayList;

import dagger.hilt.android.AndroidEntryPoint;
import no.nordicsemi.android.swaromapmesh.R;
import no.nordicsemi.android.swaromapmesh.viewmodels.SharedViewModel;

@AndroidEntryPoint
public class ImportMap_Activity extends AppCompatActivity {

    private SharedViewModel mViewModel;
    private CardView cardDropZone;
    private Button btnUpload;
    private Uri selectedSvgUri;
    private static final String PREFS_NAME = "app_prefs";
    private static final String KEY_SVG_URI = "saved_svg_uri";

    private final ActivityResultLauncher<String> svgPicker =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) {
                    selectedSvgUri = uri;
                    if (btnUpload != null) {
                        btnUpload.setEnabled(true);
                    }
                    Toast.makeText(this, "SVG file selected!", Toast.LENGTH_SHORT).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_import_map);

        // If map already imported, go directly to AreaListActivity
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String savedUri = prefs.getString(KEY_SVG_URI, null);
        if (savedUri != null) {
            Uri uri = Uri.parse(savedUri);
            ArrayList<String> areaList = SvgParser.parseAreaIds(getContentResolver(), uri);
            if (!areaList.isEmpty()) {
                Intent intent = new Intent(this, AreaListActivity.class);
                intent.putExtra("svg_uri", savedUri);
                intent.putStringArrayListExtra("area_list", areaList);
                startActivity(intent);
                finish();
                return;
            }
        }

        mViewModel = new ViewModelProvider(this).get(SharedViewModel.class);

        cardDropZone = findViewById(R.id.cardDropZone);
        btnUpload    = findViewById(R.id.btnUpload);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        if (cardDropZone != null) {
            cardDropZone.setOnClickListener(v -> svgPicker.launch("image/svg+xml"));
        }

        if (btnUpload != null) {
            btnUpload.setOnClickListener(v -> {
                if (selectedSvgUri == null) {
                    Toast.makeText(this, "Select a file first", Toast.LENGTH_SHORT).show();
                    return;
                }

                Log.d("ImportMap", "Upload button clicked, URI: " + selectedSvgUri);

                try {
                    getContentResolver().takePersistableUriPermission(
                            selectedSvgUri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                    );
                    Log.d("ImportMap", "URI permission granted");
                } catch (SecurityException e) {
                    Log.e("ImportMap", "Permission error: " + e.getMessage());
                }

                mViewModel.setSvgUri(selectedSvgUri);
                Log.d("ImportMap", "ViewModel URI set");

                btnUpload.setEnabled(false);
                btnUpload.setText("Parsing...");

                ArrayList<String> areaList = SvgParser.parseAreaIds(getContentResolver(), selectedSvgUri);
                Log.d("ImportMap", "Areas parsed: " + areaList.size() + " → " + areaList);

                if (areaList.isEmpty()) {
                    Toast.makeText(this, "No areas found in SVG", Toast.LENGTH_LONG).show();
                    btnUpload.setEnabled(true);
                    btnUpload.setText("Upload to Site");
                    return;
                }

                // Save URI for future launches
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                        .edit()
                        .putString(KEY_SVG_URI, selectedSvgUri.toString())
                        .apply();

                Intent intent = new Intent(this, AreaListActivity.class);
                intent.putExtra("svg_uri", selectedSvgUri.toString());
                intent.putStringArrayListExtra("area_list", areaList);
                startActivity(intent);
                finish();
            });
        }
    }
}