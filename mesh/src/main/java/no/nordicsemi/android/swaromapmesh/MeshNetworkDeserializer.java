package no.nordicsemi.android.swaromapmesh;

import com.google.gson.JsonArray;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import androidx.annotation.NonNull;
import no.nordicsemi.android.swaromapmesh.logger.MeshLogger;
import no.nordicsemi.android.swaromapmesh.transport.Element;
import no.nordicsemi.android.swaromapmesh.transport.ProvisionedMeshNode;
import no.nordicsemi.android.swaromapmesh.utils.MeshAddress;
import static no.nordicsemi.android.swaromapmesh.utils.MeshParserUtils.formatTimeStamp;
import static no.nordicsemi.android.swaromapmesh.utils.MeshParserUtils.formatUuid;
import static no.nordicsemi.android.swaromapmesh.utils.MeshParserUtils.isUuidPattern;
import static no.nordicsemi.android.swaromapmesh.utils.MeshParserUtils.parseTimeStamp;
import static no.nordicsemi.android.swaromapmesh.utils.MeshParserUtils.uuidToHex;

public final class MeshNetworkDeserializer implements JsonSerializer<MeshNetwork>, JsonDeserializer<MeshNetwork> {
    private static final String TAG          = MeshNetworkDeserializer.class.getSimpleName();
    private static final String KEY_SWAROMAP = "swaromapData";
    private static final String PREFS_NAME   = "mesh_prefs";

    private final android.content.Context mContext;

    public MeshNetworkDeserializer(@NonNull android.content.Context context) {
        this.mContext = context.getApplicationContext();
    }

    @Override
    public MeshNetwork deserialize(final JsonElement json,
                                   final Type typeOfT,
                                   final JsonDeserializationContext context) throws JsonParseException {

        final JsonObject jsonObject = json.getAsJsonObject();
        if (!isValidMeshObject(jsonObject)) {
            throw new JsonSyntaxException("Invalid Mesh Provisioning/Configuration Database, " +
                    "Mesh Network must follow the Mesh Provisioning/Configuration Database format.");
        }

        final String uuid     = jsonObject.get("meshUUID").getAsString();
        final String meshUuid = formatUuid(uuid);
        final MeshNetwork network = new MeshNetwork(meshUuid == null ? uuid : meshUuid);
        network.schema   = jsonObject.get("$schema").getAsString();
        network.id       = jsonObject.get("id").getAsString();
        network.version  = jsonObject.get("version").getAsString();
        network.meshName = jsonObject.get("meshName").getAsString();

        try {
            network.timestamp = parseTimeStamp(jsonObject.get("timestamp").getAsString());
        } catch (Exception ex) {
            throw new JsonSyntaxException("Invalid Mesh Provisioning/Configuration Database JSON file, " +
                    "mesh network timestamp must follow the Mesh Provisioning/Configuration Database format.");
        }

        if (jsonObject.has("partial")) {
            network.partial = jsonObject.get("partial").getAsBoolean();
        }

        network.netKeys      = deserializeNetKeys(context,
                jsonObject.getAsJsonArray("netKeys"), network.meshUUID);
        network.appKeys      = deserializeAppKeys(context,
                jsonObject.getAsJsonArray("appKeys"), network.meshUUID);
        network.provisioners = deserializeProvisioners(context,
                jsonObject.getAsJsonArray("provisioners"), network.meshUUID);
        network.nodes        = deserializeNodes(context,
                jsonObject.getAsJsonArray("nodes"), network.meshUUID);
        network.groups       = deserializeGroups(jsonObject, network.meshUUID);
        network.scenes       = deserializeScenes(jsonObject, network.meshUUID);

        if (jsonObject.has("networkExclusions"))
            network.networkExclusions =
                    deserializeExclusionList(jsonObject.getAsJsonArray("networkExclusions"));

        assignProvisionerAddresses(network);

        // ── Restore ClientServerElementStore from swaromapData ────────────
        if (jsonObject.has(KEY_SWAROMAP)) {
            deserializeSwaromapData(jsonObject.getAsJsonObject(KEY_SWAROMAP));
        }

        return network;
    }

    @Override
    public JsonElement serialize(final MeshNetwork network,
                                 final Type typeOfSrc,
                                 final JsonSerializationContext context) {
        final String meshUuid = network.getMeshUUID().toUpperCase(Locale.US);
        final JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty("$schema",   network.getSchema());
        jsonObject.addProperty("id",        network.getId());
        jsonObject.addProperty("version",   network.getVersion());
        jsonObject.addProperty("meshUUID",  meshUuid);
        jsonObject.addProperty("meshName",  network.getMeshName());
        jsonObject.addProperty("timestamp", formatTimeStamp(network.getTimestamp()));
        jsonObject.addProperty("partial",   network.partial);
        jsonObject.add("netKeys",           serializeNetKeys(context, network.getNetKeys()));
        jsonObject.add("appKeys",           serializeAppKeys(context, network.getAppKeys()));
        jsonObject.add("provisioners",      serializeProvisioners(context, network.getProvisioners()));
        jsonObject.add("nodes",             serializeNodes(context, network.getNodes()));
        jsonObject.add("groups",            serializeGroups(network.getGroups()));
        jsonObject.add("scenes",            serializeScenes(network.getScenes()));
        jsonObject.add("networkExclusions", serializeExclusionList(network.getNetworkExclusions()));

        // ── Embed ClientServerElementStore data ───────────────────────────
        jsonObject.add(KEY_SWAROMAP, serializeSwaromapData());

        return jsonObject;
    }

    // =========================================================================
    // SWAROMAP DATA — serialize / deserialize
    // =========================================================================

    /**
     * Serializes all device data from mesh_prefs into a swaromapData JSON block.
     *
     * Strategy:
     * 1. Start from provisioned_devices set (server devices).
     * 2. Also scan all keys in prefs for "address_*" and "lc_address_*" entries
     *    that may belong to LC Node assigned addresses saved under relationDeviceName
     *    keys — these may NOT be in provisioned_devices set.
     */
    private JsonObject serializeSwaromapData() {
        final JsonObject root = new JsonObject();

        android.content.SharedPreferences prefs =
                mContext.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE);

        // ── Step 1: All provisioned device keys ───────────────────────────
        java.util.Set<String> provisionedKeys = new java.util.HashSet<>(
                prefs.getStringSet("provisioned_devices", new java.util.HashSet<>()));

        // ── Step 2: Also collect keys from address_* and lc_address_* ─────
        // These are saved using relationDeviceName as key (e.g. "lc_node_3_relay"),
        // which may differ from deviceId-based provisioned keys.
        java.util.Set<String> allKeys = new java.util.HashSet<>(provisionedKeys);
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            String k = entry.getKey();
            if (k.startsWith("address_") && !k.startsWith("address_0x")) {
                // e.g. "address_lc_node_3_relay" → extract "lc_node_3_relay"
                String candidate = k.substring("address_".length());
                if (!candidate.isEmpty()) allKeys.add(candidate);
            }
            if (k.startsWith("lc_address_")) {
                String candidate = k.substring("lc_address_".length());
                if (!candidate.isEmpty()) allKeys.add(candidate);
            }
        }

        for (String key : allKeys) {
            final JsonObject entry = new JsonObject();

            int unicast = prefs.getInt("server_unicast_" + key, -1);
            if (unicast != -1) entry.addProperty("unicast", unicast);

            int svgId = prefs.getInt("server_svg_element_id_" + key, -1);
            if (svgId != -1) entry.addProperty("svgElementId", svgId);

            String mac = prefs.getString("mac_" + key, null);
            if (mac != null && !mac.isEmpty()) entry.addProperty("mac", mac);

            String receiveId = prefs.getString("server_receive_id_" + key, null);
            if (receiveId != null && !receiveId.isEmpty()) entry.addProperty("receiveId", receiveId);

            // LC address (int) — the parsed address value 1-8
            int lcAddress = prefs.getInt("lc_address_" + key, -1);
            if (lcAddress != -1) entry.addProperty("lcAddress", lcAddress);

            // Assigned address (String) — the raw string saved from EditText
            String assignedAddr = prefs.getString("address_" + key, null);
            if (assignedAddr != null && !assignedAddr.isEmpty())
                entry.addProperty("assignedAddress", assignedAddr);

            // Client element addresses
            final JsonObject clientAddrs = new JsonObject();
            for (int i = 0; i <= 40; i++) {
                int addr = prefs.getInt("element_addr_" + key + "_" + i, -1);
                if (addr != -1) {
                    clientAddrs.addProperty(String.valueOf(i), addr);
                }
            }
            if (clientAddrs.size() > 0) entry.add("clientAddresses", clientAddrs);

            // Only add entry if it has at least one meaningful field
            if (entry.size() > 0) {
                root.add(key, entry);
            }
        }

        MeshLogger.verbose(TAG, "serializeSwaromapData: exported " + root.size() + " device(s)");
        return root;
    }

    /**
     * Reads swaromapData block from JSON and restores all device data into mesh_prefs.
     * Also restores assigned address into device_address_prefs so TestProvisionActivity
     * can read it immediately after import without needing a fallback lookup.
     */
    private void deserializeSwaromapData(@NonNull final JsonObject swaromapData) {
        android.content.SharedPreferences meshPrefs =
                mContext.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE);
        android.content.SharedPreferences.Editor meshEditor = meshPrefs.edit();

        // Also restore into device_address_prefs so TestProvisionActivity reads it directly
        android.content.SharedPreferences devicePrefs =
                mContext.getSharedPreferences("device_address_prefs", android.content.Context.MODE_PRIVATE);
        android.content.SharedPreferences.Editor deviceEditor = devicePrefs.edit();

        java.util.Set<String> provisionedKeys = new java.util.HashSet<>(
                meshPrefs.getStringSet("provisioned_devices", new java.util.HashSet<>()));

        int restored = 0;

        for (Map.Entry<String, JsonElement> entry : swaromapData.entrySet()) {
            final String     key = entry.getKey();
            final JsonObject obj = entry.getValue().getAsJsonObject();

            if (obj.has("unicast")) {
                int unicast = obj.get("unicast").getAsInt();
                meshEditor.putInt("server_unicast_" + key, unicast);
                meshEditor.putInt("server_primary_addr_" + key, unicast);
                // Mark as provisioned only if it has a unicast address
                provisionedKeys.add(key);
            }

            if (obj.has("svgElementId"))
                meshEditor.putInt("server_svg_element_id_" + key, obj.get("svgElementId").getAsInt());

            if (obj.has("mac"))
                meshEditor.putString("mac_" + key, obj.get("mac").getAsString());

            if (obj.has("receiveId"))
                meshEditor.putString("server_receive_id_" + key, obj.get("receiveId").getAsString());

            if (obj.has("lcAddress"))
                meshEditor.putInt("lc_address_" + key, obj.get("lcAddress").getAsInt());

            // Restore assigned address to BOTH prefs
            if (obj.has("assignedAddress")) {
                String assignedAddr = obj.get("assignedAddress").getAsString();
                // mesh_prefs — used as fallback in TestProvisionActivity
                meshEditor.putString("address_" + key, assignedAddr);
                // device_address_prefs — primary source in TestProvisionActivity
                deviceEditor.putString("address_" + key, assignedAddr);
            }

            // Client element addresses
            if (obj.has("clientAddresses")) {
                final JsonObject clientAddrs = obj.getAsJsonObject("clientAddresses");
                for (Map.Entry<String, JsonElement> addrEntry : clientAddrs.entrySet()) {
                    try {
                        int index = Integer.parseInt(addrEntry.getKey());
                        int addr  = addrEntry.getValue().getAsInt();
                        meshEditor.putInt("element_addr_" + key + "_" + index, addr);
                    } catch (NumberFormatException ignored) {}
                }
            }

            restored++;
        }

        meshEditor.putStringSet("provisioned_devices", provisionedKeys);
        meshEditor.apply();
        deviceEditor.apply();

        MeshLogger.verbose(TAG, "deserializeSwaromapData: restored " + restored + " device(s)");
    }

    // =========================================================================
    // Validation
    // =========================================================================

    private boolean isValidMeshObject(@NonNull final JsonObject mesh) {
        return mesh.has("$schema") &&
                mesh.has("id") &&
                mesh.has("version") &&
                mesh.has("meshUUID") &&
                mesh.has("meshName") &&
                mesh.has("timestamp") &&
                mesh.has("provisioners") &&
                mesh.has("netKeys") &&
                mesh.has("appKeys") &&
                mesh.has("nodes");
    }

    // =========================================================================
    // Net Keys
    // =========================================================================

    private JsonElement serializeNetKeys(@NonNull final JsonSerializationContext context,
                                         @NonNull final List<NetworkKey> networkKeys) {
        final Type networkKey = new TypeToken<List<NetworkKey>>() {}.getType();
        return context.serialize(networkKeys, networkKey);
    }

    private List<NetworkKey> deserializeNetKeys(@NonNull final JsonDeserializationContext context,
                                                @NonNull final JsonArray json,
                                                @NonNull final String meshUuid) {
        final Type networkKey = new TypeToken<List<NetworkKey>>() {}.getType();
        final List<NetworkKey> networkKeys = context.deserialize(json, networkKey);
        for (NetworkKey key : networkKeys) key.setMeshUuid(meshUuid);
        return networkKeys;
    }

    // =========================================================================
    // App Keys
    // =========================================================================

    private JsonElement serializeAppKeys(@NonNull final JsonSerializationContext context,
                                         @NonNull final List<ApplicationKey> applicationKeys) {
        final Type networkKey = new TypeToken<List<ApplicationKey>>() {}.getType();
        return context.serialize(applicationKeys, networkKey);
    }

    private List<ApplicationKey> deserializeAppKeys(@NonNull final JsonDeserializationContext context,
                                                    @NonNull final JsonArray json,
                                                    @NonNull final String meshUuid) {
        final Type applicationKeyList = new TypeToken<List<ApplicationKey>>() {}.getType();
        final List<ApplicationKey> applicationKeys = context.deserialize(json, applicationKeyList);
        for (ApplicationKey key : applicationKeys) key.setMeshUuid(meshUuid);
        return applicationKeys;
    }

    // =========================================================================
    // Provisioners
    // =========================================================================

    private List<Provisioner> deserializeProvisioners(@NonNull final JsonDeserializationContext context,
                                                      @NonNull final JsonArray json,
                                                      @NonNull final String meshUuid) {
        List<Provisioner> provisioners = new ArrayList<>();
        final JsonArray jsonProvisioners = json.getAsJsonArray();
        for (int i = 0; i < jsonProvisioners.size(); i++) {
            final JsonObject jsonProvisioner = jsonProvisioners.get(i).getAsJsonObject();
            final String name = jsonProvisioner.get("provisionerName").getAsString();
            final String uuid = jsonProvisioner.get("UUID").getAsString().toUpperCase();
            final String provisionerUuid = formatUuid(uuid);

            if (provisionerUuid == null)
                throw new IllegalArgumentException("Invalid Mesh Provisioning/Configuration " +
                        "Database, invalid provisioner uuid.");

            final List<AllocatedUnicastRange> unicastRanges =
                    deserializeAllocatedUnicastRange(context, jsonProvisioner);

            List<AllocatedGroupRange> groupRanges = new ArrayList<>();
            if (jsonProvisioner.has("allocatedGroupRange") &&
                    !jsonProvisioner.get("allocatedGroupRange").isJsonNull()) {
                groupRanges = deserializeAllocatedGroupRange(context, jsonProvisioner);
            }

            List<AllocatedSceneRange> sceneRanges = new ArrayList<>();
            if (jsonProvisioner.has("allocatedSceneRange") &&
                    !jsonProvisioner.get("allocatedSceneRange").isJsonNull()) {
                sceneRanges = deserializeAllocatedSceneRange(context, jsonProvisioner);
            }

            final Provisioner provisioner = new Provisioner(
                    provisionerUuid, unicastRanges, groupRanges, sceneRanges, meshUuid);
            provisioner.setProvisionerName(name);
            provisioners.add(provisioner);
        }
        return provisioners;
    }

    private JsonElement serializeProvisioners(@NonNull final JsonSerializationContext context,
                                              @NonNull final List<Provisioner> provisioners) {
        final JsonArray jsonArray = new JsonArray();
        for (Provisioner provisioner : provisioners) {
            final JsonObject provisionerJson = new JsonObject();
            provisionerJson.addProperty("provisionerName", provisioner.getProvisionerName());
            provisionerJson.addProperty("UUID",
                    provisioner.getProvisionerUuid().toUpperCase(Locale.US));
            provisionerJson.add("allocatedUnicastRange",
                    serializeAllocatedUnicastRanges(context, provisioner.allocatedUnicastRanges));
            provisionerJson.add("allocatedGroupRange",
                    serializeAllocatedGroupRanges(context, provisioner.allocatedGroupRanges));
            provisionerJson.add("allocatedSceneRange",
                    serializeAllocatedSceneRanges(context, provisioner.allocatedSceneRanges));
            jsonArray.add(provisionerJson);
        }
        return jsonArray;
    }

    // =========================================================================
    // Allocated Ranges
    // =========================================================================

    private JsonElement serializeAllocatedUnicastRanges(
            @NonNull final JsonSerializationContext context,
            @NonNull final List<AllocatedUnicastRange> ranges) {
        final Type t = new TypeToken<List<AllocatedUnicastRange>>() {}.getType();
        return context.serialize(ranges, t);
    }

    private List<AllocatedUnicastRange> deserializeAllocatedUnicastRange(
            @NonNull final JsonDeserializationContext context,
            @NonNull final JsonObject json) {
        final Type t = new TypeToken<List<AllocatedUnicastRange>>() {}.getType();
        return context.deserialize(json.get("allocatedUnicastRange").getAsJsonArray(), t);
    }

    private JsonElement serializeAllocatedGroupRanges(
            @NonNull final JsonSerializationContext context,
            @NonNull final List<AllocatedGroupRange> ranges) {
        final Type t = new TypeToken<List<AllocatedGroupRange>>() {}.getType();
        return context.serialize(ranges, t);
    }

    private List<AllocatedGroupRange> deserializeAllocatedGroupRange(
            @NonNull final JsonDeserializationContext context,
            @NonNull final JsonObject json) {
        final Type t = new TypeToken<List<AllocatedGroupRange>>() {}.getType();
        return context.deserialize(json.getAsJsonArray("allocatedGroupRange"), t);
    }

    private JsonElement serializeAllocatedSceneRanges(
            @NonNull final JsonSerializationContext context,
            @NonNull final List<AllocatedSceneRange> ranges) {
        final Type t = new TypeToken<List<AllocatedSceneRange>>() {}.getType();
        return context.serialize(ranges, t);
    }

    private List<AllocatedSceneRange> deserializeAllocatedSceneRange(
            @NonNull final JsonDeserializationContext context,
            @NonNull final JsonObject json) {
        final Type t = new TypeToken<List<AllocatedSceneRange>>() {}.getType();
        return context.deserialize(json.getAsJsonArray("allocatedSceneRange"), t);
    }

    // =========================================================================
    // Nodes
    // =========================================================================

    private JsonElement serializeNodes(@NonNull final JsonSerializationContext context,
                                       @NonNull final List<ProvisionedMeshNode> nodes) {
        final Type nodeList = new TypeToken<List<ProvisionedMeshNode>>() {}.getType();
        return context.serialize(nodes, nodeList);
    }

    private List<ProvisionedMeshNode> deserializeNodes(
            @NonNull final JsonDeserializationContext context,
            @NonNull final JsonArray json,
            final String meshUuid) {
        final Type nodeList = new TypeToken<List<ProvisionedMeshNode>>() {}.getType();
        final List<ProvisionedMeshNode> nodes = context.deserialize(json, nodeList);
        for (ProvisionedMeshNode node : nodes) node.setMeshUuid(meshUuid);
        return nodes;
    }

    // =========================================================================
    // Groups
    // =========================================================================

    private JsonElement serializeGroups(@NonNull final List<Group> groups) {
        JsonArray groupsArray = new JsonArray();
        for (Group group : groups) {
            JsonObject groupObj = new JsonObject();
            groupObj.addProperty("name", group.getName());
            if (group.getAddressLabel() == null) {
                groupObj.addProperty("address",
                        MeshAddress.formatAddress(group.getAddress(), false));
            } else {
                groupObj.addProperty("address", uuidToHex(group.getAddressLabel()));
            }
            if (group.getParentAddressLabel() == null) {
                groupObj.addProperty("parentAddress",
                        MeshAddress.formatAddress(group.getParentAddress(), false));
            } else {
                groupObj.addProperty("parentAddress", uuidToHex(group.getParentAddressLabel()));
            }
            groupsArray.add(groupObj);
        }
        return groupsArray;
    }

    private List<Group> deserializeGroups(@NonNull final JsonObject jsonNetwork,
                                          @NonNull final String meshUuid) {
        final List<Group> groups = new ArrayList<>();
        if (!jsonNetwork.has("groups")) return groups;

        final JsonArray jsonGroups = jsonNetwork.getAsJsonArray("groups");
        for (int i = 0; i < jsonGroups.size(); i++) {
            try {
                final JsonObject jsonGroup     = jsonGroups.get(i).getAsJsonObject();
                final String     name          = jsonGroup.get("name").getAsString();
                String           address       = jsonGroup.get("address").getAsString();
                String           parentAddress = jsonGroup.get("parentAddress").getAsString();
                final Group group;

                if (isUuidPattern(address) && isUuidPattern(parentAddress)) {
                    group = new Group(UUID.fromString(formatUuid(address)),
                            UUID.fromString(formatUuid(parentAddress)), meshUuid);
                } else if (isUuidPattern(address)) {
                    group = new Group(UUID.fromString(formatUuid(address)),
                            Integer.parseInt(parentAddress, 16), meshUuid);
                } else if (isUuidPattern(parentAddress)) {
                    group = new Group(Integer.parseInt(parentAddress, 16),
                            UUID.fromString(formatUuid(parentAddress)), meshUuid);
                } else {
                    group = new Group(Integer.parseInt(address, 16),
                            Integer.parseInt(parentAddress, 16), meshUuid);
                }
                group.setName(name);
                groups.add(group);
            } catch (Exception ex) {
                MeshLogger.error(TAG, "Error while de-serializing groups: " + ex.getMessage());
            }
        }
        return groups;
    }

    // =========================================================================
    // Scenes
    // =========================================================================

    private JsonElement serializeScenes(@NonNull final List<Scene> scenes) {
        final JsonArray scenesArray = new JsonArray();
        for (Scene scene : scenes) {
            JsonObject sceneObj = new JsonObject();
            sceneObj.addProperty("name", scene.getName());
            final JsonArray array = new JsonArray();
            for (Integer address : scene.getAddresses()) {
                array.add(MeshAddress.formatAddress(address, false));
            }
            sceneObj.add("addresses", array);
            sceneObj.addProperty("number",
                    String.format(Locale.US, "%04X", scene.getNumber()));
            scenesArray.add(sceneObj);
        }
        return scenesArray;
    }

    private List<Scene> deserializeScenes(@NonNull final JsonObject jsonNetwork,
                                          @NonNull final String meshUuid) {
        final List<Scene> scenes = new ArrayList<>();
        try {
            if (!jsonNetwork.has("scenes")) return scenes;

            final JsonArray jsonScenes = jsonNetwork.getAsJsonArray("scenes");
            for (int i = 0; i < jsonScenes.size(); i++) {
                final JsonObject jsonScene = jsonScenes.get(i).getAsJsonObject();
                final String     name      = jsonScene.get("name").getAsString();
                final List<Integer> addresses = new ArrayList<>();
                if (jsonScene.has("addresses")) {
                    final JsonArray addressesArray =
                            jsonScene.get("addresses").getAsJsonArray();
                    for (int j = 0; j < addressesArray.size(); j++) {
                        addresses.add(Integer.parseInt(
                                addressesArray.get(j).getAsString(), 16));
                    }
                }
                final int number;
                if (jsonScene.has("scene")) {
                    number = Integer.parseInt(jsonScene.get("scene").getAsString(), 16);
                } else {
                    number = Integer.parseInt(jsonScene.get("number").getAsString(), 16);
                }
                final Scene scene = new Scene(number, addresses, meshUuid);
                scene.setName(name);
                scenes.add(scene);
            }
        } catch (Exception ex) {
            MeshLogger.error(TAG, "Error while de-serializing scenes: " + ex.getMessage());
        }
        return scenes;
    }

    // =========================================================================
    // Network Exclusions
    // =========================================================================

    private JsonElement serializeExclusionList(
            @NonNull final Map<Integer, List<Integer>> networkExclusions) {
        final JsonArray exclusionList = new JsonArray();
        for (Map.Entry<Integer, List<Integer>> entry : networkExclusions.entrySet()) {
            JsonObject exclusion = new JsonObject();
            JsonArray  array     = new JsonArray();
            for (Integer address : entry.getValue()) {
                array.add(MeshAddress.formatAddress(address, false));
            }
            exclusion.addProperty("ivIndex", entry.getKey());
            exclusion.add("addresses", array);
            exclusionList.add(exclusion);
        }
        return exclusionList;
    }

    private Map<Integer, List<Integer>> deserializeExclusionList(
            @NonNull final JsonArray networkExclusions) {
        final Map<Integer, List<Integer>> exclusionList = new HashMap<>();
        for (JsonElement element : networkExclusions) {
            ArrayList<Integer> addresses = new ArrayList<>();
            JsonObject exclusion = element.getAsJsonObject();
            int ivIndex = exclusion.get("ivIndex").getAsInt();
            for (JsonElement address : exclusion.get("addresses").getAsJsonArray()) {
                addresses.add(Integer.parseInt(address.getAsString(), 16));
            }
            exclusionList.put(ivIndex, addresses);
        }
        return exclusionList;
    }

    // =========================================================================
    // Utility
    // =========================================================================

    private int getNextAvailableAddress(final List<ProvisionedMeshNode> nodes) {
        int unicast = 1;
        if (nodes != null && !nodes.isEmpty()) {
            final int index = nodes.size() - 1;
            final ProvisionedMeshNode node = nodes.get(index);
            Map<Integer, Element> elements = node.getElements();
            if (elements != null && !elements.isEmpty()) {
                unicast = node.getUnicastAddress() + elements.size();
            } else {
                unicast = node.getUnicastAddress() + 1;
            }
        }
        return unicast;
    }

    private void assignProvisionerAddresses(@NonNull final MeshNetwork network) {
        for (Provisioner provisioner : network.provisioners) {
            for (ProvisionedMeshNode node : network.nodes) {
                if (provisioner.getProvisionerUuid().equalsIgnoreCase(node.getUuid())) {
                    provisioner.assignProvisionerAddress(node.getUnicastAddress());
                    provisioner.setGlobalTtl(node.getTtl());
                }
            }
        }
    }
}