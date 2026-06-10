package no.nordicsemi.android.swarorgbww;

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
import no.nordicsemi.android.swarorgbww.databinding.ActivityMainBinding;
import no.nordicsemi.android.swarorgbww.viewmodels.SharedViewModel;

@AndroidEntryPoint
public class MainActivity extends AppCompatActivity implements
        NavigationBarView.OnItemSelectedListener,
        NavigationBarView.OnItemReselectedListener {

    private static final String CURRENT_FRAGMENT = "CURRENT_FRAGMENT";

    private SharedViewModel mViewModel;

    private Fragment mNetworkFragment;
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

        // fragment_network is now a FrameLayout — add LightSelectorFragment manually
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .add(R.id.fragment_network, new LightSelectorFragment())
                    .commit();
        }

        // Find fragments
        mNetworkFragment =
                getSupportFragmentManager().findFragmentById(R.id.fragment_network);

        mGroupsFragment = (GroupsFragment)
                getSupportFragmentManager().findFragmentById(R.id.fragment_groups);

        mProxyFilterFragment = (ProxyFilterFragment)
                getSupportFragmentManager().findFragmentById(R.id.fragment_proxy);

        mSettingsFragment =
                getSupportFragmentManager().findFragmentById(R.id.fragment_settings);

        bottomNavigationView = findViewById(R.id.bottom_navigation_view);
        bottomNavigationView.setOnItemSelectedListener(this);
        bottomNavigationView.setOnItemReselectedListener(this);

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

        // When network tab is selected, pop back stack so LightSelectorFragment
        // is visible again
        if (item.getItemId() == R.id.action_network) {
            getSupportFragmentManager().popBackStack(
                    "selection",
                    androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE
            );
            getSupportFragmentManager().popBackStack(
                    "tunable",
                    androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE
            );
        }

        // Re-fetch mNetworkFragment after possible back stack pop
        mNetworkFragment =
                getSupportFragmentManager().findFragmentById(R.id.fragment_network);

        FragmentTransaction ft = getSupportFragmentManager().beginTransaction();

        if (item.getItemId() == R.id.action_network) {
            if (mNetworkFragment != null) ft.show(mNetworkFragment);
            if (mGroupsFragment != null)  ft.hide(mGroupsFragment);
            if (mProxyFilterFragment != null) ft.hide(mProxyFilterFragment);
            if (mSettingsFragment != null)    ft.hide(mSettingsFragment);
        } else if (item.getItemId() == R.id.action_groups) {
            if (mNetworkFragment != null) ft.hide(mNetworkFragment);
            if (mGroupsFragment != null)  ft.show(mGroupsFragment);
            if (mProxyFilterFragment != null) ft.hide(mProxyFilterFragment);
            if (mSettingsFragment != null)    ft.hide(mSettingsFragment);
        } else if (item.getItemId() == R.id.action_proxy) {
            if (mNetworkFragment != null) ft.hide(mNetworkFragment);
            if (mGroupsFragment != null)  ft.hide(mGroupsFragment);
            if (mProxyFilterFragment != null) ft.show(mProxyFilterFragment);
            if (mSettingsFragment != null)    ft.hide(mSettingsFragment);
        } else if (item.getItemId() == R.id.action_settings) {
            if (mNetworkFragment != null) ft.hide(mNetworkFragment);
            if (mGroupsFragment != null)  ft.hide(mGroupsFragment);
            if (mProxyFilterFragment != null) ft.hide(mProxyFilterFragment);
            if (mSettingsFragment != null)    ft.show(mSettingsFragment);
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