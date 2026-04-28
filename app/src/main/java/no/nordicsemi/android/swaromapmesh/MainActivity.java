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
import no.nordicsemi.android.swaromapmesh.swajaui.SvgParserList;
import no.nordicsemi.android.swaromapmesh.viewmodels.SharedViewModel;

@AndroidEntryPoint
public class MainActivity extends AppCompatActivity implements
        NavigationBarView.OnItemSelectedListener,
        NavigationBarView.OnItemReselectedListener {

    private static final String TAG              = "MainActivity";
    private static final String CURRENT_FRAGMENT = "CURRENT_FRAGMENT";

    private SharedViewModel mViewModel;

    private NetworkFragment       mNetworkFragment;
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
        mViewModel = new ViewModelProvider(this).get(SharedViewModel.class);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        setSupportActionBar(binding.toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.app_name);
        }

        mNetworkFragment       = (NetworkFragment)       getSupportFragmentManager().findFragmentById(R.id.fragment_network);
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
                    .hide(mGroupsFragment)
                    .hide(mProxyFilterFragment)
                    .hide(mSettingsFragment);
        }else if (item.getItemId() == R.id.action_groups) {
            ft.hide(mNetworkFragment)
                    .show(mGroupsFragment)
                    .hide(mProxyFilterFragment)
                    .hide(mSettingsFragment);
        } else if (item.getItemId() == R.id.action_proxy) {
            ft.hide(mNetworkFragment)
                    .hide(mGroupsFragment)
                    .show(mProxyFilterFragment)
                    .hide(mSettingsFragment);
        } else if (item.getItemId() == R.id.action_settings) {
            ft.hide(mNetworkFragment)
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
    @Override
    public void onBackPressed() {
        // Get the currently visible fragment
        Fragment currentFragment = null;

        if (mNetworkFragment != null && mNetworkFragment.isVisible()) {
            currentFragment = mNetworkFragment;
        }
         else if (mGroupsFragment != null && mGroupsFragment.isVisible()) {
            currentFragment = mGroupsFragment;
        } else if (mProxyFilterFragment != null && mProxyFilterFragment.isVisible()) {
            currentFragment = mProxyFilterFragment;
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