package no.nordicsemi.android.swaromapmesh.swajaui;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
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

    private static final String PREFS_NAME  = "app_prefs";
    private static final String KEY_SVG_URI = "saved_svg_uri";
    private static final String TAG         = "ImportMap";

    private final ActivityResultLauncher<String[]> svgPicker =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) {
                    selectedSvgUri = uri;

                    try {
                        getContentResolver().takePersistableUriPermission(
                                uri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION
                        );
                    } catch (Exception e) {
                        Log.e(TAG, "Permission error", e);
                    }

                    if (btnUpload != null) btnUpload.setEnabled(true);
                    Toast.makeText(this, "SVG selected!", Toast.LENGTH_SHORT).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_import_map);

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String savedUri = prefs.getString(KEY_SVG_URI, null);

        if (savedUri != null) {
            Uri uri = Uri.parse(savedUri);

            ArrayList<String> areaList =
                    SvgParserList.parseAreaIds(getContentResolver(), uri);

            if (!areaList.isEmpty()) {

                String savedName = prefs.getString("svg_name_" + savedUri, "Imported Map");

                Intent intent = new Intent(this, AreaListActivity.class);
                intent.putExtra("svg_uri", savedUri);
                intent.putStringArrayListExtra("area_list", areaList);
                intent.putExtra("svg_display_name", savedName);

                startActivity(intent);
                finish();
                return;
            }
        }

        mViewModel   = new ViewModelProvider(this).get(SharedViewModel.class);
        cardDropZone = findViewById(R.id.cardDropZone);
        btnUpload    = findViewById(R.id.btnUpload);

        if (cardDropZone != null) {
            cardDropZone.setOnClickListener(v ->
                    svgPicker.launch(new String[]{"image/svg+xml"})
            );
        }

        if (btnUpload != null) {
            btnUpload.setOnClickListener(v -> {

                if (selectedSvgUri == null) {
                    Toast.makeText(this, "Select file first", Toast.LENGTH_SHORT).show();
                    return;
                }

                mViewModel.setSvgUri(selectedSvgUri);

                String tempName = getDisplayName(selectedSvgUri);
                final String cleanName = (tempName != null ? tempName : "Imported Map")
                        .replaceAll("(?i)\\.svg$", "")
                        .replaceAll("([a-z])([A-Z])", "$1 $2")
                        .trim();

                new Thread(() -> {

                    ArrayList<String> areaList =
                            SvgParserList.parseAreaIds(getContentResolver(), selectedSvgUri);

                    runOnUiThread(() -> {

                        SharedPreferences prefs1 =
                                getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

                        prefs1.edit()
                                .putString(KEY_SVG_URI, selectedSvgUri.toString())
                                .putString("svg_name_" + selectedSvgUri.toString(), cleanName)
                                .apply();

                        Intent intent = new Intent(this, AreaListActivity.class);
                        intent.putExtra("svg_uri", selectedSvgUri.toString());
                        intent.putStringArrayListExtra("area_list", areaList);
                        intent.putExtra("svg_display_name", cleanName);

                        startActivity(intent);
                        finish();
                    });

                }).start();
            });
        }
    }

    private String getDisplayName(Uri uri) {
        try (android.database.Cursor cursor = getContentResolver().query(
                uri,
                new String[]{OpenableColumns.DISPLAY_NAME},
                null, null, null)) {

            if (cursor != null && cursor.moveToFirst()) {
                int col = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (col >= 0) return cursor.getString(col);
            }
        } catch (Exception e) {
            Log.e(TAG, "Name error", e);
        }
        return null;
    }
}