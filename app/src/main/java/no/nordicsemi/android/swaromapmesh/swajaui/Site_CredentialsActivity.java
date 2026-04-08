package no.nordicsemi.android.swaromapmesh.swajaui;

import android.os.Bundle;
import android.text.method.HideReturnsTransformationMethod;
import android.text.method.PasswordTransformationMethod;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.List;

import no.nordicsemi.android.swaromapmesh.R;

public class Site_CredentialsActivity extends AppCompatActivity {

    // ── Views ──────────────────────────────────────────────────────────────
    private LinearLayout   btnBack;
    private Spinner        spinnerSiteId;
    private EditText       etPassword;
    private ImageView      ivTogglePassword;
    private MaterialButton btnConnect;

    // ── State ──────────────────────────────────────────────────────────────
    private boolean isPasswordVisible = false;
    private String  selectedSite      = "";

    // ── Site list ──────────────────────────────────────────────────────────
    private final List<String> siteList = new ArrayList<String>() {{
        add("Select Site");       // index 0 → treated as hint
        add("Swaja Robotics");
        add("SFTW - D60");
        add("Oota & Windmill");
        add("Peace Garden");
        add("Indira Nagar");
    }};

    // ======================================================================
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_site_credentials);

        bindViews();
        setupSpinner();
        setupPasswordToggle();
        setupClickListeners();
    }

    // ── Bind ───────────────────────────────────────────────────────────────
    private void bindViews() {
        btnBack          = findViewById(R.id.btnBack);
        spinnerSiteId    = findViewById(R.id.spinnerSiteId);
        etPassword       = findViewById(R.id.etPassword);
        ivTogglePassword = findViewById(R.id.ivTogglePassword);
        btnConnect       = findViewById(R.id.btnConnect);
    }

    // ── Spinner ────────────────────────────────────────────────────────────
    private void setupSpinner() {

        ArrayAdapter<String> adapter = new ArrayAdapter<String>(
                this,
                android.R.layout.simple_spinner_item,
                siteList) {

            // Closed row shown in the field
            @NonNull
            @Override
            public View getView(int position, @Nullable View convertView,
                                @NonNull ViewGroup parent) {
                View     view = super.getView(position, convertView, parent);
                TextView tv   = view.findViewById(android.R.id.text1);
                // index-0 = hint (grey), rest = white
                tv.setTextColor(position == 0 ? 0xFF666666 : 0xFFFFFFFF);
                tv.setTextSize(15f);
                return view;
            }

            // Rows shown in the dropdown popup
            @Override
            public View getDropDownView(int position, @Nullable View convertView,
                                        @NonNull ViewGroup parent) {
                View     view = super.getDropDownView(position, convertView, parent);
                TextView tv   = view.findViewById(android.R.id.text1);
                tv.setTextColor(position == 0 ? 0xFF666666 : 0xFFFFFFFF);
                tv.setBackgroundColor(0xFF1A1A1A);
                tv.setPadding(40, 28, 40, 28);
                tv.setTextSize(15f);
                return view;
            }
        };

        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerSiteId.setAdapter(adapter);

        spinnerSiteId.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view,
                                       int position, long id) {
                // position 0 = placeholder → treat as empty
                selectedSite = (position == 0) ? "" : siteList.get(position);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                selectedSite = "";
            }
        });
    }

    // ── Password toggle ────────────────────────────────────────────────────
    private void setupPasswordToggle() {
        ivTogglePassword.setOnClickListener(v -> {
            isPasswordVisible = !isPasswordVisible;

            if (isPasswordVisible) {
                // Show password
                etPassword.setTransformationMethod(
                        HideReturnsTransformationMethod.getInstance());
                ivTogglePassword.setImageResource(R.drawable.eye);
            } else {
                // Hide password
                etPassword.setTransformationMethod(
                        PasswordTransformationMethod.getInstance());
                ivTogglePassword.setImageResource(R.drawable.eye);
            }

            // Move cursor to end so it doesn't jump
            etPassword.setSelection(etPassword.getText().length());
        });
    }

    // ── Click listeners ────────────────────────────────────────────────────
    private void setupClickListeners() {

        // Back
        btnBack.setOnClickListener(v -> onBackPressed());

        // Connect
        btnConnect.setOnClickListener(v -> {

            String password = etPassword.getText().toString().trim();

            // Validation
            if (selectedSite.isEmpty()) {
                Toast.makeText(this,
                        "Please select a site", Toast.LENGTH_SHORT).show();
                return;
            }

            if (password.isEmpty()) {
                etPassword.setError("Password is required");
                etPassword.requestFocus();
                return;
            }

            // ✅ All good — trigger connect
            onConnect(selectedSite, password);
        });
    }

    // ── Connect action ─────────────────────────────────────────────────────
    private void onConnect(String site, String password) {
        // TODO: replace with your ViewModel / API call
        Toast.makeText(this,
                "Connecting to " + site + "…", Toast.LENGTH_SHORT).show();
    }
}