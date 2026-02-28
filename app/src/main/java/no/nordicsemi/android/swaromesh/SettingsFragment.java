
package no.nordicsemi.android.swaromesh;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
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
import no.nordicsemi.android.swaromesh.databinding.FragmentSettingsBinding;
import no.nordicsemi.android.swaromesh.databinding.LayoutContainerBinding;
import no.nordicsemi.android.swaromesh.dialog.DialogFragmentError;
import no.nordicsemi.android.swaromesh.dialog.DialogFragmentMeshExportMsg;
import no.nordicsemi.android.swaromesh.dialog.DialogFragmentMeshImport;
import no.nordicsemi.android.swaromesh.dialog.DialogFragmentMeshImportMsg;
import no.nordicsemi.android.swaromesh.dialog.DialogFragmentNetworkName;
import no.nordicsemi.android.swaromesh.dialog.DialogFragmentResetNetwork;
import no.nordicsemi.android.swaromesh.export.ExportNetworkActivity;
import no.nordicsemi.android.swaromesh.keys.AppKeysActivity;
import no.nordicsemi.android.swaromesh.keys.NetKeysActivity;
import no.nordicsemi.android.swaromesh.provisioners.ProvisionersActivity;
import no.nordicsemi.android.swaromesh.scenes.ScenesActivity;
import no.nordicsemi.android.swaromesh.utils.Utils;
import no.nordicsemi.android.swaromesh.viewmodels.SharedViewModel;

import static androidx.activity.result.contract.ActivityResultContracts.GetContent;
import static java.text.DateFormat.getDateTimeInstance;

@AndroidEntryPoint
public class SettingsFragment extends Fragment implements
        DialogFragmentNetworkName.DialogFragmentNetworkNameListener,
        DialogFragmentResetNetwork.DialogFragmentResetNetworkListener,
        DialogFragmentMeshImport.DialogFragmentNetworkImportListener {

    private static final String TAG = SettingsFragment.class.getSimpleName();
    private static final int READ_FILE_REQUEST_CODE = 42;
    private SharedViewModel mViewModel;

    private final ActivityResultLauncher<String> fileSelector = registerForActivityResult(
            new GetContent(), result -> {
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
                             @Nullable final ViewGroup viewGroup, @Nullable final Bundle savedInstanceState) {
        mViewModel = new ViewModelProvider(requireActivity()).get(SharedViewModel.class);
        final FragmentSettingsBinding binding = FragmentSettingsBinding.inflate(getLayoutInflater());

        // Set up views
        binding.containerNetworkName.image
                .setBackground(ContextCompat.getDrawable(requireContext(), R.drawable.ic_label));
        binding.containerNetworkName.title.setText(R.string.name);
        binding.containerNetworkName.text.setVisibility(View.VISIBLE);
        binding.containerNetworkName.getRoot().setOnClickListener(v -> {
            final DialogFragmentNetworkName fragment = DialogFragmentNetworkName.
                    newInstance(binding.containerNetworkName.text.getText().toString());
            fragment.show(getChildFragmentManager(), null);
        });

        binding.containerProvisioners.image
                .setBackground(ContextCompat.getDrawable(requireContext(), R.drawable.ic_folder_provisioner_24dp));
        binding.containerProvisioners.title.setText(R.string.title_provisioners);
        binding.containerProvisioners.text.setVisibility(View.VISIBLE);
        binding.containerProvisioners.getRoot().setOnClickListener(v -> startActivity(new Intent(requireContext(), ProvisionersActivity.class)));

        binding.containerNetKeys.image
                .setBackground(ContextCompat.getDrawable(requireContext(), R.drawable.ic_folder_key_24dp));
        binding.containerNetKeys.title.setText(R.string.title_net_keys);
        binding.containerNetKeys.text.setVisibility(View.VISIBLE);
        binding.containerNetKeys.getRoot().setOnClickListener(v -> {
            final Intent intent = new Intent(requireContext(), NetKeysActivity.class);
            intent.putExtra(Utils.EXTRA_DATA, Utils.MANAGE_NET_KEY);
            startActivity(intent);
        });

        binding.containerAppKeys.image.
                setBackground(ContextCompat.getDrawable(requireContext(), R.drawable.ic_folder_key_24dp));
        binding.containerAppKeys.title.setText(R.string.title_app_keys);
        binding.containerAppKeys.text.setVisibility(View.VISIBLE);
        binding.containerAppKeys.getRoot().setOnClickListener(v ->
                startActivity(new Intent(requireContext(), AppKeysActivity.class)));

        binding.containerScenes.image.
                setBackground(ContextCompat.getDrawable(requireContext(), R.drawable.ic_baseline_palette_24dp));
        binding.containerScenes.title.setText(R.string.title_scenes);
        binding.containerScenes.text.setVisibility(View.VISIBLE);
        binding.containerScenes.getRoot().setOnClickListener(v ->
                startActivity(new Intent(requireContext(), ScenesActivity.class)));

        binding.containerIvTestMode.image.
                setBackground(ContextCompat.getDrawable(requireContext(), R.drawable.ic_folder_key_24dp));
        binding.containerIvTestMode.title.setText(R.string.title_iv_test_mode);
        binding.containerIvTestMode.text.setText(R.string.iv_test_mode_summary);
        binding.containerIvTestMode.text.setVisibility(View.VISIBLE);

        binding.containerIvTestMode.actionChangeTestMode.setVisibility(View.VISIBLE);
        binding.containerIvTestMode.actionChangeTestMode.setChecked(mViewModel.getMeshManagerApi().isIvUpdateTestModeActive());
        binding.containerIvTestMode.actionChangeTestMode.setOnClickListener(v ->
                mViewModel.getMeshManagerApi().setIvUpdateTestModeActive(binding.containerIvTestMode.actionChangeTestMode.isChecked()));
        binding.containerIvTestMode.getRoot().setOnClickListener(v ->
                DialogFragmentError.newInstance(getString(R.string.info), getString(R.string.iv_test_mode_info))
                        .show(getChildFragmentManager(), null)
        );

        binding.containerLastModified.image.
                setBackground(ContextCompat.getDrawable(requireContext(), R.drawable.ic_time));
        binding.containerLastModified.title.setText(R.string.last_modified);
        binding.containerLastModified.text.setVisibility(View.VISIBLE);
        binding.containerLastModified.getRoot().setVisibility(View.VISIBLE);
        binding.containerLastModified.getRoot().setClickable(false);

        final LayoutContainerBinding containerVersion = binding.containerVersion;
        containerVersion.getRoot().setClickable(false);
        containerVersion.image.
                setBackground(ContextCompat.getDrawable(requireContext(), R.drawable.ic_puzzle));
        containerVersion.title.setText(R.string.summary_version);
        final TextView version = containerVersion.text;
        version.setVisibility(View.VISIBLE);
        try {
            version.setText(requireContext().getPackageManager().getPackageInfo(requireContext().getPackageName(), 0).versionName);
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }

        mViewModel.getNetworkLiveData().observe(getViewLifecycleOwner(), meshNetworkLiveData -> {
            if (meshNetworkLiveData != null) {
                binding.containerNetworkName.text.setText(meshNetworkLiveData.getNetworkName());
                binding.containerNetKeys.text.setText(String.valueOf(meshNetworkLiveData.getNetworkKeys().size()));
                binding.containerProvisioners.text.setText(String.valueOf(meshNetworkLiveData.getProvisioners().size()));
                binding.containerAppKeys.text.setText(String.valueOf(meshNetworkLiveData.getAppKeys().size()));
                binding.containerScenes.text.setText(String.valueOf(meshNetworkLiveData.getScenes().size()));
                binding.containerLastModified.text.setText(getDateTimeInstance().format(new Date(meshNetworkLiveData.getMeshNetwork().getTimestamp())));
            }
        });

        mViewModel.getNetworkLoadState().observe(getViewLifecycleOwner(), networkImportState -> {
            final String title = getString(R.string.title_network_import);
            final DialogFragmentMeshImportMsg fragment =
                    DialogFragmentMeshImportMsg.newInstance(R.drawable.ic_info_outline,
                            title, networkImportState);
            fragment.show(getChildFragmentManager(), null);
        });

        mViewModel.getNetworkExportState().observe(getViewLifecycleOwner(), networkExportState -> {
            final String title = getString(R.string.title_network_export);
            final DialogFragmentMeshExportMsg fragment =
                    DialogFragmentMeshExportMsg.newInstance(R.drawable.ic_info_outline,
                            title, networkExportState);
            fragment.show(getChildFragmentManager(), null);
        });

        return binding.getRoot();
    }

    @Override
    public void onCreateOptionsMenu(@NonNull final Menu menu, @NonNull final MenuInflater inflater) {
        inflater.inflate(R.menu.network_settings, menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull final MenuItem item) {
        final int id = item.getItemId();
        if (id == R.id.action_import_network) {
            final String title = getString(R.string.title_network_import);
            final String message = getString(R.string.network_import_rationale);
            final DialogFragmentMeshImport fragment = DialogFragmentMeshImport.newInstance(title, message);
            fragment.show(getChildFragmentManager(), null);
            return true;
        } else if (id == R.id.action_export_network) {
            startActivity(new Intent(requireContext(), ExportNetworkActivity.class));
            return true;
        } else if (id == R.id.action_reset_network) {
            final DialogFragmentResetNetwork dialogFragmentResetNetwork = DialogFragmentResetNetwork.
                    newInstance(getString(R.string.title_reset_network), getString(R.string.message_reset_network));
            dialogFragmentResetNetwork.show(getChildFragmentManager(), null);
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
        mViewModel.resetMeshNetwork();
    }

    @Override
    public void onNetworkImportConfirmed() {
        performFileSearch();
    }

    /**
     * Fires an intent to spin up the "file chooser" UI to select a file
     */
    private void performFileSearch() {
        fileSelector.launch("application/json");
    }
}
