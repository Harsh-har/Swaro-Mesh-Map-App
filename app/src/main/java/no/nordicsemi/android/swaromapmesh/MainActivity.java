package no.nordicsemi.android.swaromapmesh;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;

import android.graphics.Color;
import android.graphics.drawable.Drawable;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.navigation.NavigationBarView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import dagger.hilt.android.AndroidEntryPoint;
import no.nordicsemi.android.swaromapmesh.databinding.ActivityMainBinding;
import no.nordicsemi.android.swaromapmesh.swajaui.SvgParserList;
import no.nordicsemi.android.swaromapmesh.viewmodels.SharedViewModel;

@AndroidEntryPoint
public class MainActivity extends AppCompatActivity implements
        NavigationBarView.OnItemSelectedListener,
        NavigationBarView.OnItemReselectedListener {

    private static final String TAG              = "MainActivity";
    private static final String CURRENT_FRAGMENT = "CURRENT_FRAGMENT";
    private static final int    PERMISSION_REQUEST_CODE = 101;

    private SharedViewModel mViewModel;

    private NetworkFragment       mNetworkFragment;
    private Fragment              mSettingsFragment;
    private ActivityMainBinding   binding;
    private BottomNavigationView  bottomNavigationView;

    // ✅ Bluetooth enable launcher
    private final ActivityResultLauncher<Intent> enableBluetooth =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == RESULT_OK) {
                            Log.d(TAG, "Bluetooth enabled by user");
                        } else {
                            Log.w(TAG, "Bluetooth enable refused by user");
                        }
                    });

    // ==================== LIFECYCLE ====================

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        setTheme(R.style.AppTheme);
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);

        // ✅ Step 1: Permissions pehle maango
        checkAndRequestPermissions();

        // ✅ Step 2: Bluetooth on karne ko kaho agar band hai
        requestBluetoothEnable();

        mViewModel = new ViewModelProvider(this).get(SharedViewModel.class);

        ensureOfficeSvgExists();

        boolean fromAreaList = getIntent().getBooleanExtra("from_area_list", false);

        if (!fromAreaList) {
            SharedPreferences prefs = getSharedPreferences("app_prefs", MODE_PRIVATE);
            String savedUri = prefs.getString("saved_svg_uri", null);

            if (savedUri == null) {
                Intent intent = new Intent(this,
                        no.nordicsemi.android.swaromapmesh.swajaui.HomeActivity.class);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(intent);
                finish();
                return;
            }

            // ✅ Saved areas directly lo — no HTTP call
            java.util.Set<String> savedAreaSet = prefs.getStringSet("saved_area_list", null);

            if (savedAreaSet != null && !savedAreaSet.isEmpty()) {
                // ✅ Instant — no network needed
                ArrayList<String> areaList = new ArrayList<>(savedAreaSet);
                Intent intent = new Intent(this,
                        no.nordicsemi.android.swaromapmesh.swajaui.AreaListActivity.class);
                intent.putExtra("svg_uri", savedUri);
                intent.putStringArrayListExtra("area_list", areaList);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(intent);
                finish();
                return;
            }

            if (!savedUri.startsWith("http://") && !savedUri.startsWith("https://")) {
                Uri uri = Uri.parse(savedUri);
                ArrayList<String> areaList = SvgParserList
                        .parseAreaIds(getContentResolver(), uri);
                if (!areaList.isEmpty()) {
                    Intent intent = new Intent(this,
                            no.nordicsemi.android.swaromapmesh.swajaui.AreaListActivity.class);
                    intent.putExtra("svg_uri", savedUri);
                    intent.putStringArrayListExtra("area_list", areaList);
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(intent);
                    finish();
                    return;
                }
            }

            getSharedPreferences("app_prefs", MODE_PRIVATE)
                    .edit().remove("saved_svg_uri").remove("saved_area_list").apply();
            Intent intent = new Intent(this,
                    no.nordicsemi.android.swaromapmesh.swajaui.HomeActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
            return;
        }

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        setSupportActionBar(binding.toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.app_name);
        }

        mNetworkFragment       = (NetworkFragment)       getSupportFragmentManager().findFragmentById(R.id.fragment_network);
        mSettingsFragment      =                         getSupportFragmentManager().findFragmentById(R.id.fragment_settings);

        bottomNavigationView = findViewById(R.id.bottom_navigation_view);
        bottomNavigationView.setOnItemSelectedListener(this);
        bottomNavigationView.setOnItemReselectedListener(this);

        if (savedInstanceState == null) {
            bottomNavigationView.setSelectedItemId(R.id.action_network);
            onNavigationItemSelected(
                    bottomNavigationView.getMenu().findItem(R.id.action_network));
        } else {
            int selected = savedInstanceState.getInt(CURRENT_FRAGMENT, R.id.action_network);
            bottomNavigationView.setSelectedItemId(selected);
            onNavigationItemSelected(
                    bottomNavigationView.getMenu().findItem(selected));
        }

        mViewModel.isConnectedToProxy().observe(this, connected -> invalidateOptionsMenu());
        mViewModel.getMqttConnected().observe(this, connected -> invalidateOptionsMenu());

        handleNavigationIntent(getIntent());
    }
    // ==================== NEW INTENT (SINGLE_TOP) ====================

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleNavigationIntent(intent);
    }

    private void handleNavigationIntent(Intent intent) {
        if (intent == null) return;

        boolean navigateToNetwork = intent.getBooleanExtra("navigate_to_network", false);
        String  focusAreaId       = intent.getStringExtra("focus_area_id");
        String  svgUriString      = intent.getStringExtra("svg_uri");

        // ✅ YE LOG ADD KARO
        Log.d(TAG, "handleNavigationIntent: svgUri=" + svgUriString
                + " focusAreaId=" + focusAreaId);

        if (svgUriString != null && !svgUriString.isEmpty()) {
            Uri svgUri = Uri.parse(svgUriString);
            mViewModel.setSvgUri(svgUri);
            Log.d(TAG, "✅ setSvgUri called: " + svgUri);
        } else {
            Log.w(TAG, "⚠️ svg_uri is NULL in intent");
        }

        if (navigateToNetwork && bottomNavigationView != null) {
            bottomNavigationView.setSelectedItemId(R.id.action_network);
            onNavigationItemSelected(
                    bottomNavigationView.getMenu().findItem(R.id.action_network));
        }

        if (focusAreaId != null && !focusAreaId.isEmpty()) {
            mViewModel.setFocusAreaId(focusAreaId);
            Log.d(TAG, "setFocusAreaId: " + focusAreaId);
            intent.removeExtra("focus_area_id");
        }
    }
    // ==================== SAVE STATE ====================

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(CURRENT_FRAGMENT, bottomNavigationView.getSelectedItemId());
    }

    // ==================== MENU ====================

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        Boolean isConnected = mViewModel.isConnectedToProxy().getValue();
        getMenuInflater().inflate(
                isConnected != null && isConnected
                        ? R.menu.menu_connect_icon
                        : R.menu.menu_disconnect_icon,
                menu);

        MenuItem item;
        if (isConnected == null || !isConnected) {
            item = menu.findItem(R.id.action_disconnection_state);
            if (item != null) {
                View view = findViewById(item.getItemId());
                if (view != null) {
                    Animation blink = AnimationUtils.loadAnimation(this, R.anim.blink);
                    view.startAnimation(blink);
                }
            }
            MenuItem connectTextItem = menu.findItem(R.id.action_connect_proxy_text);
            if (connectTextItem != null) {
                View view = findViewById(connectTextItem.getItemId());
                if (view != null) {
                    Animation blink = AnimationUtils.loadAnimation(this, R.anim.blink);
                    view.startAnimation(blink);
                }
            }
        } else {
            item = menu.findItem(R.id.action_connection_state);
            if (item != null) {
                View view = findViewById(item.getItemId());
                if (view != null) view.clearAnimation();
            }
            MenuItem disconnectTextItem = menu.findItem(R.id.action_disconnect_proxy_text);
            if (disconnectTextItem != null) {
                View view = findViewById(disconnectTextItem.getItemId());
                if (view != null) view.clearAnimation();
            }
        }

        MenuItem mqttItem = menu.findItem(R.id.action_mqtt_status);
        if (mqttItem != null) {
            // Only show in Network fragment
            mqttItem.setVisible(bottomNavigationView.getSelectedItemId() == R.id.action_network);

            Boolean mqttConnected = mViewModel.getMqttConnected().getValue();
            int color = (mqttConnected != null && mqttConnected)
                    ? ContextCompat.getColor(this, android.R.color.holo_green_dark)
                    : ContextCompat.getColor(this, android.R.color.holo_red_dark);

            Drawable icon = ContextCompat.getDrawable(this, R.drawable.circle_dot);
            if (icon != null) {
                Drawable wrappedIcon = DrawableCompat.wrap(icon.mutate());
                DrawableCompat.setTint(wrappedIcon, color);
                mqttItem.setIcon(wrappedIcon);
            }
            Log.d(TAG, "MQTT Menu updated: connected=" + mqttConnected);
        }

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_connection_state || id == R.id.action_disconnect_proxy_text) {
            mViewModel.disconnect();
            return true;
        } else if (id == R.id.action_disconnection_state || id == R.id.action_connect_proxy_text) {
            mViewModel.navigateToScannerActivity(this, false);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // ==================== NAVIGATION ====================

    @Override
    public boolean onNavigationItemSelected(@NonNull MenuItem item) {
        FragmentTransaction ft = getSupportFragmentManager().beginTransaction();

        if (item.getItemId() == R.id.action_network) {
            ft.show(mNetworkFragment)
                    .hide(mSettingsFragment);
        } else if (item.getItemId() == R.id.action_settings) {
            ft.hide(mNetworkFragment)
                    .show(mSettingsFragment);
        }

        ft.commit();
        invalidateOptionsMenu();
        return true;
    }

    @Override
    public void onNavigationItemReselected(@NonNull MenuItem item) {
        // No-op
    }
    @Override
    public void onBackPressed() {
        // Get the currently visible fragment
        Fragment currentFragment = null;

        if (mNetworkFragment != null && mNetworkFragment.isVisible()) {
            currentFragment = mNetworkFragment;
        } else if (mSettingsFragment != null && mSettingsFragment.isVisible()) {
            currentFragment = mSettingsFragment;
        }

        if (currentFragment instanceof NetworkFragment) {
            // Network fragment - navigate to AreaListActivity
            ((NetworkFragment) currentFragment).handleBackPress();
            navigateToAreaList();
        } else {
            // Other fragments - just go back
            super.onBackPressed();
        }
    }

    private void ensureOfficeSvgExists() {
        SharedPreferences prefs = getSharedPreferences("app_prefs", MODE_PRIVATE);
        // Force update for development
        try {
            File file = new File(getFilesDir(), "office.svg");
            // Overwrite every time during development to reflect SVG changes
            InputStream is = getAssets().open("office.svg");
            FileOutputStream fos = new FileOutputStream(file);
            byte[] buffer = new byte[1024];
            int read;
            while ((read = is.read(buffer)) != -1) {
                fos.write(buffer, 0, read);
            }
            fos.flush();
            fos.close();
            is.close();

            String uri = Uri.fromFile(file).toString();
            prefs.edit()
                    .putString("saved_svg_uri", uri)
                    .putString("svg_name_" + uri, "Office Map")
                    .apply();
            Log.d(TAG, "✅ office.svg updated in internal storage");
        } catch (IOException e) {
            Log.e(TAG, "Error copying office.svg", e);
        }
    }

    // ==================== BLUETOOTH & PERMISSIONS ====================

    private void requestBluetoothEnable() {
        BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null) {
            Log.w(TAG, "Device does not support Bluetooth");
            return;
        }
        if (!bluetoothAdapter.isEnabled()) {
            Log.d(TAG, "Bluetooth is off — requesting enable");
            enableBluetooth.launch(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
        }
    }

    private void checkAndRequestPermissions() {
        List<String> permissionsNeeded = new ArrayList<>();

        // ✅ Camera — QR scan ke liye
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.CAMERA);
        }

        // ✅ Location — BLE scan ke liye zaroori
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }

        // ✅ Bluetooth — Android version ke hisaab se
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.BLUETOOTH_SCAN);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
        }

        // ✅ Notifications — Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.POST_NOTIFICATIONS);
            }
        }

        if (!permissionsNeeded.isEmpty()) {
            ActivityCompat.requestPermissions(this,
                    permissionsNeeded.toArray(new String[0]),
                    PERMISSION_REQUEST_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            for (int i = 0; i < permissions.length; i++) {
                if (grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                    Log.d(TAG, "Permission granted: " + permissions[i]);
                } else {
                    Log.w(TAG, "Permission denied: " + permissions[i]);
                }
            }
        }
    }

    private void navigateToAreaList() {
        SharedPreferences prefs = getSharedPreferences("app_prefs", MODE_PRIVATE);
        String savedUri = prefs.getString("saved_svg_uri", null);

        if (savedUri == null) {
            // No SVG loaded, go to HomeActivity
            Intent intent = new Intent(this,
                    no.nordicsemi.android.swaromapmesh.swajaui.HomeActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
            return;
        }

        // Get saved area list
        java.util.Set<String> savedAreaSet = prefs.getStringSet("saved_area_list", null);
        ArrayList<String> areaList = savedAreaSet != null ? new ArrayList<>(savedAreaSet) : null;

        if (areaList == null || areaList.isEmpty()) {
            // Parse areas from SVG if not saved
            try {
                Uri uri = Uri.parse(savedUri);
                areaList = SvgParserList
                        .parseAreaIds(getContentResolver(), uri);

                // Save for future
                if (areaList != null && !areaList.isEmpty()) {
                    prefs.edit().putStringSet("saved_area_list", new java.util.HashSet<>(areaList)).apply();
                }
            } catch (Exception e) {
                Log.e(TAG, "Error parsing areas", e);
                areaList = new ArrayList<>();
            }
        }

        if (areaList != null && !areaList.isEmpty()) {
            Intent intent = new Intent(this,
                    no.nordicsemi.android.swaromapmesh.swajaui.AreaListActivity.class);
            intent.putExtra("svg_uri", savedUri);
            intent.putStringArrayListExtra("area_list", areaList);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
        } else {
            // No areas found, go to HomeActivity
            Intent intent = new Intent(this,
                    no.nordicsemi.android.swaromapmesh.swajaui.HomeActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
        }
    }

}
