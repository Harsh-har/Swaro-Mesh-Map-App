package no.nordicsemi.android.swaromapmesh;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.navigation.NavigationBarView;

import java.util.ArrayList;

import dagger.hilt.android.AndroidEntryPoint;
import no.nordicsemi.android.swaromapmesh.databinding.ActivityMainBinding;
import no.nordicsemi.android.swaromapmesh.viewmodels.SharedViewModel;

@AndroidEntryPoint
public class MainActivity extends AppCompatActivity implements
        NavigationBarView.OnItemSelectedListener,
        NavigationBarView.OnItemReselectedListener {

    private static final String TAG              = "MainActivity";
    private static final String CURRENT_FRAGMENT = "CURRENT_FRAGMENT";

    private SharedViewModel mViewModel;

    private NetworkFragment       mNetworkFragment;
    private DevicesFilterActivity mDevicesFilterFragment;
    private GroupsFragment        mGroupsFragment;
    private ProxyFilterFragment   mProxyFilterFragment;
    private Fragment              mSettingsFragment;
    private ActivityMainBinding   binding;
    private BottomNavigationView  bottomNavigationView;

    // ==================== LIFECYCLE ====================

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        setTheme(R.style.AppTheme);
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);

        // ✅ Check saved URI
        SharedPreferences prefs = getSharedPreferences("app_prefs", MODE_PRIVATE);
        String savedUri = prefs.getString("saved_svg_uri", null);

        // ✅ Agar AreaListActivity se aaye hain toh redirect mat karo
        boolean fromAreaList = getIntent().getBooleanExtra("from_area_list", false);

        if (savedUri == null) {
            // Pehli baar — HomeActivity pe bhejo
            Intent intent = new Intent(this, no.nordicsemi.android.swaromapmesh.swajaui.HomeActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
            return;
        } else if (!fromAreaList) {
            // App launch pe — AreaListActivity pe bhejo
            Uri uri = Uri.parse(savedUri);
            ArrayList<String> areaList = no.nordicsemi.android.swaromapmesh.swajaui.SvgParser
                    .parseAreaIds(getContentResolver(), uri);
            if (!areaList.isEmpty()) {
                Intent intent = new Intent(this, no.nordicsemi.android.swaromapmesh.swajaui.AreaListActivity.class);
                intent.putExtra("svg_uri", savedUri);
                intent.putStringArrayListExtra("area_list", areaList);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(intent);
                finish();
                return;
            }
        }

        // ✅ fromAreaList == true — seedha NetworkFragment dikhao
        mViewModel = new ViewModelProvider(this).get(SharedViewModel.class);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        setSupportActionBar(binding.toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.app_name);
        }

        // Find fragments from XML
        mNetworkFragment       = (NetworkFragment)       getSupportFragmentManager().findFragmentById(R.id.fragment_network);
        mDevicesFilterFragment = (DevicesFilterActivity) getSupportFragmentManager().findFragmentById(R.id.fragment_device_filter);
        mGroupsFragment        = (GroupsFragment)        getSupportFragmentManager().findFragmentById(R.id.fragment_groups);
        mProxyFilterFragment   = (ProxyFilterFragment)   getSupportFragmentManager().findFragmentById(R.id.fragment_proxy);
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

        // ✅ Handle intent from onCreate (first launch case)
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

        Log.d(TAG, "handleNavigationIntent: navigateToNetwork=" + navigateToNetwork
                + " focusAreaId=" + focusAreaId);

        if (navigateToNetwork) {
            if (bottomNavigationView != null) {
                bottomNavigationView.setSelectedItemId(R.id.action_network);
                onNavigationItemSelected(
                        bottomNavigationView.getMenu().findItem(R.id.action_network));
            }
        }

        if (focusAreaId != null && !focusAreaId.isEmpty()) {
            mViewModel.setFocusAreaId(focusAreaId);
            Log.d(TAG, "🎯 setFocusAreaId: " + focusAreaId);
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
        } else {
            item = menu.findItem(R.id.action_connection_state);
            if (item != null) {
                View view = findViewById(item.getItemId());
                if (view != null) view.clearAnimation();
            }
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.action_connection_state) {
            mViewModel.navigateToScannerActivity(this, false);
            return true;
        } else if (item.getItemId() == R.id.action_disconnection_state) {
            mViewModel.disconnect();
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
                    .hide(mDevicesFilterFragment)
                    .hide(mGroupsFragment)
                    .hide(mProxyFilterFragment)
                    .hide(mSettingsFragment);
        } else if (item.getItemId() == R.id.action_device_filter) {
            ft.hide(mNetworkFragment)
                    .show(mDevicesFilterFragment)
                    .hide(mGroupsFragment)
                    .hide(mProxyFilterFragment)
                    .hide(mSettingsFragment);
        } else if (item.getItemId() == R.id.action_groups) {
            ft.hide(mNetworkFragment)
                    .hide(mDevicesFilterFragment)
                    .show(mGroupsFragment)
                    .hide(mProxyFilterFragment)
                    .hide(mSettingsFragment);
        } else if (item.getItemId() == R.id.action_proxy) {
            ft.hide(mNetworkFragment)
                    .hide(mDevicesFilterFragment)
                    .hide(mGroupsFragment)
                    .show(mProxyFilterFragment)
                    .hide(mSettingsFragment);
        } else if (item.getItemId() == R.id.action_settings) {
            ft.hide(mNetworkFragment)
                    .hide(mDevicesFilterFragment)
                    .hide(mGroupsFragment)
                    .hide(mProxyFilterFragment)
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
}