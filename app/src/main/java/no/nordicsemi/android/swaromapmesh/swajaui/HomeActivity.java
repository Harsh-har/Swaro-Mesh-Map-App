package no.nordicsemi.android.swaromapmesh.swajaui;

import android.content.Intent;
import android.os.Bundle;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;

import no.nordicsemi.android.swaromapmesh.databinding.ActivityHomeBinding;

public class HomeActivity extends AppCompatActivity {

    private ActivityHomeBinding binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityHomeBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Button Click → Move to Next Screen
        binding.btnGetStarted.setOnClickListener(v -> {
            Intent intent = new Intent(HomeActivity.this, IdentifyActivity.class);
            startActivity(intent);
        });
    }
}