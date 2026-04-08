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
import no.nordicsemi.android.swaromapmesh.databinding.ActivityIdentifyCredentialsBinding;

public class Identify_Credentials_Activity extends AppCompatActivity {

    private ActivityIdentifyCredentialsBinding binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // ViewBinding init
        binding = ActivityIdentifyCredentialsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Button click → Next screen
        binding.btnContinue.setOnClickListener(v -> {
            Intent intent = new Intent(this, Select_ActionActivity.class);
            startActivity(intent);

        });
    }
}