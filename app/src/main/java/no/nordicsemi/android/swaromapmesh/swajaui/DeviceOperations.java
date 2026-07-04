package no.nordicsemi.android.swaromapmesh.swajaui;

import android.content.Context;
import android.graphics.RectF;
import android.util.Log;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.Map;
import java.util.Set;

import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import no.nordicsemi.android.swaromapmesh.swajaui.Svg_Operations.DeviceInfo;
import no.nordicsemi.android.swaromapmesh.swajaui.Svg_Operations.SvgParsers;
import no.nordicsemi.android.swaromapmesh.utils.DeviceCodes;

public class DeviceOperations {
    private static final String TAG = "DeviceOperations";

    public interface DeviceCallback {
        void onDataChanged();
        void enterAddMode(String category);
        void exitAddMode();
        float[] getDraggableIconCoords();
        float[] touchToSvgCoords(float x, float y);
        String getCurrentFocusAreaId();
    }

    private final Context context;
    private final DeviceCallback callback;

    public DeviceOperations(Context context, DeviceCallback callback) {
        this.context = context;
        this.callback = callback;
    }

    public void showCategorySelectionDialog() {
        String[] codes = {
                DeviceCodes.CONTROL_NODE,
                DeviceCodes.STRIP_NODE,
                DeviceCodes.LC_NODE,
                DeviceCodes.AC_NODE,
                DeviceCodes.RELAY_NODE,
                DeviceCodes.FAN_NODE,
                "Switch Plate"
        };

        String[] displayNames = new String[codes.length + 1];
        for (int i = 0; i < codes.length; i++) {
            String name = DeviceCodes.getName(codes[i]);
            displayNames[i] = (name != null) ? name : codes[i];
        }
        displayNames[codes.length] = "Manual Entry...";

        new MaterialAlertDialogBuilder(context)
                .setTitle("Select Device Category")
                .setItems(displayNames, (dialog, which) -> {
                    if (which == displayNames.length - 1) {
                        showManualCategoryDialog();
                    } else {
                        String selectedCategory = (which < codes.length) ? codes[which] : displayNames[which];
                        callback.enterAddMode(selectedCategory);
                    }
                })
                .show();
    }

    private void showManualCategoryDialog() {
        final EditText input = new EditText(context);
        input.setHint("Category Name ");
        new MaterialAlertDialogBuilder(context)
                .setTitle("Enter Category")
                .setView(input)
                .setPositiveButton("OK", (dialog, which) -> {
                    String cat = input.getText().toString().trim();
                    if (!cat.isEmpty()) {
                        callback.enterAddMode(cat);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    public void showDeviceInfoDialog(String selectedCategory, Map<String, DeviceInfo> deviceMap, Document svgDocument, SvgParsers svgParser) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 20, 40, 20);

        final EditText nameInput = new EditText(context);
        nameInput.setHint("Device Name");
        layout.addView(nameInput);

        final EditText elementIdInput = new EditText(context);
        elementIdInput.setHint("Element ID (Integer)");
        elementIdInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        layout.addView(elementIdInput);

        final EditText receiveIdInput = new EditText(context);
        receiveIdInput.setHint("Receive ID (Integer)");
        layout.addView(receiveIdInput);

        new MaterialAlertDialogBuilder(context)
                .setTitle("Enter Device Info")
                .setView(layout)
                .setPositiveButton("Save", (dialog, which) -> {
                    String name = nameInput.getText().toString().trim();
                    String eid  = elementIdInput.getText().toString().trim();
                    String rid  = receiveIdInput.getText().toString().trim();

                    if (name.isEmpty() || eid.isEmpty()) {
                        Toast.makeText(context, "Name and Element ID are required", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    String conflict = checkDeviceConflict(eid, name, deviceMap);
                    if (conflict != null) {
                        new MaterialAlertDialogBuilder(context)
                                .setTitle("⚠ ID Already Reserved")
                                .setMessage(conflict + "\n\nPlease use a different Element ID or device name.")
                                .setPositiveButton("Change ID", (d2, w2) -> showDeviceInfoDialog(selectedCategory, deviceMap, svgDocument, svgParser))
                                .setNegativeButton("Cancel Add", (d2, w2) -> callback.exitAddMode())
                                .setCancelable(false)
                                .show();
                        return;
                    }

                    float[] iconCoords = callback.getDraggableIconCoords();
                    float[] svgCoords = callback.touchToSvgCoords(iconCoords[0], iconCoords[1]);

                    addNewDeviceToSvg(svgDocument, svgParser, selectedCategory, name, eid, rid, svgCoords[0], svgCoords[1]);
                    callback.exitAddMode();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    public void addNewDeviceToSvg(Document svgDocument, SvgParsers svgParser, String category, String name, String eid, String rid, float x, float y) {
        if (svgDocument == null) return;

        try {
            Element root = svgDocument.getDocumentElement();
            String areaId = callback.getCurrentFocusAreaId();
            if (areaId == null) {
                for (Map.Entry<String, RectF> entry : svgParser.selectionLayerBounds.entrySet()) {
                    if (entry.getValue().contains(x, y)) {
                        areaId = entry.getKey();
                        break;
                    }
                }
            }
            if (areaId == null) areaId = "AddedDevices";

            String deviceId = "manual_" + name.replace(" ", "_").replace(":", "_") + "_" + System.currentTimeMillis();

            Element iconsGroup = svgParser.findElementById(root, "Technician Layer");
            if (iconsGroup == null) {
                iconsGroup = svgDocument.createElement("g");
                iconsGroup.setAttribute("id", "Technician Layer");
                root.appendChild(iconsGroup);
            }

            Element areaGroup = svgParser.findElementById(iconsGroup, areaId);
            if (areaGroup == null) areaGroup = svgParser.findElementFuzzy(iconsGroup, areaId);
            if (areaGroup == null) {
                areaGroup = svgDocument.createElement("g");
                areaGroup.setAttribute("id", areaId);
                iconsGroup.appendChild(areaGroup);
            }

            Element iconEl = svgDocument.createElement("g");
            iconEl.setAttribute("id", deviceId + ":" + category);
            iconEl.setAttribute("data-manual", "true");
            iconEl.setAttribute("data-manual-added", "true");
            iconEl.setAttribute("transform", "translate(" + (x - svgParser.vbX) + " " + (y - svgParser.vbY) + ")");

            Element metadata = svgDocument.createElement("metadata");
            Element eidNode = svgDocument.createElement("elementId");
            eidNode.setTextContent(eid);
            metadata.appendChild(eidNode);
            if (!rid.isEmpty()) {
                Element ridNode = svgDocument.createElement("reciveId");
                ridNode.setTextContent(rid);
                metadata.appendChild(ridNode);
            }
            iconEl.appendChild(metadata);

            Element rect = svgDocument.createElement("rect");
            rect.setAttribute("width", "10");
            rect.setAttribute("height", "10");
            rect.setAttribute("x", "-5");
            rect.setAttribute("y", "-5");
            rect.setAttribute("fill", "#ffae42");
            rect.setAttribute("stroke", "#000");
            rect.setAttribute("stroke-width", "0.5");
            iconEl.appendChild(rect);

            areaGroup.appendChild(iconEl);

            Element devicesGroup = svgParser.findElementById(root, "User Layer");
            if (devicesGroup == null) {
                devicesGroup = svgDocument.createElement("g");
                devicesGroup.setAttribute("id", "User Layer");
                root.appendChild(devicesGroup);
            }

            Element devAreaGroup = svgParser.findElementById(devicesGroup, areaId);
            if (devAreaGroup == null) {
                NodeList devChildren = devicesGroup.getChildNodes();
                for (int i = 0; i < devChildren.getLength(); i++) {
                    if (devChildren.item(i) instanceof Element) {
                        devAreaGroup = (Element) devChildren.item(i);
                        break;
                    }
                }
            }
            if (devAreaGroup == null) {
                devAreaGroup = svgDocument.createElement("g");
                devAreaGroup.setAttribute("id", areaId + "_Dev");
                devicesGroup.appendChild(devAreaGroup);
            }

            Element physEl = svgDocument.createElement("circle");
            physEl.setAttribute("id", deviceId + "_phys");
            physEl.setAttribute("cx", String.valueOf(x));
            physEl.setAttribute("cy", String.valueOf(y));
            physEl.setAttribute("r", "3");
            physEl.setAttribute("style", "fill:#b3b3b3;");
            devAreaGroup.appendChild(physEl);

            saveSvgToInternal(svgDocument);
            callback.onDataChanged();

            Toast.makeText(context, "Device added to " + areaId, Toast.LENGTH_SHORT).show();

        } catch (Exception e) {
            Log.e(TAG, "Error adding new device", e);
            Toast.makeText(context, "Failed to add device", Toast.LENGTH_SHORT).show();
        }
    }

    public void saveSvgToInternal(Document svgDocument) {
        try {
            File file = new File(context.getFilesDir(), "modified_map.svg");
            OutputStream os = new FileOutputStream(file);
            Transformer t = TransformerFactory.newInstance().newTransformer();
            t.transform(new DOMSource(svgDocument), new StreamResult(os));
            os.close();
            Log.d(TAG, "SVG saved to " + file.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "Error saving SVG", e);
        }
    }

    public String checkDeviceConflict(String elementId, String deviceName, Map<String, DeviceInfo> deviceMap) {
        for (Map.Entry<String, DeviceInfo> entry : deviceMap.entrySet()) {
            String existingKey = entry.getKey();
            DeviceInfo existing = entry.getValue();
            String areaSuffix = existing.areaId != null ? " (area: " + existing.areaId + ")" : "";

            if (existing.elementId != null && !existing.elementId.trim().isEmpty() && existing.elementId.trim().equals(elementId.trim())) {
                return "Element ID \"" + elementId + "\" is already used by:\n" + extractPureDeviceName(existingKey) + areaSuffix;
            }

            if (extractPureDeviceName(existingKey).trim().equalsIgnoreCase(deviceName.trim())) {
                return "Device name \"" + deviceName + "\" is already used by:\n" + existingKey + areaSuffix;
            }
        }
        return null;
    }

    public void showEditDeviceDialog(String iconId, DeviceInfo device, Map<String, DeviceInfo> deviceMap, Document svgDocument) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 20, 40, 20);

        final EditText nameInput = new EditText(context);
        nameInput.setHint("Device Name");
        String currentName = extractPureDeviceName(iconId);
        nameInput.setText(currentName);
        layout.addView(nameInput);

        final EditText elementIdInput = new EditText(context);
        elementIdInput.setHint("Element ID (Integer)");
        elementIdInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        if (device.elementId != null) elementIdInput.setText(device.elementId.trim());
        layout.addView(elementIdInput);

        final EditText receiveIdInput = new EditText(context);
        receiveIdInput.setHint("Receive ID (Integer)");
        if (device.receiveId != null) receiveIdInput.setText(device.receiveId.trim());
        layout.addView(receiveIdInput);

        new MaterialAlertDialogBuilder(context)
                .setTitle("Edit Device: " + currentName)
                .setView(layout)
                .setPositiveButton("Save", (dialog, which) -> {
                    String newName = nameInput.getText().toString().trim();
                    String newEid  = elementIdInput.getText().toString().trim();
                    String newRid  = receiveIdInput.getText().toString().trim();

                    if (newName.isEmpty() || newEid.isEmpty()) {
                        Toast.makeText(context, "Name and Element ID are required", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    String conflict = checkDeviceConflictExcluding(newEid, newName, iconId, deviceMap);
                    if (conflict != null) {
                        new MaterialAlertDialogBuilder(context)
                                .setTitle("ID Already Reserved")
                                .setMessage(conflict + "\n\nPlease use a different Element ID.")
                                .setPositiveButton("Fix", (d2, w2) -> showEditDeviceDialog(iconId, device, deviceMap, svgDocument))
                                .setNegativeButton("Cancel", null)
                                .setCancelable(false)
                                .show();
                        return;
                    }

                    applyDeviceEdits(device, newName, newEid, newRid, svgDocument);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private String checkDeviceConflictExcluding(String elementId, String deviceName, String excludeIconId, Map<String, DeviceInfo> deviceMap) {
        for (Map.Entry<String, DeviceInfo> entry : deviceMap.entrySet()) {
            String existingKey = entry.getKey();
            DeviceInfo existing = entry.getValue();
            if (existingKey.equals(excludeIconId)) continue;

            String areaSuffix = existing.areaId != null ? " (area: " + existing.areaId + ")" : "";
            if (existing.elementId != null && !existing.elementId.trim().isEmpty() && existing.elementId.trim().equals(elementId.trim())) {
                return "Element ID \"" + elementId + "\" is already used by:\n" + extractPureDeviceName(existingKey) + areaSuffix;
            }
            if (extractPureDeviceName(existingKey).trim().equalsIgnoreCase(deviceName.trim())) {
                return "Device name \"" + deviceName + "\" is already used by:\n" + existingKey + areaSuffix;
            }
        }
        return null;
    }

    private void applyDeviceEdits(DeviceInfo device, String newName, String newEid, String newRid, Document svgDocument) {
        if (svgDocument == null) return;
        try {
            Element iconEl = device.element;
            NodeList allChildren = iconEl.getElementsByTagName("*");
            boolean eidUpdated = false, ridUpdated = false;
            for (int i = 0; i < allChildren.getLength(); i++) {
                Node n = allChildren.item(i);
                if (n instanceof Element) {
                    Element el = (Element) n;
                    String tag = el.getTagName().toLowerCase().replace("svg:", "");
                    if ("elementid".equals(tag)) {
                        el.setTextContent(newEid);
                        eidUpdated = true;
                    } else if ("reciveid".equals(tag) || "receiveid".equals(tag)) {
                        el.setTextContent(newRid);
                        ridUpdated = true;
                    }
                }
            }

            if (!eidUpdated || (!ridUpdated && !newRid.isEmpty())) {
                Element metadata = null;
                NodeList metaList = iconEl.getElementsByTagName("metadata");
                if (metaList.getLength() > 0) {
                    metadata = (Element) metaList.item(0);
                } else {
                    metadata = svgDocument.createElement("metadata");
                    iconEl.insertBefore(metadata, iconEl.getFirstChild());
                }
                if (!eidUpdated) {
                    Element eidNode = svgDocument.createElement("elementId");
                    eidNode.setTextContent(newEid);
                    metadata.appendChild(eidNode);
                }
                if (!ridUpdated && !newRid.isEmpty()) {
                    Element ridNode = svgDocument.createElement("reciveId");
                    ridNode.setTextContent(newRid);
                    metadata.appendChild(ridNode);
                }
            }

            saveSvgToInternal(svgDocument);
            callback.onDataChanged();
            Toast.makeText(context, "Device updated", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "Error editing device", e);
            Toast.makeText(context, "Failed to update device", Toast.LENGTH_SHORT).show();
        }
    }

    public void deleteDeviceFromSvg(String iconId, Map<String, DeviceInfo> deviceMap, Map<String, Set<String>> iconToDeviceRelations, Document svgDocument, SvgParsers svgParser) {
        if (svgDocument == null) return;
        try {
            Element root = svgDocument.getDocumentElement();
            DeviceInfo info = deviceMap.get(iconId);
            if (info != null && info.element != null) {
                Node parent = info.element.getParentNode();
                if (parent != null) parent.removeChild(info.element);
            }

            Set<String> physicalIds = iconToDeviceRelations.get(iconId);
            if (physicalIds != null) {
                Element devGroup = svgParser.findElementById(root, "User Layer");
                if (devGroup != null) {
                    for (String pid : physicalIds) {
                        Element pEl = svgParser.findElementById(devGroup, pid);
                        if (pEl != null) {
                            Node pParent = pEl.getParentNode();
                            if (pParent != null) pParent.removeChild(pEl);
                        }
                    }
                }
            }

            saveSvgToInternal(svgDocument);
            callback.onDataChanged();
            Toast.makeText(context, "Device deleted", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "Error deleting device", e);
            Toast.makeText(context, "Failed to delete device", Toast.LENGTH_SHORT).show();
        }
    }

    public String extractPureDeviceName(String fullDeviceId) {
        if (fullDeviceId == null || fullDeviceId.isEmpty()) return "";
        String[] parts = fullDeviceId.split("_");
        if (parts.length >= 5) return parts[1];
        String name = fullDeviceId;
        int ci = name.lastIndexOf(":");
        if (ci != -1) name = name.substring(ci + 1).trim();
        
        // Don't strip numbers if it's a known device code (e.g., PSD02)
        if (DeviceCodes.getName(name) != null) return name;

        name = name.replaceAll("\\s*\\d+$", "").replaceAll("\\d+$", "").replaceAll("\\s+", " ").trim();
        return name.isEmpty() ? (fullDeviceId.contains(":") ? fullDeviceId.substring(fullDeviceId.indexOf(":") + 1).trim() : fullDeviceId) : name;
    }
}
