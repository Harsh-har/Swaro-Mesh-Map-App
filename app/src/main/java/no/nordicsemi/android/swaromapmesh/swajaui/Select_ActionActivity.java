package no.nordicsemi.android.swaromapmesh.swajaui;

import android.content.Intent;
import android.os.Bundle;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import no.nordicsemi.android.swaromapmesh.R;
import no.nordicsemi.android.swaromapmesh.databinding.ActivityIdentifyBinding;
import no.nordicsemi.android.swaromapmesh.databinding.ActivitySelectActionBinding;

public class Select_ActionActivity extends AppCompatActivity {

    private ActivitySelectActionBinding binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivitySelectActionBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Get name passed from IdentifyActivity
        String technicianName = getIntent().getStringExtra("technician_name");
        if (technicianName != null && !technicianName.isEmpty()) {
            binding.tvSubtitle.setText("Hi " + technicianName + "! What would you like to do?");
        }

        binding.cardFetchSiteData.setOnClickListener(v -> {
            Intent intent = new Intent(this, Site_CredentialsActivity.class);
            startActivity(intent);
        });

        binding.cardUploadFile.setOnClickListener(v -> {
            Intent intent = new Intent(this, ImportMap_Activity.class);
            startActivity(intent);
        });
    }
}