//package no.nordicsemi.android.swaromesh;
//
//import android.content.Intent;
//import android.os.Bundle;
//
//import androidx.appcompat.app.AppCompatActivity;
//
//import dagger.hilt.android.AndroidEntryPoint;
//
//@AndroidEntryPoint
//public class MainActivity extends AppCompatActivity {
//
//    @Override
//    protected void onCreate(Bundle savedInstanceState) {
//        setTheme(R.style.AppTheme);
//        super.onCreate(savedInstanceState);
//
//        // Directly start SvgmapActivity
//        startActivity(new Intent(this, SvgmapActivity.class));
//        finish();
//    }
//}
//



package no.nordicsemi.android.swaromesh;

import android.graphics.drawable.Drawable;
import android.os.Bundle;
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

import dagger.hilt.android.AndroidEntryPoint;
import no.nordicsemi.android.swaromesh.databinding.ActivityMainBinding;
import no.nordicsemi.android.swaromesh.viewmodels.SharedViewModel;

@AndroidEntryPoint
public class MainActivity extends AppCompatActivity implements
        NavigationBarView.OnItemSelectedListener,
        NavigationBarView.OnItemReselectedListener {

    private static final String CURRENT_FRAGMENT = "CURRENT_FRAGMENT";

    private SharedViewModel mViewModel;

    private NetworkFragment mNetworkFragment;
    private DevicesFilterActivity mDevicesFilterFragment;
    private GroupsFragment mGroupsFragment;
    private ProxyFilterFragment mProxyFilterFragment;
    private Fragment mSettingsFragment;
    private ActivityMainBinding binding;

    private BottomNavigationView bottomNavigationView;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        setTheme(R.style.AppTheme);
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);

        mViewModel = new ViewModelProvider(this).get(SharedViewModel.class);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        setSupportActionBar(binding.toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.app_name);
        }


        // Find fragments from XML
        mNetworkFragment = (NetworkFragment)
                getSupportFragmentManager().findFragmentById(R.id.fragment_network);

        mDevicesFilterFragment = (DevicesFilterActivity)
                getSupportFragmentManager().findFragmentById(R.id.fragment_device_filter);

        mGroupsFragment = (GroupsFragment)
                getSupportFragmentManager().findFragmentById(R.id.fragment_groups);

        mProxyFilterFragment = (ProxyFilterFragment)
                getSupportFragmentManager().findFragmentById(R.id.fragment_proxy);

        mSettingsFragment =
                getSupportFragmentManager().findFragmentById(R.id.fragment_settings);

        bottomNavigationView = findViewById(R.id.bottom_navigation_view);
        bottomNavigationView.setOnItemSelectedListener(this);
        bottomNavigationView.setOnItemReselectedListener(this);

        // 🔥 IMPORTANT: default fragment handling
        if (savedInstanceState == null) {
            bottomNavigationView.setSelectedItemId(R.id.action_network);
            onNavigationItemSelected(
                    bottomNavigationView.getMenu().findItem(R.id.action_network)
            );
        } else {
            int selected = savedInstanceState.getInt(CURRENT_FRAGMENT, R.id.action_network);
            bottomNavigationView.setSelectedItemId(selected);
            onNavigationItemSelected(
                    bottomNavigationView.getMenu().findItem(selected)
            );
        }
        mViewModel.isConnectedToProxy().observe(this, connected -> {
            invalidateOptionsMenu();
        });
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(CURRENT_FRAGMENT, bottomNavigationView.getSelectedItemId());
    }

    // ───────────────────── MENU ─────────────────────

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {

        Boolean isConnected = mViewModel.isConnectedToProxy().getValue();

        getMenuInflater().inflate(
                isConnected != null && isConnected
                        ? R.menu.menu_connect_icon
                        : R.menu.menu_disconnect_icon,
                menu
        );

        MenuItem item;

        if (isConnected == null || !isConnected) {

            // DISCONNECTED → start blinking
            item = menu.findItem(R.id.action_disconnection_state);

            if (item != null) {

                View view = findViewById(item.getItemId());

                if (view != null) {

                    Animation blink =
                            AnimationUtils.loadAnimation(this, R.anim.blink);

                    view.startAnimation(blink);
                }
            }

        } else {

            // CONNECTED → stop blinking
            item = menu.findItem(R.id.action_connection_state);

            if (item != null) {

                View view = findViewById(item.getItemId());

                if (view != null) {
                    view.clearAnimation();
                }
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

    // ─────────────────── NAVIGATION ───────────────────

    @Override
    public boolean onNavigationItemSelected(@NonNull MenuItem item) {

        FragmentTransaction ft = getSupportFragmentManager().beginTransaction();

        if (item.getItemId() == R.id.action_network) {
            ft.show(mNetworkFragment)
                    .hide(mDevicesFilterFragment)
                    .hide(mGroupsFragment)
                    .hide(mProxyFilterFragment)
                    .hide(mSettingsFragment);
        }

        else if (item.getItemId() == R.id.action_device_filter) {
            ft.hide(mNetworkFragment)
                    .show(mDevicesFilterFragment)
                    .hide(mGroupsFragment)
                    .hide(mProxyFilterFragment)
                    .hide(mSettingsFragment);
        }

        else if (item.getItemId() == R.id.action_groups) {
            ft.hide(mNetworkFragment)
                    .hide(mDevicesFilterFragment)
                    .show(mGroupsFragment)
                    .hide(mProxyFilterFragment)
                    .hide(mSettingsFragment);
        }

        else if (item.getItemId() == R.id.action_proxy) {
            ft.hide(mNetworkFragment)
                    .hide(mDevicesFilterFragment)
                    .hide(mGroupsFragment)
                    .show(mProxyFilterFragment)
                    .hide(mSettingsFragment);
        }

        else if (item.getItemId() == R.id.action_settings) {
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





