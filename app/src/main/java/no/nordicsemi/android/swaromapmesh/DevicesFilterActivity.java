//package no.nordicsemi.android.swaromapmesh;
//
//import android.os.Bundle;
//import android.util.Log;
//import android.view.LayoutInflater;
//import android.view.View;
//import android.view.ViewGroup;
//import android.widget.AdapterView;
//import android.widget.ArrayAdapter;
//import android.widget.Button;
//import android.widget.RadioGroup;
//import android.widget.Spinner;
//import android.widget.Toast;
//
//import androidx.annotation.NonNull;
//import androidx.fragment.app.Fragment;
//import androidx.lifecycle.ViewModelProvider;
//
//import java.util.ArrayList;
//import java.util.Arrays;
//import java.util.List;
//
//import no.nordicsemi.android.swaromapmesh.adapter.ExtendedBluetoothDevice;
//import no.nordicsemi.android.swaromapmesh.ble.BleMeshManager;
//import no.nordicsemi.android.swaromapmesh.ble.adapter.DevicesAdapter;
//import no.nordicsemi.android.swaromapmesh.viewmodels.ScannerViewModel;
//import no.nordicsemi.android.swaromapmesh.viewmodels.SharedViewModel;
//
//public class DevicesFilterActivity extends Fragment {
//
//    private static final String TAG = "DevicesFilterFragment";
//
//    private static final String DEFAULT_SELECTED_DEVICE = "All Device";
//
//    private static final List<String> PREDEFINED_DEVICES = Arrays.asList(
//            "All Device",
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
//    // UI
//    private Spinner    spinnerDevices;
//    private RadioGroup rgSignalStrength;
//    private Button     btnApply, btnReset;
//
//    // ViewModels
//    private ScannerViewModel scannerViewModel;
//    private SharedViewModel  sharedViewModel;
//
//    // Local state
//    private List<ExtendedBluetoothDevice> allUnprovisionedDevices = new ArrayList<>();
//    private String selectedDevice         = DEFAULT_SELECTED_DEVICE;
//    private int    currentSignalThreshold = DevicesAdapter.SIGNAL_DEFAULT;
//
//    public DevicesFilterActivity() {}
//
//    public static DevicesFilterActivity newInstance() {
//        return new DevicesFilterActivity();
//    }
//
//    @Override
//    public View onCreateView(@NonNull LayoutInflater inflater,
//                             ViewGroup container,
//                             Bundle savedInstanceState) {
//
//        View view = inflater.inflate(R.layout.activity_devices_filter, container, false);
//
//        scannerViewModel = new ViewModelProvider(requireActivity()).get(ScannerViewModel.class);
//        sharedViewModel  = new ViewModelProvider(requireActivity()).get(SharedViewModel.class);
//
//        initUi(view);
//        setupDeviceSpinner();
//        restoreSavedState();
//        setupSignalRadioGroup();
//        setupButtons();
//        observeScanResults();
//
//        return view;
//    }
//
//    // -------------------------------------------------------------------------
//    // UI Init
//    // -------------------------------------------------------------------------
//
//    private void initUi(View view) {
//        spinnerDevices   = view.findViewById(R.id.spinnerDevices);
//        rgSignalStrength = view.findViewById(R.id.rgSignalStrength);
//        btnApply         = view.findViewById(R.id.btnApply);
//        btnReset         = view.findViewById(R.id.btnReset);
//    }
//
//    // -------------------------------------------------------------------------
//    // Restore previously saved filter state
//    // -------------------------------------------------------------------------
//
//    private void restoreSavedState() {
//        // Restore spinner selection
//        String savedDevice = sharedViewModel.getSelectedDeviceValue();
//        selectedDevice = savedDevice;
//        int spinnerPos = PREDEFINED_DEVICES.indexOf(savedDevice);
//        spinnerDevices.setSelection(spinnerPos >= 0 ? spinnerPos : 0);
//
//        // Restore signal threshold — only Default or 100%
//        int savedThreshold = sharedViewModel.getSignalThresholdValue();
//        currentSignalThreshold = savedThreshold;
//        if (savedThreshold == DevicesAdapter.SIGNAL_100) {
//            rgSignalStrength.check(R.id.rbSignal5Bars);
//        } else {
//            rgSignalStrength.check(R.id.rbSignalDefault);
//        }
//    }
//
//    // -------------------------------------------------------------------------
//    // Spinner setup
//    // -------------------------------------------------------------------------
//
//    private void setupDeviceSpinner() {
//        ArrayAdapter<String> adapter = new ArrayAdapter<>(
//                requireContext(),
//                android.R.layout.simple_spinner_item,
//                PREDEFINED_DEVICES);
//        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
//        spinnerDevices.setAdapter(adapter);
//
//        spinnerDevices.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
//            @Override
//            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
//                selectedDevice = PREDEFINED_DEVICES.get(position);
//                if (!selectedDevice.equals(DEFAULT_SELECTED_DEVICE)) {
//                    Toast.makeText(requireContext(),
//                            "Selected: " + selectedDevice, Toast.LENGTH_SHORT).show();
//                }
//                filterDevicesAndUpdate();
//            }
//
//            @Override
//            public void onNothingSelected(AdapterView<?> parent) {
//                selectedDevice = DEFAULT_SELECTED_DEVICE;
//            }
//        });
//    }
//
//    // -------------------------------------------------------------------------
//    // Signal strength RadioGroup — Default or 100% only
//    // -------------------------------------------------------------------------
//
//    private void setupSignalRadioGroup() {
//        rgSignalStrength.setOnCheckedChangeListener((group, checkedId) -> {
//            if (checkedId == R.id.rbSignal5Bars) {
//                currentSignalThreshold = DevicesAdapter.SIGNAL_100;
//            } else {
//                currentSignalThreshold = DevicesAdapter.SIGNAL_DEFAULT;
//            }
//            filterDevicesAndUpdate();
//            Log.d(TAG, "Signal threshold: " + currentSignalThreshold + "%");
//        });
//    }
//
//    // -------------------------------------------------------------------------
//    // Apply + Reset buttons
//    // -------------------------------------------------------------------------
//
//    private void setupButtons() {
//
//        btnApply.setOnClickListener(v -> {
//            sharedViewModel.setSelectedDevice(selectedDevice);
//            sharedViewModel.setSignalThreshold(currentSignalThreshold);
//            sharedViewModel.setDeviceNameFilter("");
//
//            filterDevicesAndUpdate();
//
//            String filterInfo = selectedDevice.equals(DEFAULT_SELECTED_DEVICE)
//                    ? "All devices"
//                    : "Device: " + selectedDevice;
//
//            if (currentSignalThreshold == DevicesAdapter.SIGNAL_100) {
//                filterInfo += " | Signal ≥ 100%";
//            }
//
//            Toast.makeText(requireContext(),
//                    "Applied — " + filterInfo, Toast.LENGTH_LONG).show();
//
//            requireActivity().getSupportFragmentManager().popBackStack();
//        });
//
//        btnReset.setOnClickListener(v -> resetFilter());
//    }
//
//    // -------------------------------------------------------------------------
//    // Reset all filters
//    // -------------------------------------------------------------------------
//
//    private void resetFilter() {
//        // Reset UI
//        spinnerDevices.setSelection(0);
//        rgSignalStrength.check(R.id.rbSignalDefault);
//
//        // Reset local state
//        selectedDevice         = DEFAULT_SELECTED_DEVICE;
//        currentSignalThreshold = DevicesAdapter.SIGNAL_DEFAULT;
//
//        // Reset SharedViewModel
//        sharedViewModel.setSelectedDevice(DEFAULT_SELECTED_DEVICE);
//        sharedViewModel.setSignalThreshold(DevicesAdapter.SIGNAL_DEFAULT);
//        sharedViewModel.setDeviceNameFilter("");
//
//        filterDevicesAndUpdate();
//
//        Toast.makeText(requireContext(),
//                "Filters reset — showing all devices", Toast.LENGTH_SHORT).show();
//
//        Log.i(TAG, "Filters reset");
//    }
//
//    // -------------------------------------------------------------------------
//    // Observe BLE scan results
//    // -------------------------------------------------------------------------
//
//    private void observeScanResults() {
//        scannerViewModel.getScannerRepository().getScannerResults()
//                .observe(getViewLifecycleOwner(), scannerLiveData -> {
//                    if (scannerLiveData != null && scannerLiveData.getDevices() != null) {
//                        allUnprovisionedDevices.clear();
//                        for (ExtendedBluetoothDevice device : scannerLiveData.getDevices()) {
//                            if (isUnprovisionedDevice(device)) {
//                                allUnprovisionedDevices.add(device);
//                            }
//                        }
//                        filterDevicesAndUpdate();
//                        Log.d(TAG, "Unprovisioned devices: " + allUnprovisionedDevices.size());
//                    }
//                });
//    }
//
//    // -------------------------------------------------------------------------
//    // Check if device is unprovisioned
//    // -------------------------------------------------------------------------
//
//    private boolean isUnprovisionedDevice(ExtendedBluetoothDevice device) {
//        if (device.getScanResult() != null
//                && device.getScanResult().getScanRecord() != null
//                && device.getScanResult().getScanRecord().getServiceUuids() != null) {
//            return device.getScanResult().getScanRecord().getServiceUuids()
//                    .contains(BleMeshManager.MESH_PROVISIONING_UUID);
//        }
//        return false;
//    }
//
//    // -------------------------------------------------------------------------
//    // Filter logic — device type AND signal strength
//    // -------------------------------------------------------------------------
//
//    private void filterDevicesAndUpdate() {
//        List<ExtendedBluetoothDevice> filteredList = new ArrayList<>();
//
//        boolean hasDeviceFilter = !selectedDevice.equals(DEFAULT_SELECTED_DEVICE);
//        boolean hasSignalFilter = currentSignalThreshold != DevicesAdapter.SIGNAL_DEFAULT;
//
//        for (ExtendedBluetoothDevice device : allUnprovisionedDevices) {
//
//            boolean deviceOk = !hasDeviceFilter
//                    || (device.getName() != null
//                    && device.getName().toLowerCase()
//                    .contains(selectedDevice.toLowerCase()));
//
//            boolean signalOk = !hasSignalFilter
//                    || meetsSignalThreshold(device, currentSignalThreshold);
//
//            if (deviceOk && signalOk) {
//                filteredList.add(device);
//            }
//        }
//
//        sharedViewModel.setFilteredDevices(filteredList);
//
//        Log.d(TAG, "Filter — device:'" + selectedDevice
//                + "' signal:" + currentSignalThreshold
//                + "% → showing " + filteredList.size() + " devices");
//    }
//
//    private boolean meetsSignalThreshold(ExtendedBluetoothDevice device, int threshold) {
//        int rssiPercent = (int) (100.0f * (127.0f + device.getRssi()) / (127.0f + 20.0f));
//        return rssiPercent >= threshold;
//    }
//
//    @Override
//    public void onDestroyView() {
//        super.onDestroyView();
//        spinnerDevices   = null;
//        rgSignalStrength = null;
//        btnApply         = null;
//        btnReset         = null;
//    }
//}