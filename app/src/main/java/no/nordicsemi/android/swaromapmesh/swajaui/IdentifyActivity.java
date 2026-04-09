package no.nordicsemi.android.swaromapmesh.swajaui;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import no.nordicsemi.android.swaromapmesh.databinding.ActivityIdentifyBinding;

public class IdentifyActivity extends AppCompatActivity {

    private ActivityIdentifyBinding binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // ViewBinding init
        binding = ActivityIdentifyBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Set initial disabled state
        setButtonInactive();

        // Back button
        binding.layoutBack.setOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());

        // TextWatcher to monitor both fields
        TextWatcher watcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                updateButtonState();
            }
        };

        binding.etTechnicianName.addTextChangedListener(watcher);
        binding.etTechnicianId.addTextChangedListener(watcher);

        // Continue button click → Next screen
        binding.btnContinue.setOnClickListener(v -> {
            String name = binding.etTechnicianName.getText().toString().trim();
            String id   = binding.etTechnicianId.getText().toString().trim();

            Intent intent = new Intent(this, Select_ActionActivity.class);
            intent.putExtra("technician_name", name);
            intent.putExtra("technician_id", id);
            startActivity(intent);
        });
    }

    private void updateButtonState() {
        String name = binding.etTechnicianName.getText().toString().trim();
        String id   = binding.etTechnicianId.getText().toString().trim();

        if (!name.isEmpty() && !id.isEmpty()) {
            setButtonActive();
        } else {
            setButtonInactive();
        }
    }

    private void setButtonActive() {
        binding.btnContinue.setEnabled(true);
        binding.btnContinue.setTextColor(android.graphics.Color.WHITE);
        binding.btnContinue.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(
                        android.graphics.Color.parseColor("#00A1F1") // Active blue
                )
        );
    }

    private void setButtonInactive() {
        binding.btnContinue.setEnabled(false);
        binding.btnContinue.setTextColor(android.graphics.Color.parseColor("#666666"));
        binding.btnContinue.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(
                        android.graphics.Color.parseColor("#232323") // Inactive dark gray
                )
        );
    }
}