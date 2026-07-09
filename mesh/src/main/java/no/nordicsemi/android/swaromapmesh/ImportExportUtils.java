package no.nordicsemi.android.swaromapmesh;

import android.content.ContentResolver;
import android.net.Uri;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;
import java.util.Locale;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import no.nordicsemi.android.swaromapmesh.logger.MeshLogger;
import no.nordicsemi.android.swaromapmesh.transport.Element;
import no.nordicsemi.android.swaromapmesh.transport.InternalElementListDeserializer;
import no.nordicsemi.android.swaromapmesh.transport.MeshModel;
import no.nordicsemi.android.swaromapmesh.transport.MeshModelListDeserializer;
import no.nordicsemi.android.swaromapmesh.transport.NodeDeserializer;
import no.nordicsemi.android.swaromapmesh.transport.ProvisionedMeshNode;
// import no.nordicsemi.android.swaromapmesh.utils.CryptoUtils;

import static no.nordicsemi.android.swaromapmesh.utils.MeshAddress.isValidGroupAddress;

/**
 * Utility class to handle network imports and exports
 */
class ImportExportUtils {

    private static final String TAG = ImportExportUtils.class.getSimpleName();
    private final Gson mGson;
    private final android.content.Context mContext;

    ImportExportUtils(@NonNull android.content.Context context) {
        this.mContext = context.getApplicationContext();
        mGson = initGson();
    }

    /**
     * Initializes the Gson based on the network export type.
     */
    private Gson initGson() {
        Type netKeyList = new TypeToken<List<NetworkKey>>() {
        }.getType();
        Type appKeyList = new TypeToken<List<ApplicationKey>>() {
        }.getType();
        Type allocatedUnicastRange = new TypeToken<List<AllocatedUnicastRange>>() {
        }.getType();
        Type allocatedGroupRange = new TypeToken<List<AllocatedGroupRange>>() {
        }.getType();
        Type allocatedSceneRange = new TypeToken<List<AllocatedSceneRange>>() {
        }.getType();
        Type nodeList = new TypeToken<List<ProvisionedMeshNode>>() {
        }.getType();
        Type meshModelList = new TypeToken<List<MeshModel>>() {
        }.getType();
        Type elementList = new TypeToken<List<Element>>() {
        }.getType();

        return new GsonBuilder()
                .registerTypeAdapter(netKeyList, new NetKeyDeserializer())
                .registerTypeAdapter(appKeyList, new AppKeyDeserializer())
                .registerTypeAdapter(allocatedUnicastRange, new AllocatedUnicastRangeDeserializer())
                .registerTypeAdapter(allocatedGroupRange, new AllocatedGroupRangeDeserializer())
                .registerTypeAdapter(allocatedSceneRange, new AllocatedSceneRangeDeserializer())

                // NODE MAC ADDRESS
                .registerTypeAdapter(nodeList, new NodeDeserializer())

                // for MAC address
                .registerTypeAdapter(ProvisionedMeshNode.class, new JsonSerializer<ProvisionedMeshNode>() {
                    @Override
                    public JsonElement serialize(ProvisionedMeshNode src, Type typeOfSrc,
                                                 JsonSerializationContext context) {
                        JsonObject jsonObject = new JsonObject();

                        // UUID
                        jsonObject.addProperty("UUID", src.getUuid().toUpperCase(Locale.US));

                        // ✅ MAC ADDRESS
                        String macAddress = src.getMacAddress();
                        if (macAddress != null && !macAddress.isEmpty()) {
                            jsonObject.addProperty("mac_address", macAddress);
                        }

                        // Name
                        jsonObject.addProperty("name", src.getNodeName());

                        // Device Key
                        if (src.getDeviceKey() != null) {
                            jsonObject.addProperty("deviceKey",
                                    no.nordicsemi.android.swaromapmesh.utils.MeshParserUtils.bytesToHex(
                                            src.getDeviceKey(), false));
                        }

                        // Unicast Address
                        jsonObject.addProperty("unicastAddress",
                                no.nordicsemi.android.swaromapmesh.utils.MeshParserUtils.bytesToHex(
                                        no.nordicsemi.android.swaromapmesh.utils.MeshAddress.addressIntToBytes(
                                                src.getUnicastAddress()), false));

                        // Config Complete
                        jsonObject.addProperty("configComplete", src.isConfigured());

                        MeshLogger.debug(TAG, "Serializing node: " + src.getNodeName() +
                                ", MAC: " + (macAddress != null ? macAddress : "null"));

                        return jsonObject;
                    }
                })

                .registerTypeAdapter(elementList, new InternalElementListDeserializer())
                .registerTypeAdapter(meshModelList, new MeshModelListDeserializer())
                .registerTypeAdapter(MeshNetwork.class, new MeshNetworkDeserializer(mContext))
                .serializeNulls()
                .setPrettyPrinting()
                .create();
    }

    /**
     * Imports the network from the Mesh Provisioning/Configuration Database json file
     */
    protected MeshNetwork importNetwork(@NonNull String networkJson) throws JsonSyntaxException {
        MeshLogger.debug(TAG, "Importing network JSON...");

        /*// ✅ Decrypt if it's an encrypted file
        if (CryptoUtils.isEncrypted(networkJson)) {
            MeshLogger.debug(TAG, "Encrypted file detected — decrypting...");
            String decrypted = CryptoUtils.decrypt(networkJson);
            if (decrypted != null) {
                networkJson = decrypted;
            } else {
                MeshLogger.error(TAG, "Failed to decrypt network JSON");
                throw new JsonSyntaxException("Failed to decrypt network JSON. Invalid key or corrupted file.");
            }
        }*/

        if (networkJson.contains("mac_address")) {
            MeshLogger.debug(TAG, "JSON contains 'mac_address' field");
        } else {
            MeshLogger.debug(TAG, "JSON does NOT contain 'mac_address' field");
        }

        return mGson.fromJson(networkJson, MeshNetwork.class);
    }

    /**
     * Reads and returns the json string from URI.
     *
     * @param contentResolver ContentResolver
     * @param uri             URI
     * @throws IOException in case of failure
     */
    protected String readJsonStringFromUri(@NonNull final ContentResolver contentResolver,
                                           @NonNull final Uri uri) throws IOException {
        final StringBuilder stringBuilder = new StringBuilder();
        final InputStream inputStream = contentResolver.openInputStream(uri);
        if (inputStream != null) {
            final BufferedReader reader = new BufferedReader(
                    new InputStreamReader(inputStream));
            String line;
            while ((line = reader.readLine()) != null) {
                stringBuilder.append(line);
            }
            reader.close();
            inputStream.close();
        }
        return stringBuilder.toString();
    }

    /**
     * Exports the mesh network to a Json file
     *
     * @param network Mesh network to be exported
     * @param partial True if the network is to be exported as partial.
     */
    @Nullable
    protected String export(@NonNull final MeshNetwork network, final boolean partial) {
        try {
            network.setPartial(partial);

            for (ProvisionedMeshNode node : network.getNodes()) {
                String macAddress = node.getMacAddress();
                MeshLogger.debug(TAG, "Node: " + node.getNodeName() +
                        ", MAC present: " + (macAddress != null && !macAddress.isEmpty()) +
                        ", MAC: " + macAddress);
            }

            String exportedJson = mGson.toJson(network);

            if (exportedJson != null) {
                boolean hasMacAddress = exportedJson.contains("mac_address");
                MeshLogger.debug(TAG, "Export contains 'mac_address': " + hasMacAddress);

                int count = 0;
                int index = 0;
                while ((index = exportedJson.indexOf("mac_address", index)) != -1) {
                    count++;
                    index += "mac_address".length();
                }
                MeshLogger.debug(TAG, "Found 'mac_address' " + count + " times in export");

                /*// ✅ ENCRYPT the exported JSON
                String encryptedJson = CryptoUtils.encrypt(exportedJson);
                if (encryptedJson != null) {
                    MeshLogger.debug(TAG, "Network JSON encrypted successfully");
                    return encryptedJson;
                }*/
            }

            return exportedJson;
        } catch (final JsonSyntaxException ex) {
            MeshLogger.error(TAG, "Error: " + ex.getMessage());
            return null;
        } catch (final Exception e) {
            MeshLogger.error(TAG, "Error: " + e.getMessage());
            return null;
        }
    }

    @Nullable
    protected String export(@NonNull final MeshNetwork network,
                            @NonNull final NetworkKeysConfig networkKeysConfig,
                            @NonNull final ApplicationKeysConfig applicationKeysConfig,
                            @NonNull final NodesConfig nodesConfig,
                            @NonNull final ProvisionersConfig provisionersConfig,
                            @NonNull final GroupsConfig groupsConfig,
                            @NonNull final ScenesConfig scenesConfig) {
        final MeshNetwork temp = mGson.fromJson(mGson.toJson(network), MeshNetwork.class);
        return export(prepareNetwork(temp, networkKeysConfig, applicationKeysConfig, nodesConfig,
                provisionersConfig, groupsConfig, scenesConfig), true);
    }

    /**
     * Configures and returns a network with the export configuration provided.
     */
    private MeshNetwork prepareNetwork(@NonNull final MeshNetwork network,
                                       @NonNull final NetworkKeysConfig networkKeysConfig,
                                       @NonNull final ApplicationKeysConfig applicationKeysConfig,
                                       @NonNull final NodesConfig nodesConfig,
                                       @NonNull final ProvisionersConfig provisionersConfig,
                                       @NonNull final GroupsConfig groupsConfig,
                                       @NonNull final ScenesConfig scenesConfig) {

        if (nodesConfig.getConfig() instanceof NodesConfig.ExportWithoutDeviceKey) {
            for (ProvisionedMeshNode node : network.nodes) {
                node.setDeviceKey(null);
            }
        } else if (nodesConfig.getConfig() instanceof NodesConfig.ExportSome) {
            network.nodes.clear();
            final List<ProvisionedMeshNode> withDeviceKey =
                    ((NodesConfig.ExportSome) nodesConfig.getConfig()).getWithDeviceKey();
            final List<ProvisionedMeshNode> withoutDeviceKey =
                    ((NodesConfig.ExportSome) nodesConfig.getConfig()).getWithoutDeviceKey();

            for (ProvisionedMeshNode node : withoutDeviceKey) {
                node.setDeviceKey(null);
            }

            network.nodes.addAll(withDeviceKey);
            network.nodes.addAll(withoutDeviceKey);

            for (Provisioner provisioner : network.provisioners) {
                if (!isProvisionerExistsInNodes(provisioner, network.nodes)) {
                    ProvisionedMeshNode provisionerNode =
                            new ProvisionedMeshNode(provisioner, network.netKeys, network.appKeys);
                    network.nodes.add(provisionerNode);
                }
            }
        }

        if (provisionersConfig.getConfig() instanceof ProvisionersConfig.ExportSome) {
            final ListIterator<Provisioner> provisionerListIterator =
                    network.provisioners.listIterator();
            while (provisionerListIterator.hasNext()) {
                final Provisioner provisioner = provisionerListIterator.next();
                if (!isProvisionerExistsInNodes(provisioner, network.nodes)) {
                    provisionerListIterator.remove();
                }
            }

            final List<Provisioner> selectedProvisioners =
                    ((ProvisionersConfig.ExportSome) provisionersConfig.getConfig()).getProvisioners();
            for (Provisioner provisioner : selectedProvisioners) {
                if (!network.isProvisionerUuidInUse(provisioner.getProvisionerUuid())) {
                    network.provisioners.add(provisioner);
                }
            }
        }

        if (networkKeysConfig.getConfig() instanceof NetworkKeysConfig.ExportSome) {
            network.setNetKeys(
                    ((NetworkKeysConfig.ExportSome) networkKeysConfig.getConfig()).getKeys());
        }

        if (applicationKeysConfig.getConfig() instanceof ApplicationKeysConfig.ExportSome) {
            network.appKeys.clear();
            final List<ApplicationKey> keys =
                    ((ApplicationKeysConfig.ExportSome) applicationKeysConfig.getConfig()).getKeys();
            for (ApplicationKey key : keys) {
                if (isApplicationKeyBound(network.getNetKeys(), key)) {
                    network.appKeys.add(key);
                }
            }
        }

        final ListIterator<ProvisionedMeshNode> nodeListIterator =
                network.nodes.listIterator();
        while (nodeListIterator.hasNext()) {
            final ProvisionedMeshNode node = nodeListIterator.next();
            if (!isNetworkKeyAdded(node, network.getNetKeys())) {
                nodeListIterator.remove();
                continue;
            }
            excludeAppKeys(node, network.appKeys);
        }

        if (groupsConfig.getConfig() instanceof GroupsConfig.ExportRelated) {
            excludeNonRelatedGroups(network);
        } else if (groupsConfig.getConfig() instanceof GroupsConfig.ExportSome) {
            network.groups =
                    ((GroupsConfig.ExportSome) groupsConfig.getConfig()).getGroups();
            for (ProvisionedMeshNode node : network.getNodes()) {
                for (Element element : node.getElements().values()) {
                    for (MeshModel model : element.getMeshModels().values()) {
                        for (Group group : network.groups) {
                            if (model.getPublicationSettings() != null &&
                                    isValidGroupAddress(
                                            model.getPublicationSettings().getPublishAddress()) &&
                                    model.getPublicationSettings().getPublishAddress()
                                            != group.getAddress()) {
                                model.setPublicationSettings(null);
                            }
                            model.getSubscribedAddresses().remove((Integer) group.getAddress());
                        }
                    }
                }
            }
        }

        if (scenesConfig.getConfig() instanceof ScenesConfig.ExportSome) {
            network.scenes =
                    ((ScenesConfig.ExportSome) scenesConfig.getConfig()).getScenes();
        }

        removeExcludedNodesFromScenes(network.nodes, network.scenes);
        return network;
    }

    private boolean isProvisionerExistsInNodes(@NonNull final Provisioner provisioner,
                                               @NonNull final List<ProvisionedMeshNode> nodes) {
        if (provisioner.getProvisionerAddress() != null) {
            for (ProvisionedMeshNode node : nodes) {
                if (node.getUuid().equalsIgnoreCase(provisioner.getProvisionerUuid()))
                    return true;
            }
        }
        return false;
    }

    private void excludeNonRelatedGroups(@NonNull final MeshNetwork network) {
        final List<Group> groups = new ArrayList<>();
        for (Group group : network.getGroups()) {
            for (ProvisionedMeshNode node : network.getNodes()) {
                if (isGroupInUse(node, group)) {
                    groups.add(group);
                }
            }
        }
        network.groups = groups;
    }

    private boolean isGroupInUse(@NonNull final ProvisionedMeshNode node,
                                 @NonNull final Group group) {
        for (final Element element : node.getElements().values()) {
            for (final MeshModel model : element.getMeshModels().values()) {
                if (model.getPublicationSettings().getPublishAddress() == group.getAddress() ||
                        model.getSubscribedAddresses().contains((Integer) group.getAddress())) {
                    return true;
                }
            }
        }
        return false;
    }

    private void removeExcludedNodesFromScenes(@NonNull List<ProvisionedMeshNode> nodes,
                                               @NonNull List<Scene> scenes) {
        ListIterator<Integer> addresses;
        Integer address;
        for (Scene scene : scenes) {
            addresses = scene.getAddresses().listIterator();
            while (addresses.hasNext()) {
                address = addresses.next();
                if (!isNodeAddressExistsInScene(nodes, address)) {
                    addresses.remove();
                }
            }
        }
    }

    private boolean isNodeAddressExistsInScene(@NonNull final List<ProvisionedMeshNode> nodes,
                                               @NonNull final Integer address) {
        for (ProvisionedMeshNode node : nodes) {
            if (address == node.getUnicastAddress()) {
                return true;
            }
        }
        return false;
    }

    private void excludeAppKeys(@NonNull final ProvisionedMeshNode node,
                                @NonNull final List<ApplicationKey> applicationKeys) {
        int index;
        for (Element element : node.getElements().values()) {
            for (MeshModel model : element.getMeshModels().values()) {
                final ListIterator<Integer> boundKeyIndexes =
                        model.getBoundAppKeyIndexes().listIterator();
                while (boundKeyIndexes.hasNext()) {
                    index = boundKeyIndexes.next();
                    if (!isApplicationKeyBound(index, applicationKeys)) {
                        boundKeyIndexes.remove();
                        if (model.getPublicationSettings() != null &&
                                model.getPublicationSettings().getAppKeyIndex() == index) {
                            model.setPublicationSettings(null);
                        }
                    }
                }
            }
        }
    }

    private boolean isApplicationKeyBound(@NonNull final List<NetworkKey> networkKeys,
                                          @NonNull final ApplicationKey applicationKey) {
        for (NetworkKey networkKey : networkKeys) {
            if (networkKey.keyIndex == applicationKey.keyIndex) return true;
        }
        return false;
    }

    private boolean isApplicationKeyBound(@NonNull final Integer index,
                                          @NonNull final List<ApplicationKey> keys) {
        for (ApplicationKey key : keys) {
            if (index == key.getKeyIndex()) return true;
        }
        return false;
    }

    private boolean isNetworkKeyAdded(@NonNull final ProvisionedMeshNode node,
                                      @NonNull final List<NetworkKey> networkKeys) {
        for (NetworkKey networkKey : networkKeys) {
            for (NodeKey nodeKey : node.getAddedNetKeys()) {
                if (nodeKey.getIndex() == networkKey.getKeyIndex()) return true;
            }
        }
        return false;
    }
}