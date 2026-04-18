package no.nordicsemi.android.swaromapmesh.swajaui;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

import no.nordicsemi.android.swaromapmesh.databinding.ActivityHomeBinding;

public class HomeActivity extends AppCompatActivity {

    private ActivityHomeBinding binding;
    private static final String PREFS_NAME           = "app_prefs";
    private static final String KEY_SVG_URI          = "saved_svg_uri";
    private static final int    PERMISSION_REQUEST_CODE = 101;

    // ✅ Bluetooth enable launcher — ScannerActivity jaisa
    private final ActivityResultLauncher<Intent> enableBluetooth =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == RESULT_OK) {
                            Log.d("HomeActivity", "Bluetooth enabled by user");
                        } else {
                            Log.w("HomeActivity", "Bluetooth enable refused by user");
                        }
                    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // ✅ Step 1: Permissions pehle maango
        checkAndRequestPermissions();

        // ✅ Step 2: Bluetooth on karne ko kaho agar band hai
        requestBluetoothEnable();

        // ✅ Step 3: Saved map check karo
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String savedUri = prefs.getString(KEY_SVG_URI, null);
        Log.d("HomeActivity", "Saved URI: " + savedUri);

        if (savedUri != null) {
            Uri uri = Uri.parse(savedUri);
            ArrayList<String> areaList = SvgParser.parseAreaIds(getContentResolver(), uri);
            Log.d("HomeActivity", "Area list size: " + areaList.size());

            if (!areaList.isEmpty()) {
                Intent intent = new Intent(this, AreaListActivity.class);
                intent.putExtra("svg_uri", savedUri);
                intent.putStringArrayListExtra("area_list", areaList);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(intent);
                finish();
                return;
            }
        }

        // ✅ Step 4: Normal first-time flow
        binding = ActivityHomeBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.btnGetStarted.setOnClickListener(v -> {
            Intent intent = new Intent(HomeActivity.this, IdentifyActivity.class);
            startActivity(intent);
        });
    }

    // ==================== BLUETOOTH ENABLE ====================

    private void requestBluetoothEnable() {
        BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null) {
            Log.w("HomeActivity", "Device does not support Bluetooth");
            return;
        }
        if (!bluetoothAdapter.isEnabled()) {
            Log.d("HomeActivity", "Bluetooth is off — requesting enable");
            enableBluetooth.launch(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
        }
    }

    // ==================== PERMISSIONS ====================

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
        } else {
            // Android 11 aur neeche
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.BLUETOOTH);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADMIN)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.BLUETOOTH_ADMIN);
            }
        }

        // ✅ Storage — Android 12 se neeche
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            }
        }

        if (!permissionsNeeded.isEmpty()) {
            ActivityCompat.requestPermissions(
                    this,
                    permissionsNeeded.toArray(new String[0]),
                    PERMISSION_REQUEST_CODE
            );
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            for (int i = 0; i < permissions.length; i++) {
                if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                    Log.w("HomeActivity", "Permission denied: " + permissions[i]);
                }
            }
            // ✅ Permissions grant hone ke baad bluetooth bhi check karo
            requestBluetoothEnable();
        }
    }
}