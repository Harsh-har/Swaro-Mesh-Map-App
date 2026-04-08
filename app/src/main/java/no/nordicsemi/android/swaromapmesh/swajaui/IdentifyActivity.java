package no.nordicsemi.android.swaromapmesh.swajaui;

import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import no.nordicsemi.android.swaromapmesh.databinding.ActivityIdentifyBinding;

public class IdentifyActivity extends AppCompatActivity {

    private ActivityIdentifyBinding binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // ViewBinding init
        binding = ActivityIdentifyBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Button click → Next screen
        binding.btnContinue.setOnClickListener(v -> {
            Intent intent = new Intent(this, Identify_Credentials_Activity.class);
            startActivity(intent);

        });
    }
}