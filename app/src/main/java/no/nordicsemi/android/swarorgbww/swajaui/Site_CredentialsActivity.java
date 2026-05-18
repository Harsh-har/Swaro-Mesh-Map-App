//package no.nordicsemi.android.swaromapmesh.swajaui;
//
//import android.content.Intent;
//import android.content.res.ColorStateList;
//import android.graphics.Color;
//import android.os.Bundle;
//import android.text.Editable;
//import android.text.TextWatcher;
//import android.text.method.HideReturnsTransformationMethod;
//import android.text.method.PasswordTransformationMethod;
//import android.view.LayoutInflater;
//import android.view.View;
//import android.view.ViewGroup;
//import android.widget.BaseAdapter;
//import android.widget.RadioButton;
//import android.widget.TextView;
//
//import androidx.annotation.NonNull;
//import androidx.appcompat.app.AppCompatActivity;
//
//import com.google.firebase.firestore.DocumentSnapshot;
//import com.google.firebase.firestore.FirebaseFirestore;
//
//import java.util.ArrayList;
//import java.util.List;
//
//import no.nordicsemi.android.swaromapmesh.R;
//import no.nordicsemi.android.swaromapmesh.databinding.ActivitySiteCredentialsBinding;
//
//public class Site_CredentialsActivity extends AppCompatActivity {
//
//    private ActivitySiteCredentialsBinding binding;
//
//    private boolean isPasswordVisible = false;
//    private boolean isDropdownOpen = false;
//    private int selectedSiteIndex = -1;
//
//    private List<SiteModel> siteList = new ArrayList<>();
//    private FirebaseFirestore db;
//
//    @Override
//    protected void onCreate(Bundle savedInstanceState) {
//        super.onCreate(savedInstanceState);
//        binding = ActivitySiteCredentialsBinding.inflate(getLayoutInflater());
//        setContentView(binding.getRoot());
//
//        db = FirebaseFirestore.getInstance();
//
//        setupBackButton();
//        setupDropdown();
//        setupPasswordField();
//        setupTogglePassword();
//        setupConnectButton();
//
//        setButtonInactive();
//
//        loadSitesFromFirebase();
//    }
//
//    // 🔥 LOAD SITES FROM FIRESTORE
//    private void loadSitesFromFirebase() {
//
//        db.collection("sites")
//                .get()
//                .addOnSuccessListener(queryDocumentSnapshots -> {
//
//                    siteList.clear();
//
//                    for (DocumentSnapshot doc : queryDocumentSnapshots) {
//
//                        SiteModel site = new SiteModel();
//                        site.id = doc.getId();
//                        site.siteName = doc.getString("siteName");
//                        site.password = doc.getString("password");
//                        site.svgData = doc.getString("svgData");
//
//                        siteList.add(site);
//                    }
//
//                    ((BaseAdapter) binding.listViewSites.getAdapter()).notifyDataSetChanged();
//                })
//                .addOnFailureListener(e -> {
//                    showError("Failed to load sites");
//                });
//    }
//
//    // 🔙 BACK BUTTON
//    private void setupBackButton() {
//        binding.layoutBack.setOnClickListener(v ->
//                getOnBackPressedDispatcher().onBackPressed()
//        );
//    }
//
//    // 📂 DROPDOWN
//    private void setupDropdown() {
//
//        SiteDropdownAdapter adapter = new SiteDropdownAdapter();
//        binding.listViewSites.setAdapter(adapter);
//
//        binding.layoutSiteDropdown.setOnClickListener(v -> {
//            isDropdownOpen = !isDropdownOpen;
//            binding.cardDropdownPanel.setVisibility(isDropdownOpen ? View.VISIBLE : View.GONE);
//            binding.imgDropdownArrow.setRotation(isDropdownOpen ? 180f : 0f);
//        });
//
//        binding.listViewSites.setOnItemClickListener((parent, view, position, id) -> {
//
//            selectedSiteIndex = position;
//            adapter.notifyDataSetChanged();
//
//            binding.tvSelectedSite.setText(siteList.get(position).siteName);
//            binding.tvSelectedSite.setTextColor(Color.parseColor("#F2F2F2"));
//
//            isDropdownOpen = false;
//            binding.cardDropdownPanel.setVisibility(View.GONE);
//            binding.imgDropdownArrow.setRotation(0f);
//
//            updateButtonState();
//        });
//    }
//
//    // 🔐 PASSWORD FIELD
//    private void setupPasswordField() {
//        binding.etPassword.addTextChangedListener(new TextWatcher() {
//            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
//            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
//            @Override public void afterTextChanged(Editable s) {
//                hideError();
//                updateButtonState();
//            }
//        });
//    }
//
//    private void setupTogglePassword() {
//        binding.imgTogglePassword.setOnClickListener(v -> {
//
//            isPasswordVisible = !isPasswordVisible;
//
//            binding.etPassword.setTransformationMethod(
//                    isPasswordVisible
//                            ? HideReturnsTransformationMethod.getInstance()
//                            : PasswordTransformationMethod.getInstance()
//            );
//
//            binding.etPassword.setSelection(binding.etPassword.getText().length());
//        });
//    }
//
//    // 🔥 CONNECT BUTTON LOGIC
//    private void setupConnectButton() {
//        binding.btnConnect.setOnClickListener(v -> {
//
//            if (selectedSiteIndex == -1) {
//                showError("Please select a site");
//                return;
//            }
//
//            String enteredPassword = binding.etPassword.getText().toString().trim();
//
//            SiteModel selectedSite = siteList.get(selectedSiteIndex);
//
//            if (enteredPassword.equals(selectedSite.password)) {
//
//                hideError();
//
//                Intent intent = new Intent(this, AreaListActivity.class);
//                intent.putExtra("SITE_ID", selectedSite.id);
//                intent.putExtra("SITE_NAME", selectedSite.siteName);
//                intent.putExtra("SVG_DATA", selectedSite.svgData);
//
//                startActivity(intent);
//
//            } else {
//                showError("Incorrect password");
//            }
//        });
//    }
//
//    // 🎯 BUTTON STATE
//    private void updateButtonState() {
//        String password = binding.etPassword.getText().toString().trim();
//        boolean ready = selectedSiteIndex != -1 && !password.isEmpty();
//
//        if (ready) setButtonActive();
//        else setButtonInactive();
//    }
//
//    private void setButtonActive() {
//        binding.btnConnect.setEnabled(true);
//        binding.btnConnect.setTextColor(Color.WHITE);
//        binding.btnConnect.setBackgroundTintList(
//                ColorStateList.valueOf(Color.parseColor("#00A1F1"))
//        );
//    }
//
//    private void setButtonInactive() {
//        binding.btnConnect.setEnabled(false);
//        binding.btnConnect.setTextColor(Color.parseColor("#666666"));
//        binding.btnConnect.setBackgroundTintList(
//                ColorStateList.valueOf(Color.parseColor("#232323"))
//        );
//    }
//
//    // ❌ ERROR
//    public void showError(String message) {
//        binding.tvErrorMessage.setText(message);
//        binding.layoutError.setVisibility(View.VISIBLE);
//    }
//
//    public void hideError() {
//        binding.layoutError.setVisibility(View.GONE);
//    }
//
//    // 📋 ADAPTER
//    private class SiteDropdownAdapter extends BaseAdapter {
//
//        @Override
//        public int getCount() {
//            return siteList.size();
//        }
//
//        @Override
//        public Object getItem(int position) {
//            return siteList.get(position);
//        }
//
//        @Override
//        public long getItemId(int position) {
//            return position;
//        }
//
//        @Override
//        public View getView(int position, View convertView, ViewGroup parent) {
//
//            if (convertView == null) {
//                convertView = LayoutInflater.from(Site_CredentialsActivity.this)
//                        .inflate(R.layout.item_site_dropdown, parent, false);
//            }
//
//            TextView tvName = convertView.findViewById(R.id.tvSiteName);
//            RadioButton rb = convertView.findViewById(R.id.rbSite);
//
//            tvName.setText(siteList.get(position).siteName);
//            rb.setChecked(position == selectedSiteIndex);
//
//            return convertView;
//        }
//    }
//
//    // 📦 MODEL CLASS
//    public static class SiteModel {
//        public String id;
//        public String siteName;
//        public String password;
//        public String svgData;
//
//        public SiteModel() {}
//    }
//}