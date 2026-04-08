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

        // ViewBinding init
        binding = ActivitySelectActionBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Button click → Next screen
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