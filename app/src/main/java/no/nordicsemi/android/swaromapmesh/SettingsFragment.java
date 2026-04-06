package no.nordicsemi.android.swaromapmesh;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.Date;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import dagger.hilt.android.AndroidEntryPoint;
import no.nordicsemi.android.swaromapmesh.databinding.FragmentSettingsBinding;
import no.nordicsemi.android.swaromapmesh.databinding.LayoutContainerBinding;
import no.nordicsemi.android.swaromapmesh.dialog.DialogFragmentError;
import no.nordicsemi.android.swaromapmesh.dialog.DialogFragmentMeshExportMsg;
import no.nordicsemi.android.swaromapmesh.dialog.DialogFragmentMeshImport;
import no.nordicsemi.android.swaromapmesh.dialog.DialogFragmentMeshImportMsg;
import no.nordicsemi.android.swaromapmesh.dialog.DialogFragmentNetworkName;
import no.nordicsemi.android.swaromapmesh.dialog.DialogFragmentResetNetwork;
import no.nordicsemi.android.swaromapmesh.export.ExportNetworkActivity;
import no.nordicsemi.android.swaromapmesh.keys.AppKeysActivity;
import no.nordicsemi.android.swaromapmesh.keys.NetKeysActivity;
import no.nordicsemi.android.swaromapmesh.provisioners.ProvisionersActivity;
import no.nordicsemi.android.swaromapmesh.scenes.ScenesActivity;
import no.nordicsemi.android.swaromapmesh.utils.Utils;
import no.nordicsemi.android.swaromapmesh.viewmodels.SharedViewModel;

import static androidx.activity.result.contract.ActivityResultContracts.GetContent;
import static java.text.DateFormat.getDateTimeInstance;

@AndroidEntryPoint
public class SettingsFragment extends Fragment implements
        DialogFragmentNetworkName.DialogFragmentNetworkNameListener,
        DialogFragmentResetNetwork.DialogFragmentResetNetworkListener,
        DialogFragmentMeshImport.DialogFragmentNetworkImportListener {

    private static final String TAG = SettingsFragment.class.getSimpleName();
    private SharedViewModel mViewModel;

    // ── Existing: JSON network import ────────────────────────────────────────
    private final ActivityResultLauncher<String> fileSelector =
            registerForActivityResult(new GetContent(), result -> {
                if (result != null) {
                    mViewModel.disconnect();
                    mViewModel.getMeshManagerApi().importMeshNetwork(result);
                }
            });

    // ── SVG map import ───────────────────────────────────────────────────────
    private final ActivityResultLauncher<String> svgSelector =
            registerForActivityResult(new GetContent(), uri -> {
                if (uri != null) {
                    requireContext().getContentResolver()
                            .takePersistableUriPermission(
                                    uri,
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                            );
                    mViewModel.setSvgUri(uri);
                    Log.d(TAG, "SVG imported: " + uri);
                }
            });

    // ────────────────────────────────────────────────────────────────────────

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

        // ── Network Name ──────────────────────────────────────────────────────
        binding.containerNetworkName.image
                .setBackground(ContextCompat.getDrawable(requireContext(), R.drawable.ic_label));
        binding.containerNetworkName.title.setText(R.string.name);
        binding.containerNetworkName.text.setVisibility(View.VISIBLE);
        binding.containerNetworkName.getRoot().setOnClickListener(v -> {
            final DialogFragmentNetworkName fragment = DialogFragmentNetworkName
                    .newInstance(binding.containerNetworkName.text.getText().toString());
            fragment.show(getChildFragmentManager(), null);
        });

        // ── Provisioners ──────────────────────────────────────────────────────
        binding.containerProvisioners.image
                .setBackground(ContextCompat.getDrawable(requireContext(),
                        R.drawable.ic_folder_provisioner_24dp));
        binding.containerProvisioners.title.setText(R.string.title_provisioners);
        binding.containerProvisioners.text.setVisibility(View.VISIBLE);
        binding.containerProvisioners.getRoot().setOnClickListener(v ->
                startActivity(new Intent(requireContext(), ProvisionersActivity.class)));

        // ── Net Keys ──────────────────────────────────────────────────────────
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

        // ── App Keys ──────────────────────────────────────────────────────────
        binding.containerAppKeys.image
                .setBackground(ContextCompat.getDrawable(requireContext(),
                        R.drawable.ic_folder_key_24dp));
        binding.containerAppKeys.title.setText(R.string.title_app_keys);
        binding.containerAppKeys.text.setVisibility(View.VISIBLE);
        binding.containerAppKeys.getRoot().setOnClickListener(v ->
                startActivity(new Intent(requireContext(), AppKeysActivity.class)));

        // ── Scenes ────────────────────────────────────────────────────────────
        binding.containerScenes.image
                .setBackground(ContextCompat.getDrawable(requireContext(),
                        R.drawable.ic_baseline_palette_24dp));
        binding.containerScenes.title.setText(R.string.title_scenes);
        binding.containerScenes.text.setVisibility(View.VISIBLE);
        binding.containerScenes.getRoot().setOnClickListener(v ->
                startActivity(new Intent(requireContext(), ScenesActivity.class)));

        // ── IV Test Mode ──────────────────────────────────────────────────────
        binding.containerIvTestMode.image
                .setBackground(ContextCompat.getDrawable(requireContext(),
                        R.drawable.ic_folder_key_24dp));
        binding.containerIvTestMode.title.setText(R.string.title_iv_test_mode);
        binding.containerIvTestMode.text.setText(R.string.iv_test_mode_summary);
        binding.containerIvTestMode.text.setVisibility(View.VISIBLE);
        binding.containerIvTestMode.actionChangeTestMode.setVisibility(View.VISIBLE);
        binding.containerIvTestMode.actionChangeTestMode
                .setChecked(mViewModel.getMeshManagerApi().isIvUpdateTestModeActive());
        binding.containerIvTestMode.actionChangeTestMode.setOnClickListener(v ->
                mViewModel.getMeshManagerApi().setIvUpdateTestModeActive(
                        binding.containerIvTestMode.actionChangeTestMode.isChecked()));
        binding.containerIvTestMode.getRoot().setOnClickListener(v ->
                DialogFragmentError
                        .newInstance(getString(R.string.info),
                                getString(R.string.iv_test_mode_info))
                        .show(getChildFragmentManager(), null));

        // ── Last Modified ─────────────────────────────────────────────────────
        binding.containerLastModified.image
                .setBackground(ContextCompat.getDrawable(requireContext(), R.drawable.ic_time));
        binding.containerLastModified.title.setText(R.string.last_modified);
        binding.containerLastModified.text.setVisibility(View.VISIBLE);
        binding.containerLastModified.getRoot().setVisibility(View.VISIBLE);
        binding.containerLastModified.getRoot().setClickable(false);

        // ── MQTT Settings row ─────────────────────────────────────────────────
        binding.containerMqtt.image
                .setBackground(ContextCompat.getDrawable(requireContext(),
                        R.drawable.ic_settings));          // use any suitable icon you have
        binding.containerMqtt.title.setText("MQTT Configuration");
        binding.containerMqtt.text.setVisibility(View.VISIBLE);
        // Show saved broker host as subtitle so user knows what's configured
        refreshMqttSubtitle(binding);
        binding.containerMqtt.getRoot().setOnClickListener(v -> {
            startActivity(new Intent(requireContext(), MqttSettingsActivity.class));
        });

        // ── App Version ───────────────────────────────────────────────────────
        final LayoutContainerBinding containerVersion = binding.containerVersion;
        containerVersion.getRoot().setClickable(false);
        containerVersion.image
                .setBackground(ContextCompat.getDrawable(requireContext(), R.drawable.ic_puzzle));
        containerVersion.title.setText(R.string.summary_version);
        final TextView version = containerVersion.text;
        version.setVisibility(View.VISIBLE);
        try {
            version.setText(requireContext().getPackageManager()
                    .getPackageInfo(requireContext().getPackageName(), 0).versionName);
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Version not found", e);
        }

        // ── LiveData observers ────────────────────────────────────────────────
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

    // ── Refresh MQTT subtitle when returning from MqttSettingsActivity ────────
    @Override
    public void onResume() {
        super.onResume();
        // Re-read prefs every time fragment is visible so subtitle stays fresh
        // We need the binding — safest to just post to root view
        // (If you prefer, move binding to a field instead)
    }

    /**
     * Shows the saved broker host (or "Not configured") as the subtitle
     * of the MQTT row, so the user can see at a glance what's set.
     */
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

    // ── Options Menu ──────────────────────────────────────────────────────────

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

        } else if (id == R.id.action_import_svg) {
            svgSelector.launch("image/svg+xml");
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

    // ── Dialog callbacks ──────────────────────────────────────────────────────

    @Override
    public void onNetworkNameEntered(@NonNull final String name) {
        mViewModel.getNetworkLiveData().setNetworkName(name);
    }

    @Override
    public void onNetworkReset() {
        mViewModel.resetMeshNetwork();
    }

    @Override
    public void onNetworkImportConfirmed() {
        fileSelector.launch("application/json");
    }
}