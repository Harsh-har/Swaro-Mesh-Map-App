package no.nordicsemi.android.swarorgbww.swajaui;

import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import no.nordicsemi.android.swarorgbww.R;
import no.nordicsemi.android.swarorgbww.databinding.ActivityIdentifyBinding;
import no.nordicsemi.android.swarorgbww.databinding.ActivitySelectActionBinding;

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


        }

        // Continue button click → Next screen
        binding.btnBack.setOnClickListener(v -> {
            Intent intent = new Intent(this, IdentifyActivity.class);
            startActivity(intent);
        });
        binding.cardFetchSiteData.setOnClickListener(v -> {
            Intent intent = new Intent(this,QRScannerActivity.class);
            startActivity(intent);
        });

        binding.cardUploadFile.setOnClickListener(v -> {
            Intent intent = new Intent(this, ImportMap_Activity.class);
            startActivity(intent);
        });
    }
}