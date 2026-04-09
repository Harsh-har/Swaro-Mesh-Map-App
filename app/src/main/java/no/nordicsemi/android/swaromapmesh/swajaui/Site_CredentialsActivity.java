package no.nordicsemi.android.swaromapmesh.swajaui;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.method.HideReturnsTransformationMethod;
import android.text.method.PasswordTransformationMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.RadioButton;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.Arrays;
import java.util.List;

import no.nordicsemi.android.swaromapmesh.R;
import no.nordicsemi.android.swaromapmesh.databinding.ActivitySiteCredentialsBinding;

public class Site_CredentialsActivity extends AppCompatActivity {

    private ActivitySiteCredentialsBinding binding;
    private boolean isPasswordVisible = false;
    private boolean isDropdownOpen = false;
    private int selectedSiteIndex = -1; // -1 = nothing selected

    // ── Replace this with your real data source ──────────────────────────────
    private final List<String> siteList = Arrays.asList(
            "Swaja Robotics",
            "SFTW - D60",
            "Oota & Windmill",
            "Peace Garden",
            "Indira Nagar",
            "Koramangala Hub",
            "BTM Layout Site"
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivitySiteCredentialsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        setupBackButton();
        setupDropdown();
        setupPasswordField();
        setupTogglePassword();
        setButtonInactive();
        setupConnectButton();
    }

    // ─── Connect Button Click ────────────────────────────────────────────────

    private void setupConnectButton() {
        binding.btnConnect.setOnClickListener(v -> {
            String enteredPassword = binding.etPassword.getText().toString().trim();
            String correctPassword = "123456";

            if (enteredPassword.equals(correctPassword)) {
                hideError();
                // TODO: navigate to next screen
                // Intent intent = new Intent(this, NextActivity.class);
                // startActivity(intent);
            } else {
                showError("Incorrect Site ID or password. Please try again.");
            }
        });
    }

    // ─── Back ────────────────────────────────────────────────────────────────

    private void setupBackButton() {
        binding.layoutBack.setOnClickListener(v ->
                getOnBackPressedDispatcher().onBackPressed()
        );
    }

    // ─── Dropdown ────────────────────────────────────────────────────────────

    private void setupDropdown() {
        SiteDropdownAdapter adapter = new SiteDropdownAdapter();
        binding.listViewSites.setAdapter(adapter);

        // Toggle open/close on trigger tap
        binding.layoutSiteDropdown.setOnClickListener(v -> {
            isDropdownOpen = !isDropdownOpen;
            binding.cardDropdownPanel.setVisibility(isDropdownOpen ? View.VISIBLE : View.GONE);
            binding.imgDropdownArrow.setRotation(isDropdownOpen ? 180f : 0f);
        });

        // Item click → select + collapse
        binding.listViewSites.setOnItemClickListener((parent, view, position, id) -> {
            selectedSiteIndex = position;
            adapter.notifyDataSetChanged();

            // Update trigger text
            binding.tvSelectedSite.setText(siteList.get(position));
            binding.tvSelectedSite.setTextColor(Color.parseColor("#F2F2F2"));

            // Collapse panel
            isDropdownOpen = false;
            binding.cardDropdownPanel.setVisibility(View.GONE);
            binding.imgDropdownArrow.setRotation(0f);

            updateButtonState();
        });
    }

    // ─── Password ────────────────────────────────────────────────────────────

    private void setupPasswordField() {
        binding.etPassword.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                hideError(); // hide error when user starts typing
                updateButtonState();
            }
        });
    }

    private void setupTogglePassword() {
        binding.imgTogglePassword.setOnClickListener(v -> {
            isPasswordVisible = !isPasswordVisible;
            binding.etPassword.setTransformationMethod(
                    isPasswordVisible
                            ? HideReturnsTransformationMethod.getInstance()
                            : PasswordTransformationMethod.getInstance()
            );
            binding.imgTogglePassword.setImageResource(
                    isPasswordVisible ? R.drawable.eye : R.drawable.eye
            );
            binding.etPassword.setSelection(binding.etPassword.getText().length());
        });
    }

    // ─── Button State ─────────────────────────────────────────────────────────

    private void updateButtonState() {
        String password = binding.etPassword.getText().toString().trim();
        boolean ready = selectedSiteIndex != -1 && !password.isEmpty();
        if (ready) setButtonActive(); else setButtonInactive();
    }

    // ─── Error Message ───────────────────────────────────────────────────────

    public void showError(String message) {
        binding.tvErrorMessage.setText(message);
        binding.layoutError.setVisibility(View.VISIBLE);
    }

    public void hideError() {
        binding.layoutError.setVisibility(View.GONE);
    }

    private void setButtonActive() {
        binding.btnConnect.setEnabled(true);
        binding.btnConnect.setTextColor(Color.WHITE);
        binding.btnConnect.setBackgroundTintList(
                ColorStateList.valueOf(Color.parseColor("#00A1F1"))
        );
    }

    private void setButtonInactive() {
        binding.btnConnect.setEnabled(false);
        binding.btnConnect.setTextColor(Color.parseColor("#666666"));
        binding.btnConnect.setBackgroundTintList(
                ColorStateList.valueOf(Color.parseColor("#232323"))
        );
    }

    // ─── Dropdown Adapter ─────────────────────────────────────────────────────

    private class SiteDropdownAdapter extends BaseAdapter {

        @Override
        public int getCount() { return siteList.size(); }

        @Override
        public Object getItem(int position) { return siteList.get(position); }

        @Override
        public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(Site_CredentialsActivity.this)
                        .inflate(R.layout.item_site_dropdown, parent, false);
            }

            TextView tvName   = convertView.findViewById(R.id.tvSiteName);
            RadioButton rb    = convertView.findViewById(R.id.rbSite);

            tvName.setText(siteList.get(position));
            rb.setChecked(position == selectedSiteIndex);

            return convertView;
        }
    }
}