package no.nordicsemi.android.swaromapmesh;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.util.Log;
import android.util.TypedValue;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import java.util.Date;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import dagger.hilt.android.AndroidEntryPoint;
import no.nordicsemi.android.swaromapmesh.databinding.FragmentSettingsBinding;
import no.nordicsemi.android.swaromapmesh.dialog.DialogFragmentMeshExportMsg;
import no.nordicsemi.android.swaromapmesh.dialog.DialogFragmentMeshImport;
import no.nordicsemi.android.swaromapmesh.dialog.DialogFragmentMeshImportMsg;
import no.nordicsemi.android.swaromapmesh.dialog.DialogFragmentNetworkName;
import no.nordicsemi.android.swaromapmesh.dialog.DialogFragmentResetNetwork;
import no.nordicsemi.android.swaromapmesh.export.ExportNetworkActivity;
import no.nordicsemi.android.swaromapmesh.keys.AppKeysActivity;
import no.nordicsemi.android.swaromapmesh.keys.NetKeysActivity;
import no.nordicsemi.android.swaromapmesh.mqtt.MqttSettingsActivity;
import no.nordicsemi.android.swaromapmesh.provisioners.ProvisionersActivity;
import no.nordicsemi.android.swaromapmesh.swajaui.AreaClientListActivity;
import no.nordicsemi.android.swaromapmesh.swajaui.DialogFragmentHiddenAccess;
import no.nordicsemi.android.swaromapmesh.utils.Utils;
import no.nordicsemi.android.swaromapmesh.viewmodels.ClientServerElementStore;
import no.nordicsemi.android.swaromapmesh.viewmodels.SharedViewModel;

import static androidx.activity.result.contract.ActivityResultContracts.GetContent;
import static java.text.DateFormat.getDateTimeInstance;

@AndroidEntryPoint
public class SettingsFragment extends Fragment implements
        DialogFragmentNetworkName.DialogFragmentNetworkNameListener,
        DialogFragmentResetNetwork.DialogFragmentResetNetworkListener,
        DialogFragmentMeshImport.DialogFragmentNetworkImportListener,
        DialogFragmentHiddenAccess.DialogFragmentHiddenAccessListener {

    private static final String TAG = SettingsFragment.class.getSimpleName();
    private static final long HIDDEN_ACCESS_HOLD_MS = 10000; // 10 seconds

    private SharedViewModel mViewModel;

    private CountDownTimer hiddenAccessCountDownTimer;

    private final androidx.activity.result.ActivityResultLauncher<String> fileSelector =
            registerForActivityResult(new GetContent(), result -> {
                if (result != null) {
                    mViewModel.disconnect();
                    mViewModel.getMeshManagerApi().importMeshNetwork(result);
                }
            });

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull final LayoutInflater inflater,
                             @Nullable final ViewGroup viewGroup,
                             @Nullable final Bundle savedInstanceState) {

        mViewModel = new ViewModelProvider(requireActivity()).get(SharedViewModel.class);
        final FragmentSettingsBinding binding =
                FragmentSettingsBinding.inflate(getLayoutInflater());

        // Network Name
        binding.containerNetworkName.image
                .setBackground(ContextCompat.getDrawable(requireContext(), R.drawable.ic_label));
        binding.containerNetworkName.title.setText(R.string.name);
        binding.containerNetworkName.text.setVisibility(View.VISIBLE);
        binding.containerNetworkName.getRoot().setOnClickListener(v -> {
            final DialogFragmentNetworkName fragment = DialogFragmentNetworkName
                    .newInstance(binding.containerNetworkName.text.getText().toString());
            fragment.show(getChildFragmentManager(), null);
        });

        // Provisioners
        binding.containerProvisioners.image
                .setBackground(ContextCompat.getDrawable(requireContext(),
                        R.drawable.ic_folder_provisioner_24dp));
        binding.containerProvisioners.title.setText(R.string.title_provisioners);
        binding.containerProvisioners.text.setVisibility(View.VISIBLE);
        binding.containerProvisioners.getRoot().setOnClickListener(v ->
                startActivity(new Intent(requireContext(), ProvisionersActivity.class)));

        // Net Keys
        binding.containerNetKeys.image
                .setBackground(ContextCompat.getDrawable(requireContext(),
                        R.drawable.ic_folder_key_24dp));
        binding.containerNetKeys.title.setText(R.string.title_net_keys);
        binding.containerNetKeys.text.setVisibility(View.VISIBLE);
        binding.containerNetKeys.getRoot().setOnClickListener(v -> {
            final Intent intent = new Intent(requireContext(), NetKeysActivity.class);
            intent.putExtra(Utils.EXTRA_DATA, Utils.MANAGE_NET_KEY);
            startActivity(intent);
        });

        // App Keys
        binding.containerAppKeys.image
                .setBackground(ContextCompat.getDrawable(requireContext(),
                        R.drawable.ic_folder_key_24dp));
        binding.containerAppKeys.title.setText(R.string.title_app_keys);
        binding.containerAppKeys.text.setVisibility(View.VISIBLE);
        binding.containerAppKeys.getRoot().setOnClickListener(v ->
                startActivity(new Intent(requireContext(), AppKeysActivity.class)));

        // MQTT Settings
        binding.containerMqtt.image
                .setBackground(ContextCompat.getDrawable(requireContext(),
                        R.drawable.ic_settings));
        binding.containerMqtt.title.setText("MQTT Configuration");
        binding.containerMqtt.text.setVisibility(View.VISIBLE);
        refreshMqttSubtitle(binding);
        binding.containerMqtt.getRoot().setOnClickListener(v ->
                startActivity(new Intent(requireContext(), MqttSettingsActivity.class)));

        // ================================================================
        // AREA CLIENT LIST
        // ================================================================
        binding.containerAreaClient.image
                .setBackground(ContextCompat.getDrawable(requireContext(),
                        R.drawable.ic_settings));
        binding.containerAreaClient.title.setText("Area Client List");
        binding.containerAreaClient.text.setVisibility(View.VISIBLE);
        binding.containerAreaClient.text.setText("View all client addresses");
        binding.containerAreaClient.getRoot().setOnClickListener(v -> {
            Log.d(TAG, "Area Client List clicked");
            Intent intent = new Intent(requireContext(), AreaClientListActivity.class);
            startActivity(intent);
        });

        // ================================================================
        // RSSI FILTER
        // ================================================================
        binding.containerRssiFilter.image
                .setBackground(ContextCompat.getDrawable(requireContext(),
                        R.drawable.ic_folder_key_24dp)); // swap icon as needed
        binding.containerRssiFilter.title.setText("RSSI Filter");
        binding.containerRssiFilter.text.setVisibility(View.VISIBLE);
        binding.containerRssiFilter.text.setText("Set minimum signal strength threshold");
        binding.containerRssiFilter.getRoot().setOnClickListener(v -> {
            Log.d(TAG, "RSSI Filter clicked");
            Intent intent = new Intent(requireContext(), RssiFilterActivity.class);
            startActivity(intent);
        });

        // PROXY FILTER
        binding.containerProxyFilter.image
                .setBackground(ContextCompat.getDrawable(requireContext(),
                        R.drawable.ic_proxy));
        binding.containerProxyFilter.title.setText("Auto Proxy Filter");
        binding.containerProxyFilter.text.setVisibility(View.VISIBLE);
        binding.containerProxyFilter.text.setText("Enable or disable automatic proxy filtering");

        binding.containerProxyFilter.actionChangeTestMode.setVisibility(View.VISIBLE);
        binding.containerProxyFilter.actionChangeTestMode.setChecked(mViewModel.isProxyEnabled());
        binding.containerProxyFilter.actionChangeTestMode.setOnCheckedChangeListener((buttonView, isChecked) -> {
            mViewModel.setProxyEnabled(isChecked);
            Log.d(TAG, "Proxy Filter toggled: " + isChecked);
        });

        binding.containerProxyFilter.getRoot().setOnClickListener(v -> {
            binding.containerProxyFilter.actionChangeTestMode.toggle();
        });

        // DEVICE FILTER
        binding.containerDeviceFilter.image
                .setBackground(ContextCompat.getDrawable(requireContext(),
                        R.drawable.ic_settings));
        binding.containerDeviceFilter.title.setText("Show Device Filter");
        binding.containerDeviceFilter.text.setVisibility(View.VISIBLE);
        binding.containerDeviceFilter.text.setText("Show all devices after provisioning");
        binding.containerDeviceFilter.actionChangeTestMode.setVisibility(View.VISIBLE);

        SharedPreferences appPrefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE);
        boolean isDeviceFilterEnabled = appPrefs.getBoolean("show_device_filter", false);
        binding.containerDeviceFilter.actionChangeTestMode.setChecked(isDeviceFilterEnabled);

        binding.containerDeviceFilter.actionChangeTestMode.setOnCheckedChangeListener((buttonView, isChecked) -> {
            appPrefs.edit().putBoolean("show_device_filter", isChecked).apply();
            Log.d(TAG, "Device Filter toggled: " + isChecked);
            mViewModel.forceSvgRefresh(); // Notify that SVG needs re-rendering
        });

        binding.containerDeviceFilter.getRoot().setOnClickListener(v -> {
            binding.containerDeviceFilter.actionChangeTestMode.toggle();
        });

        // ================================================================
        // HIDDEN ACCESS - long press 10 seconds to reveal password screen,
        // correct password navigates to GroupsFragment (GroupsActivity)
        // Subtitle text is intentionally kept hidden from the user; the
        // countdown still runs internally, it's just not displayed.
        // ================================================================
        binding.containerHiddenAccess.image
                .setBackground(ContextCompat.getDrawable(requireContext(), R.drawable.ic_settings));
        binding.containerHiddenAccess.title.setText("More");
        binding.containerHiddenAccess.text.setVisibility(View.GONE);

        binding.containerHiddenAccess.getRoot().setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    Log.d(TAG, "Hidden access: touch DOWN detected");

                    // Visual feedback: set pressed state and change background color
                    v.setPressed(true);
                    v.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.nordicLightGray));

                    // Haptic feedback
                    v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);

                    // Prevent the parent ScrollView from intercepting this touch as a
                    // scroll gesture, which would otherwise send ACTION_CANCEL almost
                    // immediately and stop the countdown before 10s.
                    v.getParent().requestDisallowInterceptTouchEvent(true);

                    if (hiddenAccessCountDownTimer != null) {
                        hiddenAccessCountDownTimer.cancel();
                    }

                    hiddenAccessCountDownTimer = new CountDownTimer(HIDDEN_ACCESS_HOLD_MS, 1000) {
                        @Override
                        public void onTick(long millisUntilFinished) {
                            long secondsLeft = (millisUntilFinished / 1000) + 1;
                            Log.d(TAG, "Hidden access: holding, " + secondsLeft + "s left");

                            // Provide small haptic feedback every second to indicate it's still working
                            v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                        }

                        @Override
                        public void onFinish() {
                            Log.d(TAG, "Hidden access hold completed, showing password dialog");
                            v.setPressed(false);
                            // Reset background to default selectable item background
                            TypedValue outValue = new TypedValue();
                            requireContext().getTheme().resolveAttribute(android.R.attr.selectableItemBackground, outValue, true);
                            v.setBackgroundResource(outValue.resourceId);

                            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);

                            DialogFragmentHiddenAccess.newInstance()
                                    .show(getChildFragmentManager(), "hidden_access");
                        }
                    }.start();
                    return true;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    Log.d(TAG, "Hidden access: touch " +
                            (event.getAction() == MotionEvent.ACTION_UP ? "UP" : "CANCEL") +
                            " - resetting timer");

                    v.setPressed(false);
                    // Reset background to default selectable item background
                    TypedValue outValue = new TypedValue();
                    requireContext().getTheme().resolveAttribute(android.R.attr.selectableItemBackground, outValue, true);
                    v.setBackgroundResource(outValue.resourceId);

                    v.getParent().requestDisallowInterceptTouchEvent(false);
                    if (hiddenAccessCountDownTimer != null) {
                        hiddenAccessCountDownTimer.cancel();
                        hiddenAccessCountDownTimer = null;
                    }
                    return true;

                default:
                    return false;
            }
        });

        // LiveData observers
        mViewModel.getNetworkLiveData().observe(getViewLifecycleOwner(), meshNetworkLiveData -> {
            if (meshNetworkLiveData != null) {
                binding.containerNetworkName.text
                        .setText(meshNetworkLiveData.getNetworkName());
                binding.containerNetKeys.text
                        .setText(String.valueOf(meshNetworkLiveData.getNetworkKeys().size()));
                binding.containerProvisioners.text
                        .setText(String.valueOf(meshNetworkLiveData.getProvisioners().size()));
                binding.containerAppKeys.text
                        .setText(String.valueOf(meshNetworkLiveData.getAppKeys().size()));
                binding.containerScenes.text
                        .setText(String.valueOf(meshNetworkLiveData.getScenes().size()));
                binding.containerLastModified.text.setText(
                        getDateTimeInstance().format(
                                new Date(meshNetworkLiveData.getMeshNetwork().getTimestamp())));
            }
        });

        mViewModel.getNetworkLoadState().observe(getViewLifecycleOwner(), networkImportState -> {
            final String title = getString(R.string.title_network_import);
            DialogFragmentMeshImportMsg
                    .newInstance(R.drawable.ic_info_outline, title, networkImportState)
                    .show(getChildFragmentManager(), null);
        });

        mViewModel.getNetworkExportState().observe(getViewLifecycleOwner(), networkExportState -> {
            final String title = getString(R.string.title_network_export);
            DialogFragmentMeshExportMsg
                    .newInstance(R.drawable.ic_info_outline, title, networkExportState)
                    .show(getChildFragmentManager(), null);
        });

        return binding.getRoot();
    }

    @Override
    public void onResume() {
        super.onResume();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // Prevent a pending countdown from firing after the view is gone
        if (hiddenAccessCountDownTimer != null) {
            hiddenAccessCountDownTimer.cancel();
            hiddenAccessCountDownTimer = null;
        }
    }

    private void refreshMqttSubtitle(FragmentSettingsBinding binding) {
        SharedPreferences prefs = requireContext()
                .getSharedPreferences(MqttSettingsActivity.PREFS_MQTT, Context.MODE_PRIVATE);
        String host = prefs.getString(MqttSettingsActivity.KEY_BROKER_HOST, "");
        if (host.isEmpty()) {
            binding.containerMqtt.text.setText("Not configured");
        } else {
            int port = prefs.getInt(MqttSettingsActivity.KEY_BROKER_PORT, 1883);
            binding.containerMqtt.text.setText(host + ":" + port);
        }
    }

    @Override
    public void onCreateOptionsMenu(@NonNull final Menu menu,
                                    @NonNull final MenuInflater inflater) {
        inflater.inflate(R.menu.network_settings, menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull final MenuItem item) {
        final int id = item.getItemId();

        if (id == R.id.action_import_network) {
            final String title   = getString(R.string.title_network_import);
            final String message = getString(R.string.network_import_rationale);
            DialogFragmentMeshImport.newInstance(title, message)
                    .show(getChildFragmentManager(), null);
            return true;

        } else if (id == R.id.action_export_network) {
            startActivity(new Intent(requireContext(), ExportNetworkActivity.class));
            return true;

        } else if (id == R.id.action_reset_network) {
            DialogFragmentResetNetwork
                    .newInstance(
                            getString(R.string.title_reset_network),
                            getString(R.string.message_reset_network))
                    .show(getChildFragmentManager(), null);
            return true;
        }

        return false;
    }

    @Override
    public void onNetworkNameEntered(@NonNull final String name) {
        mViewModel.getNetworkLiveData().setNetworkName(name);
    }

    @Override
    public void onNetworkReset() {
        Log.d(TAG, "=== Starting network reset ===");

        // 1. Reset mesh network
        mViewModel.resetMeshNetwork();

        // 2. ── mesh_prefs completely wipe ─────────────────────────────────
        SharedPreferences meshPrefs = requireContext()
                .getSharedPreferences("mesh_prefs", Context.MODE_PRIVATE);
        meshPrefs.edit().clear().apply();
        Log.d(TAG, "✅ mesh_prefs cleared");

        // 3. ── app_prefs wipe ─────────────────────────────────────────────
        requireContext()
                .getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                .edit().clear().apply();
        Log.d(TAG, "✅ app_prefs cleared");

        // 4. ── ClientServerElementStore in-memory + prefs reset ───────────
        ClientServerElementStore.clearAll();
        Log.d(TAG, "✅ ClientServerElementStore cleared");

        // 5. Refresh ViewModel
        mViewModel.syncFromStore();
        mViewModel.forceSvgRefresh();

        Log.d(TAG, "=== Network reset complete ===");

        // 6. Navigate to Home
        Intent intent = new Intent(requireContext(),
                no.nordicsemi.android.swaromapmesh.swajaui.HomeActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
    }

    @Override
    public void onNetworkImportConfirmed() {
        fileSelector.launch("application/json");
    }

    @Override
    public void onPasswordCorrect() {
        Log.d(TAG, "Correct password entered, navigating to GroupsFragment");
        if (requireActivity() instanceof MainActivity) {
            ((MainActivity) requireActivity()).navigateToTab(R.id.action_groups);
        } else {
            Log.w(TAG, "Host activity is not MainActivity — cannot switch to Groups tab");
        }
    }
}