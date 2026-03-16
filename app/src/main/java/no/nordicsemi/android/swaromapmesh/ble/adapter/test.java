//package no.nordicsemi.android.swaromesh;
//
//import android.os.Bundle;
//import android.text.Editable;
//import android.text.TextWatcher;
//import android.util.Log;
//import android.view.LayoutInflater;
//import android.view.View;
//import android.view.ViewGroup;
//import android.widget.AdapterView;
//import android.widget.ArrayAdapter;
//import android.widget.Button;
//import android.widget.LinearLayout;
//import android.widget.RadioButton;
//import android.widget.RadioGroup;
//import android.widget.Spinner;
//import android.widget.TextView;
//import android.widget.Toast;
//
//import androidx.annotation.NonNull;
//import androidx.annotation.Nullable;
//import androidx.fragment.app.Fragment;
//import androidx.lifecycle.ViewModelProvider;
//
//import com.google.android.material.textfield.TextInputEditText;
//
//import java.util.ArrayList;
//import java.util.Arrays;
//import java.util.List;
//
//import no.nordicsemi.android.swaromesh.adapter.ExtendedBluetoothDevice;
//import no.nordicsemi.android.swaromesh.ble.BleMeshManager;
//import no.nordicsemi.android.swaromesh.viewmodels.ScannerViewModel;
//import no.nordicsemi.android.swaromesh.viewmodels.SharedViewModel;
//
//public class DevicesFilterFragment extends Fragment {
//
//    private static final String TAG = "DevicesFilterFragment";
//
//    // 🔹 DEFAULT FILTER VALUE
//    private static final String DEFAULT_DEVICE_NAME = "";
//    private static final String DEFAULT_SELECTED_DEVICE = "Select no.nordicsemi.android.swaromesh.Device";
//
//    // 🔹 PREDEFINED DEVICE LIST
//    private static final List<String> PREDEFINED_DEVICES = Arrays.asList(
//            "Select no.nordicsemi.android.swaromesh.Device",  // Default item
//            "SW-RL01-006", "SW-RL02-012", "SW-RL03-016",
//            "SW-CLF01-100", "SW-CLE02-050", "SW-CLC03-150",
//            "SW-PSU01-30", "SW-PSD02-60", "SW-PSS04-60", "SW-PSR05-60",
//            "SW-DND01-03", "SW-DNU02-10", "SW-DNT03-10", "SW-DNR04-60",
//            "SW-DM01-004", "SW-CN01-AA", "SW-IR01-AA",
//            "SW-UIQP01-AA", "SW-UIQS02-AA", "SW-UIQB03-AA",
//            "SW-UIKP04-AA", "SW-UIKS05-AA", "SW-UIKB06-AA",
//            "SW-CS07-1N", "SW-PB08-AA", "SW-CS09-6N",
//            "SW-URC01-AA", "SW-URT02-AA", "SW-UITC01-10",
//            "SW-HUB02-AA", "SW-SSO01-AA", "SW-SHO02-AA",
//            "SW-SWO03-AA", "SW-SUVR04-AA", "SW-STH06-AA",
//            "SW-SAQ07-AA", "SW-SFG08-AA", "SW-SAP12-AA",
//            "SW-SOF09-AA", "SW-SCO218-AA", "SW-STD10-AA",
//            "SW-SWT01-AA", "SW-SFR05-AA", "SW-SWP11-AA",
//            "SW-STW13-AA", "SW-SRS17-AA", "SW-SGB14-AA",
//            "SW-SDS15-AA", "SW-SSM16-AA", "SW-SVL01-AA",
//            "SW-SAS01-AA", "SW-HWS01-AA", "SW-MRB01-AA",
//            "SW-MCS02-AA", "SW-LR97-10", "SW-LR95-10",
//            "SW-LR00-05", "SW-LTW90-15", "SW-LRG00-28",
//            "SW-LS97-10", "SW-LGS02-AA", "SW-LGR03-AA"
//    );
//
//    // Signal strength thresholds (RSSI to percentage mapping)
//    private static final int RSSI_MIN = -100; // Weakest signal
//    private static final int RSSI_MAX = -40;  // Strongest signal
//    private static final int RSSI_RANGE = 60; // RSSI_MAX - RSSI_MIN
//
//    private TextInputEditText etDeviceName;
//    private Spinner spinnerDevices;
//    private Button btnApply, btnReset;
//    private RadioGroup rgSignalStrength;
//    private LinearLayout layoutSignalPreview;
//    private TextView tvSignalPreview;
//
//    private int selectedSignalPercentage = 0; // 0 = Default (no filter)
//    private int selectedSignalBars = 0; // 0 = Default
//
//    private ScannerViewModel scannerViewModel;
//    private SharedViewModel sharedViewModel;
//
//    // Store all unprovisioned devices for filtering
//    private List<ExtendedBluetoothDevice> allUnprovisionedDevices = new ArrayList<>();
//
//    // Current filter text and selected device
//    private String currentFilter = "";
//    private String selectedDevice = DEFAULT_SELECTED_DEVICE;
//
//    public DevicesFilterFragment() {
//        // Required empty public constructor
//    }
//
//    public static DevicesFilterFragment newInstance() {
//        return new DevicesFilterFragment();
//    }
//
//    @Override
//    public View onCreateView(
//            @NonNull LayoutInflater inflater,
//            ViewGroup container,
//            Bundle savedInstanceState) {
//
//        View view = inflater.inflate(
//                R.layout.fragment_devices_filter,
//                container,
//                false
//        );
//
//        // ✅ ViewModels (Activity scope)
//        scannerViewModel = new ViewModelProvider(requireActivity())
//                .get(ScannerViewModel.class);
//        sharedViewModel = new ViewModelProvider(requireActivity())
//                .get(SharedViewModel.class);
//
//        initUi(view);
//        setupDeviceSpinner();
//        setupDefaultValue();
//        setupTextWatcher();
//        setupActions();
//        setupSignalStrength();
//        observeScanResults();
//
//        return view;
//    }
//
//    private void initUi(View view) {
//        etDeviceName = view.findViewById(R.id.etDeviceName);
//        spinnerDevices = view.findViewById(R.id.spinnerDevices);
//        btnApply = view.findViewById(R.id.btnApply);
//        btnReset = view.findViewById(R.id.btnReset);
//        rgSignalStrength = view.findViewById(R.id.rgSignalStrength);
//        layoutSignalPreview = view.findViewById(R.id.layoutSignalPreview);
//        tvSignalPreview = view.findViewById(R.id.tvSignalPreview);
//    }
//
//    // ---------------------------------------------------------------------
//    // SIGNAL STRENGTH LOGIC WITH VISUAL PREVIEW
//    // ---------------------------------------------------------------------
//    private void setupSignalStrength() {
//        // Initial preview
//        updateSignalPreview(0);
//
//        rgSignalStrength.setOnCheckedChangeListener((group, checkedId) -> {
//            if (checkedId == R.id.rbSignalDefault) {
//                selectedSignalPercentage = 0;
//                selectedSignalBars = 0;
//                Log.i(TAG, "Signal Strength Selected: DEFAULT");
//
//            } else if (checkedId == R.id.rbSignal3Bars) {
//                selectedSignalPercentage = 20;
//                selectedSignalBars = 3;
//                Log.i(TAG, "Signal Strength Selected: 20% (3 bars)");
//
//            } else if (checkedId == R.id.rbSignal4Bars) {
//                selectedSignalPercentage = 60;
//                selectedSignalBars = 4;
//                Log.i(TAG, "Signal Strength Selected: 60% (4 bars)");
//
//            } else if (checkedId == R.id.rbSignal5Bars) {
//                selectedSignalPercentage = 100;
//                selectedSignalBars = 5;
//                Log.i(TAG, "Signal Strength Selected: 100% (5 bars)");
//            }
//
//            // Update visual preview
//            updateSignalPreview(selectedSignalBars);
//
//            // Apply filter in real-time
//            filterDevicesAndUpdateScanner();
//
//            Log.d(TAG, "Current Signal Filter Value: " + selectedSignalPercentage + "%");
//        });
//    }
//
//    private void updateSignalPreview(int bars) {
//        if (tvSignalPreview == null) return;
//
//        if (bars == 0) {
//            tvSignalPreview.setText(" Signal strength filter: OFF (showing all devices)");
//            tvSignalPreview.setTextColor(getResources().getColor(android.R.color.darker_gray));
//        } else {
//            StringBuilder signalBuilder = new StringBuilder(" Signal filter: ");
//            for (int i = 0; i < bars; i++) {
//                signalBuilder.append("■"); // Solid bar
//            }
//            for (int i = bars; i < 5; i++) {
//                signalBuilder.append("□"); // Empty bar
//            }
//            signalBuilder.append(" (minimum ").append(selectedSignalPercentage).append("%)");
//
//            tvSignalPreview.setText(signalBuilder.toString());
//            tvSignalPreview.setTextColor(getResources().getColor(android.R.color.holo_green_dark));
//        }
//    }
//
//    // ---------------------------------------------------------------------
//    // SETUP DEVICE SPINNER
//    // ---------------------------------------------------------------------
//    private void setupDeviceSpinner() {
//        // Create adapter for spinner
//        ArrayAdapter<String> adapter = new ArrayAdapter<>(
//                requireContext(),
//                android.R.layout.simple_spinner_item,
//                PREDEFINED_DEVICES
//        );
//        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
//        spinnerDevices.setAdapter(adapter);
//
//        // Set spinner selection listener
//        spinnerDevices.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
//            @Override
//            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
//                selectedDevice = PREDEFINED_DEVICES.get(position);
//
//                // Show toast when device is selected
//                if (!selectedDevice.equals(DEFAULT_SELECTED_DEVICE)) {
//                    Toast.makeText(requireContext(),
//                            "Selected: " + selectedDevice,
//                            Toast.LENGTH_SHORT).show();
//
//                    // Auto-fill the device name field with selected device
//                    etDeviceName.setText(selectedDevice);
//                }
//            }
//
//            @Override
//            public void onNothingSelected(AdapterView<?> parent) {
//                selectedDevice = DEFAULT_SELECTED_DEVICE;
//            }
//        });
//    }
//
//    // ---------------------------------------------------------------------
//    // SET DEFAULT FILTER
//    // ---------------------------------------------------------------------
//    private void setupDefaultValue() {
//        etDeviceName.setText(DEFAULT_DEVICE_NAME);
//        currentFilter = DEFAULT_DEVICE_NAME;
//
//        // Set spinner to default position
//        spinnerDevices.setSelection(0);
//
//        // Set default radio button
//        RadioButton rbDefault = getView() != null ? getView().findViewById(R.id.rbSignalDefault) : null;
//        if (rbDefault != null) {
//            rbDefault.setChecked(true);
//        }
//    }
//
//    // ---------------------------------------------------------------------
//    // TEXT WATCHER FOR REAL-TIME FILTERING
//    // ---------------------------------------------------------------------
//    private void setupTextWatcher() {
//        etDeviceName.addTextChangedListener(new TextWatcher() {
//            @Override
//            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
//
//            @Override
//            public void onTextChanged(CharSequence s, int start, int before, int count) {
//                currentFilter = s.toString().trim();
//                // If user types manually, clear spinner selection
//                if (!currentFilter.isEmpty()) {
//                    spinnerDevices.setSelection(0);
//                    selectedDevice = DEFAULT_SELECTED_DEVICE;
//                }
//                // Apply filter in real-time
//                filterDevicesAndUpdateScanner();
//            }
//
//            @Override
//            public void afterTextChanged(Editable s) {}
//        });
//    }
//
//    private void setupActions() {
//        btnApply.setOnClickListener(v -> {
//            applyFilter();
//
//            // Show toast with applied filter info
//            String filterInfo = getFilterSummary();
//
//            Toast.makeText(requireContext(),
//                    "Applied - " + filterInfo,
//                    Toast.LENGTH_LONG).show();
//
//            requireActivity()
//                    .getSupportFragmentManager()
//                    .popBackStack();
//        });
//
//        btnReset.setOnClickListener(v -> resetFilter());
//    }
//
//    // ---------------------------------------------------------------------
//    // OBSERVE SCAN RESULTS
//    // ---------------------------------------------------------------------
//    private void observeScanResults() {
//        scannerViewModel.getScannerRepository().getScannerResults()
//                .observe(getViewLifecycleOwner(), scannerLiveData -> {
//
//                    if (scannerLiveData != null && scannerLiveData.getDevices() != null) {
//
//                        // Store only unprovisioned devices (those with provisioning UUID)
//                        allUnprovisionedDevices.clear();
//
//                        for (ExtendedBluetoothDevice device : scannerLiveData.getDevices()) {
//                            if (isUnprovisionedDevice(device)) {
//                                allUnprovisionedDevices.add(device);
//                            }
//                        }
//
//                        // Apply current filter to update the scanner display
//                        filterDevicesAndUpdateScanner();
//
//                        Log.d(TAG, "Found " + allUnprovisionedDevices.size() + " unprovisioned devices");
//
//                        // Show toast with device count
//                        if (isAdded()) {
//                            Toast.makeText(requireContext(),
//                                    "Found " + allUnprovisionedDevices.size() + " unprovisioned devices",
//                                    Toast.LENGTH_SHORT).show();
//                        }
//                    }
//                });
//    }
//
//    // ---------------------------------------------------------------------
//    // CHECK IF DEVICE IS UNPROVISIONED
//    // ---------------------------------------------------------------------
//    private boolean isUnprovisionedDevice(ExtendedBluetoothDevice device) {
//        // Check if device is advertising with provisioning UUID
//        if (device.getScanResult() != null &&
//                device.getScanResult().getScanRecord() != null &&
//                device.getScanResult().getScanRecord().getServiceUuids() != null) {
//
//            return device.getScanResult().getScanRecord().getServiceUuids()
//                    .contains(BleMeshManager.MESH_PROVISIONING_UUID);
//        }
//        return false;
//    }
//
//    // ---------------------------------------------------------------------
//    // FILTER DEVICES AND UPDATE SCANNER (WITH SIGNAL STRENGTH)
//    // ---------------------------------------------------------------------
//    private void filterDevicesAndUpdateScanner() {
//        // Create filtered list based on current filter, selected device, and signal strength
//        List<ExtendedBluetoothDevice> filteredList = new ArrayList<>();
//
//        // Determine filter text to use (from spinner or manual entry)
//        String filterToUse;
//        if (!selectedDevice.equals(DEFAULT_SELECTED_DEVICE)) {
//            filterToUse = selectedDevice;
//        } else {
//            filterToUse = currentFilter;
//        }
//
//        for (ExtendedBluetoothDevice device : allUnprovisionedDevices) {
//            boolean passesNameFilter = true;
//            boolean passesSignalFilter = true;
//
//            // 1. Apply NAME FILTER (if any)
//            if (!filterToUse.isEmpty()) {
//                if (device.getName() == null ||
//                        !device.getName().toLowerCase().contains(filterToUse.toLowerCase())) {
//                    passesNameFilter = false;
//                }
//            }
//
//            // 2. Apply SIGNAL STRENGTH FILTER (if not Default)
//            if (selectedSignalPercentage > 0 && device.getScanResult() != null) {
//                int rssi = device.getScanResult().getRssi();
//
//                // Convert RSSI to percentage
//                int signalPercentage = convertRssiToPercentage(rssi);
//
//                // Check if device meets the minimum signal strength requirement
//                if (signalPercentage < selectedSignalPercentage) {
//                    passesSignalFilter = false;
//                }
//            }
//
//            // Add device if it passes ALL active filters
//            if (passesNameFilter && passesSignalFilter) {
//                filteredList.add(device);
//            }
//        }
//
//        Log.d(TAG, "Filter applied: Name='" + filterToUse +
//                "', Signal=" + selectedSignalPercentage +
//                "% - showing " + filteredList.size() + " devices");
//
//        // Update the scanner display
//        updateScannerDisplay(filteredList);
//    }
//
//    // ---------------------------------------------------------------------
//    // CONVERT RSSI TO PERCENTAGE
//    // ---------------------------------------------------------------------
//    private int convertRssiToPercentage(int rssi) {
//        // Clamp RSSI to expected range
//        int clampedRssi = Math.max(RSSI_MIN, Math.min(RSSI_MAX, rssi));
//
//        // Convert to percentage: -100 = 0%, -40 = 100%
//        return (int) (((clampedRssi - RSSI_MIN) * 100) / RSSI_RANGE);
//    }
//
//    // ---------------------------------------------------------------------
//    // GET FILTER SUMMARY STRING
//    // ---------------------------------------------------------------------
//    private String getFilterSummary() {
//        StringBuilder summary = new StringBuilder();
//
//        if (!selectedDevice.equals(DEFAULT_SELECTED_DEVICE)) {
//            summary.append("no.nordicsemi.android.swaromesh.Device: ").append(selectedDevice);
//        } else if (!currentFilter.isEmpty()) {
//            summary.append("Name: ").append(currentFilter);
//        }
//
//        if (selectedSignalPercentage > 0) {
//            if (summary.length() > 0) {
//                summary.append(", ");
//            }
//            summary.append("Signal: ").append(selectedSignalBars).append(" bars (").append(selectedSignalPercentage).append("%)");
//        }
//
//        if (summary.length() == 0) {
//            summary.append("All devices");
//        }
//
//        return summary.toString();
//    }
//
//    // ---------------------------------------------------------------------
//    // UPDATE SCANNER DISPLAY
//    // ---------------------------------------------------------------------
//    private void updateScannerDisplay(List<ExtendedBluetoothDevice> filteredDevices) {
//        // Store the filter in SharedViewModel for the activity to observe
//        sharedViewModel.setDeviceNameFilter(currentFilter);
//        sharedViewModel.setSelectedDevice(selectedDevice);
//        // You'll need to add this method to SharedViewModel
//        // sharedViewModel.setSignalStrengthFilter(selectedSignalPercentage);
//    }
//
//    // ---------------------------------------------------------------------
//    // APPLY FILTER (called when Apply button is clicked)
//    // ---------------------------------------------------------------------
//    private void applyFilter() {
//        String deviceName = etDeviceName.getText() != null
//                ? etDeviceName.getText().toString().trim()
//                : "";
//
//        Log.i(TAG, "----- APPLY FILTER -----");
//        Log.i(TAG, "Unprovisioned no.nordicsemi.android.swaromesh.Device Name Filter: " + deviceName);
//        Log.i(TAG, "Selected no.nordicsemi.android.swaromesh.Device from Spinner: " + selectedDevice);
//        Log.i(TAG, "Signal Strength Filter: " + selectedSignalPercentage + "% (" + selectedSignalBars + " bars)");
//
//        // Store the filter in SharedViewModel for persistence
//        sharedViewModel.setDeviceNameFilter(deviceName);
//        sharedViewModel.setSelectedDevice(selectedDevice);
//        // sharedViewModel.setSignalStrengthFilter(selectedSignalPercentage);
//
//        // Apply the filter one last time
//        filterDevicesAndUpdateScanner();
//
//        // Show summary toast
//        showFilterSummaryToast();
//    }
//
//    // ---------------------------------------------------------------------
//    // SHOW FILTER SUMMARY TOAST
//    // ---------------------------------------------------------------------
//    private void showFilterSummaryToast() {
//        if (!isAdded()) return;
//
//        String message = getFilterSummary();
//        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show();
//    }
//
//    // ---------------------------------------------------------------------
//    // RESET FILTER
//    // ---------------------------------------------------------------------
//    private void resetFilter() {
//        etDeviceName.setText("");
//        spinnerDevices.setSelection(0);  // Reset spinner to "Select no.nordicsemi.android.swaromesh.Device"
//
//        // Reset signal strength to default
//        RadioButton rbDefault = getView() != null ? getView().findViewById(R.id.rbSignalDefault) : null;
//        if (rbDefault != null) {
//            rbDefault.setChecked(true);
//        }
//        selectedSignalPercentage = 0;
//        selectedSignalBars = 0;
//        updateSignalPreview(0);
//
//        currentFilter = "";
//        selectedDevice = DEFAULT_SELECTED_DEVICE;
//
//        // Reset filter in SharedViewModel
//        sharedViewModel.setDeviceNameFilter("");
//        sharedViewModel.setSelectedDevice(DEFAULT_SELECTED_DEVICE);
//        // sharedViewModel.setSignalStrengthFilter(0);
//
//        // Show all devices again
//        filterDevicesAndUpdateScanner();
//
//        Log.i(TAG, "Filters reset - showing all unprovisioned devices");
//
//        Toast.makeText(requireContext(),
//                "Filters reset - showing all devices",
//                Toast.LENGTH_SHORT).show();
//    }
//
//    @Override
//    public void onDestroyView() {
//        super.onDestroyView();
//        // Optional: Clear filter when fragment is destroyed
//        // Uncomment if you want to clear filter when closing
//        // sharedViewModel.setDeviceNameFilter("");
//    }
//}