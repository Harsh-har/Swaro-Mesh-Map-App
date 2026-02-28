package no.nordicsemi.android.swaromesh;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import no.nordicsemi.android.swaromesh.adapter.ExtendedBluetoothDevice;
import no.nordicsemi.android.swaromesh.ble.BleMeshManager;
import no.nordicsemi.android.swaromesh.ble.ScannerActivity;
import no.nordicsemi.android.swaromesh.ble.adapter.DevicesAdapter;
import no.nordicsemi.android.swaromesh.viewmodels.ScannerViewModel;
import no.nordicsemi.android.swaromesh.viewmodels.SharedViewModel;

public class DevicesFilterFragment extends Fragment {

    private static final String TAG = "DevicesFilterFragment";

    // Default filter values
    private static final String DEFAULT_DEVICE_NAME    = "";
    private static final String DEFAULT_SELECTED_DEVICE = "All no.nordicsemi.android.swaromesh.Device";

    // Predefined device list
    private static final List<String> PREDEFINED_DEVICES = Arrays.asList(
            "All no.nordicsemi.android.swaromesh.Device",
            "SW-RL01-006", "SW-RL02-012", "SW-RL03-016",
            "SW-CLF01-100", "SW-CLE02-050", "SW-CLC03-150",
            "SW-PSU01-30", "SW-PSD02-60", "SW-PSS04-60", "SW-PSR05-60",
            "SW-DND01-03", "SW-DNU02-10", "SW-DNT03-10", "SW-DNR04-60",
            "SW-DM01-004", "SW-CN01-AA", "SW-IR01-AA",
            "SW-UIQP01-AA", "SW-UIQS02-AA", "SW-UIQB03-AA",
            "SW-UIKP04-AA", "SW-UIKS05-AA", "SW-UIKB06-AA",
            "SW-CS07-1N", "SW-PB08-AA", "SW-CS09-6N",
            "SW-URC01-AA", "SW-URT02-AA", "SW-UITC01-10",
            "SW-HUB02-AA", "SW-SSO01-AA", "SW-SHO02-AA",
            "SW-SWO03-AA", "SW-SUVR04-AA", "SW-STH06-AA",
            "SW-SAQ07-AA", "SW-SFG08-AA", "SW-SAP12-AA",
            "SW-SOF09-AA", "SW-SCO218-AA", "SW-STD10-AA",
            "SW-SWT01-AA", "SW-SFR05-AA", "SW-SWP11-AA",
            "SW-STW13-AA", "SW-SRS17-AA", "SW-SGB14-AA",
            "SW-SDS15-AA", "SW-SSM16-AA", "SW-SVL01-AA",
            "SW-SAS01-AA", "SW-HWS01-AA", "SW-MRB01-AA",
            "SW-MCS02-AA", "SW-LR97-10", "SW-LR95-10",
            "SW-LR00-05", "SW-LTW90-15", "SW-LRG00-28",
            "SW-LS97-10", "SW-LGS02-AA", "SW-LGR03-AA"
    );

    // UI
    private TextInputEditText etDeviceName;
    private Spinner           spinnerDevices;
    private RadioGroup        rgSignalStrength;   // ← NEW
    private Button            btnApply, btnReset;

    // ViewModels
    private ScannerViewModel scannerViewModel;
    private SharedViewModel  sharedViewModel;

    // Local state
    private List<ExtendedBluetoothDevice> allUnprovisionedDevices = new ArrayList<>();
    private String currentFilter    = "";
    private String selectedDevice   = DEFAULT_SELECTED_DEVICE;
    private int    currentSignalThreshold = DevicesAdapter.SIGNAL_DEFAULT; // ← NEW

    public DevicesFilterFragment() {}

    public static DevicesFilterFragment newInstance() {
        return new DevicesFilterFragment();
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container,
                             Bundle savedInstanceState) {

        View view = inflater.inflate(R.layout.fragment_devices_filter, container, false);

        scannerViewModel = new ViewModelProvider(requireActivity()).get(ScannerViewModel.class);
        sharedViewModel  = new ViewModelProvider(requireActivity()).get(SharedViewModel.class);

        initUi(view);
        setupDeviceSpinner();
        restoreSavedState();       // ← restore all 3 filters from SharedViewModel
        setupTextWatcher();
        setupSignalRadioGroup();   // ← NEW
        setupActions();
        observeScanResults();

        return view;
    }

    // -------------------------------------------------------------------------
    // UI Init
    // -------------------------------------------------------------------------

    private void initUi(View view) {
        etDeviceName     = view.findViewById(R.id.etDeviceName);
        spinnerDevices   = view.findViewById(R.id.spinnerDevices);
        rgSignalStrength = view.findViewById(R.id.rgSignalStrength);   // ← NEW
        btnApply         = view.findViewById(R.id.btnApply);
        btnReset         = view.findViewById(R.id.btnReset);
    }

    // -------------------------------------------------------------------------
    // Restore previously saved filter state into all UI controls
    // -------------------------------------------------------------------------

    private void restoreSavedState() {
        // Restore device name text
        String savedName = sharedViewModel.getDeviceNameFilterValue();
        etDeviceName.setText(savedName);
        currentFilter = savedName;

        // Restore spinner selection
        String savedDevice = sharedViewModel.getSelectedDeviceValue();
        selectedDevice = savedDevice;
        int spinnerPos = PREDEFINED_DEVICES.indexOf(savedDevice);
        spinnerDevices.setSelection(spinnerPos >= 0 ? spinnerPos : 0);

        // Restore signal threshold radio button ← NEW
        int savedThreshold = sharedViewModel.getSignalThresholdValue();
        currentSignalThreshold = savedThreshold;
        if (savedThreshold == DevicesAdapter.SIGNAL_20) {
            rgSignalStrength.check(R.id.rbSignal3Bars);
        } else if (savedThreshold == DevicesAdapter.SIGNAL_60) {
            rgSignalStrength.check(R.id.rbSignal4Bars);
        } else if (savedThreshold == DevicesAdapter.SIGNAL_100) {
            rgSignalStrength.check(R.id.rbSignal5Bars);
        } else {
            rgSignalStrength.check(R.id.rbSignalDefault);
        }
    }

    // -------------------------------------------------------------------------
    // Spinner setup
    // -------------------------------------------------------------------------

    private void setupDeviceSpinner() {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_spinner_item,
                PREDEFINED_DEVICES);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerDevices.setAdapter(adapter);

        spinnerDevices.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                selectedDevice = PREDEFINED_DEVICES.get(position);

                if (selectedDevice.equals(DEFAULT_SELECTED_DEVICE)) {

                    // 👇 Clear text when "All no.nordicsemi.android.swaromesh.Device" selected
                    etDeviceName.setText("");
                    currentFilter = "";

                } else {

                    Toast.makeText(requireContext(),
                            "Selected: " + selectedDevice, Toast.LENGTH_SHORT).show();

                    // Auto-fill text field
                    etDeviceName.setText(selectedDevice);
                    currentFilter = selectedDevice;
                }

                // Apply filter immediately
                filterDevicesAndUpdateScanner();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                selectedDevice = DEFAULT_SELECTED_DEVICE;
                etDeviceName.setText("");
                currentFilter = "";
            }
        });    }

    // -------------------------------------------------------------------------
    // Text watcher — real-time name filter
    // -------------------------------------------------------------------------

    private void setupTextWatcher() {
        etDeviceName.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void afterTextChanged(Editable s) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                currentFilter = s.toString().trim();
                filterDevicesAndUpdateScanner();
            }
        });
    }

    // -------------------------------------------------------------------------
    // Signal strength RadioGroup ← NEW
    // -------------------------------------------------------------------------

    private void setupSignalRadioGroup() {
        rgSignalStrength.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.rbSignal3Bars) {
                currentSignalThreshold = DevicesAdapter.SIGNAL_20;
            } else if (checkedId == R.id.rbSignal4Bars) {
                currentSignalThreshold = DevicesAdapter.SIGNAL_60;
            } else if (checkedId == R.id.rbSignal5Bars) {
                currentSignalThreshold = DevicesAdapter.SIGNAL_100;
            } else {
                currentSignalThreshold = DevicesAdapter.SIGNAL_DEFAULT;
            }

            // Apply in real-time as user changes signal selection
            filterDevicesAndUpdateScanner();

            Log.d(TAG, "Signal threshold changed: " + currentSignalThreshold + "%");
        });
    }

    // -------------------------------------------------------------------------
    // Apply / Reset buttons
    // -------------------------------------------------------------------------

    private void setupActions() {
        btnApply.setOnClickListener(v -> {
            applyFilter();

            String filterInfo;
            if (!selectedDevice.equals(DEFAULT_SELECTED_DEVICE)) {
                filterInfo = "no.nordicsemi.android.swaromesh.Device: " + selectedDevice;
            } else if (!currentFilter.isEmpty()) {
                filterInfo = "Name: " + currentFilter;
            } else {
                filterInfo = "All devices";
            }

            // Append signal info if active
            if (currentSignalThreshold != DevicesAdapter.SIGNAL_DEFAULT) {
                filterInfo += " | Signal ≥ " + currentSignalThreshold + "%";
            }

            Toast.makeText(requireContext(),
                    "Applied — " + filterInfo, Toast.LENGTH_LONG).show();

            requireActivity().getSupportFragmentManager().popBackStack();
        });

        btnReset.setOnClickListener(v -> resetFilter());
    }

    // -------------------------------------------------------------------------
    // Observe scan results
    // -------------------------------------------------------------------------

    private void observeScanResults() {
        scannerViewModel.getScannerRepository().getScannerResults()
                .observe(getViewLifecycleOwner(), scannerLiveData -> {
                    if (scannerLiveData != null && scannerLiveData.getDevices() != null) {
                        allUnprovisionedDevices.clear();
                        for (ExtendedBluetoothDevice device : scannerLiveData.getDevices()) {
                            if (isUnprovisionedDevice(device)) {
                                allUnprovisionedDevices.add(device);
                            }
                        }
                        filterDevicesAndUpdateScanner();
                        Log.d(TAG, "Unprovisioned devices found: " + allUnprovisionedDevices.size());
                    }
                });
    }

    // -------------------------------------------------------------------------
    // Check unprovisioned
    // -------------------------------------------------------------------------

    private boolean isUnprovisionedDevice(ExtendedBluetoothDevice device) {
        if (device.getScanResult() != null
                && device.getScanResult().getScanRecord() != null
                && device.getScanResult().getScanRecord().getServiceUuids() != null) {
            return device.getScanResult().getScanRecord().getServiceUuids()
                    .contains(BleMeshManager.MESH_PROVISIONING_UUID);
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Core filter logic — name AND signal combined ← UPDATED
    // -------------------------------------------------------------------------

    private void filterDevicesAndUpdateScanner() {
        List<ExtendedBluetoothDevice> filteredList = new ArrayList<>();

        // Spinner takes priority over typed name
        String nameFilterToUse = !selectedDevice.equals(DEFAULT_SELECTED_DEVICE)
                ? selectedDevice
                : currentFilter;

        boolean hasNameFilter   = !nameFilterToUse.isEmpty();
        boolean hasSignalFilter = currentSignalThreshold != DevicesAdapter.SIGNAL_DEFAULT;

        for (ExtendedBluetoothDevice device : allUnprovisionedDevices) {

            // --- Name check ---
            boolean nameOk = !hasNameFilter
                    || (device.getName() != null
                    && device.getName().toLowerCase()
                    .contains(nameFilterToUse.toLowerCase()));

            // --- Signal check ---
            boolean signalOk = !hasSignalFilter
                    || meetsSignalThreshold(device, currentSignalThreshold);

            // no.nordicsemi.android.swaromesh.Device must pass BOTH filters
            if (nameOk && signalOk) {
                filteredList.add(device);

                // Toast when exact match found
                if (hasNameFilter && device.getName() != null
                        && device.getName().equalsIgnoreCase(nameFilterToUse)) {
                    showDeviceFoundToast(device.getName());
                }
            }
        }

        updateScannerDisplay(filteredList);

        Log.d(TAG, "Filter — name:'" + nameFilterToUse
                + "' signal:" + currentSignalThreshold
                + "% → showing " + filteredList.size() + " devices");
    }

    /**
     * Returns true if device RSSI percentage meets the minimum threshold.
     * Formula: rssiPercent = 100 * (127 + rssi) / (127 + 20)
     */
    private boolean meetsSignalThreshold(ExtendedBluetoothDevice device, int threshold) {
        int rssiPercent = (int) (100.0f * (127.0f + device.getRssi()) / (127.0f + 20.0f));
        return rssiPercent >= threshold;
    }

    // -------------------------------------------------------------------------
    // Update scanner display via SharedViewModel
    // -------------------------------------------------------------------------

    private void updateScannerDisplay(List<ExtendedBluetoothDevice> filteredDevices) {
        sharedViewModel.setDeviceNameFilter(currentFilter);
        sharedViewModel.setSelectedDevice(selectedDevice);
        sharedViewModel.setSignalThreshold(currentSignalThreshold);   // ← NEW
    }

    // -------------------------------------------------------------------------
    // Apply filter (called on Apply button)
    // -------------------------------------------------------------------------

    private void applyFilter() {
        String deviceName = etDeviceName.getText() != null
                ? etDeviceName.getText().toString().trim()
                : "";

        Log.i(TAG, "----- APPLY FILTER -----");
        Log.i(TAG, "Name filter: " + deviceName);
        Log.i(TAG, "Spinner selection: " + selectedDevice);
        Log.i(TAG, "Signal threshold: " + currentSignalThreshold + "%");

        // Persist all 3 values
        sharedViewModel.setDeviceNameFilter(deviceName);
        sharedViewModel.setSelectedDevice(selectedDevice);
        sharedViewModel.setSignalThreshold(currentSignalThreshold);   // ← NEW

        // Final filter pass
        filterDevicesAndUpdateScanner();
    }

    // -------------------------------------------------------------------------
    // Reset all filters
    // -------------------------------------------------------------------------

    private void resetFilter() {
        // Reset UI
        etDeviceName.setText("");
        spinnerDevices.setSelection(0);
        rgSignalStrength.check(R.id.rbSignalDefault);   // ← NEW

        // Reset local state
        currentFilter          = "";
        selectedDevice         = DEFAULT_SELECTED_DEVICE;
        currentSignalThreshold = DevicesAdapter.SIGNAL_DEFAULT;   // ← NEW

        // Reset SharedViewModel
        sharedViewModel.setDeviceNameFilter("");
        sharedViewModel.setSelectedDevice(DEFAULT_SELECTED_DEVICE);
        sharedViewModel.setSignalThreshold(DevicesAdapter.SIGNAL_DEFAULT);   // ← NEW

        filterDevicesAndUpdateScanner();

        Log.i(TAG, "Filters reset — showing all unprovisioned devices");
        Toast.makeText(requireContext(),
                "Filters reset — showing all devices", Toast.LENGTH_SHORT).show();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void showDeviceFoundToast(String deviceName) {
        if (isAdded()) {
            Toast.makeText(requireContext(),
                    "✓ no.nordicsemi.android.swaromesh.Device found: " + deviceName, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
    }
}