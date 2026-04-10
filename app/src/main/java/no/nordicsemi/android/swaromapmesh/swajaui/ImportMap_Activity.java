package no.nordicsemi.android.swaromapmesh.swajaui;

import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.lifecycle.ViewModelProvider;

import dagger.hilt.android.AndroidEntryPoint;
import no.nordicsemi.android.swaromapmesh.R;
import no.nordicsemi.android.swaromapmesh.viewmodels.SharedViewModel;

@AndroidEntryPoint  // ✅ Add this
public class ImportMap_Activity extends AppCompatActivity {

    private SharedViewModel mViewModel;
    private CardView cardDropZone;
    private Button btnUpload;
    private Uri selectedSvgUri;

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

        mViewModel = new ViewModelProvider(this).get(SharedViewModel.class);

        cardDropZone = findViewById(R.id.cardDropZone);
        btnUpload = findViewById(R.id.btnUpload);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        if (cardDropZone != null) {
            cardDropZone.setOnClickListener(v -> svgPicker.launch("image/svg+xml"));
        }

        if (btnUpload != null) {
            btnUpload.setOnClickListener(v -> {
                if (selectedSvgUri != null) {
                    mViewModel.setSvgUri(selectedSvgUri);
                    Toast.makeText(this, "SVG Map Uploaded!", Toast.LENGTH_SHORT).show();
                    setResult(RESULT_OK);
                    finish();
                } else {
                    Toast.makeText(this, "Select a file first", Toast.LENGTH_SHORT).show();
                }
            });
        }
    }
}