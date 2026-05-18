package no.nordicsemi.android.swarorgbww.swajaui;

import static android.content.Context.MODE_PRIVATE;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import no.nordicsemi.android.swarorgbww.transport.ConfigModelPublicationSet;
import no.nordicsemi.android.swarorgbww.transport.Element;
import no.nordicsemi.android.swarorgbww.transport.MeshModel;
import no.nordicsemi.android.swarorgbww.transport.ProvisionedMeshNode;
import no.nordicsemi.android.swarorgbww.viewmodels.SharedViewModel;

public class AutoPublicationHelper {
    private static final String TAG = "AutoPublicationHelper";
    private static final String PUBLICATION_PREFS = "publication_prefs";

    // Publication settings
    private static final int DEFAULT_PUBLISH_TTL = 5;
    private static final int DEFAULT_RETRANSMIT_COUNT = 3;
    private static final int DEFAULT_RETRANSMIT_INTERVAL = 2;
    private static final int PUBLICATION_STEPS = 0;
    private static final int PUBLICATION_RESOLUTION = 0;
    private static final boolean CREDENTIAL_FLAG = false;

    // Model IDs
    private static final int GENERIC_ONOFF_CLIENT = 0x1001;
    private static final int GENERIC_ONOFF_SERVER = 0x1000;

    /**
     * Setup bidirectional publication between client and server
     */
    public static void setupBidirectionalPublication(
            SharedViewModel viewModel,
            ProvisionedMeshNode clientNode,
            ProvisionedMeshNode serverNode,
            int clientElementIndex,
            int serverElementIndex,
            int appKeyIndex) {

        if (viewModel == null) {
            Log.e(TAG, "ViewModel is null");
            return;
        }

        Log.d(TAG, "═══════════════════════════════════════");
        Log.d(TAG, "Setting up bidirectional publication");
        Log.d(TAG, String.format("Client Node: 0x%04X, Element[%d]",
                clientNode.getUnicastAddress(), clientElementIndex));
        Log.d(TAG, String.format("Server Node: 0x%04X, Element[%d]",
                serverNode.getUnicastAddress(), serverElementIndex));

        // Get the actual element addresses
        int clientElementAddr = getElementAddress(clientNode, clientElementIndex);
        int serverElementAddr = getElementAddress(serverNode, serverElementIndex);

        if (clientElementAddr == -1 || serverElementAddr == -1) {
            Log.e(TAG, "Could not find element addresses");
            return;
        }

        // Get the Generic OnOff models
        int clientModelId = getClientModelId(clientNode, clientElementAddr);
        int serverModelId = getServerModelId(serverNode, serverElementAddr);

        if (clientModelId == -1) {
            Log.e(TAG, "Could not find Generic OnOff Client model at element 0x" +
                    String.format("%04X", clientElementAddr));
            return;
        }

        if (serverModelId == -1) {
            Log.e(TAG, "Could not find Generic OnOff Server model at element 0x" +
                    String.format("%04X", serverElementAddr));
            return;
        }

        // 1. Client publishes to Server
        setupPublication(
                viewModel,
                clientNode,
                clientElementAddr,
                clientModelId,
                serverElementAddr,
                appKeyIndex,
                "Client → Server"
        );

        // 2. Server publishes to Client
        setupPublication(
                viewModel,
                serverNode,
                serverElementAddr,
                serverModelId,
                clientElementAddr,
                appKeyIndex,
                "Server → Client"
        );

        Log.d(TAG, "✅ Publication setup completed");
        Log.d(TAG, "═══════════════════════════════════════");
    }

    /**
     * Setup publication from a model to a target address
     */
    public static void setupPublication(
            SharedViewModel viewModel,
            ProvisionedMeshNode node,
            int sourceElementAddr,
            int modelId,
            int targetAddress,
            int appKeyIndex,
            String direction) {

        Log.d(TAG, String.format(
                "📤 %s: Node 0x%04X, Elem 0x%04X, Model 0x%04X → 0x%04X",
                direction, node.getUnicastAddress(), sourceElementAddr, modelId, targetAddress));

        try {
            ConfigModelPublicationSet publicationSet = new ConfigModelPublicationSet(
                    sourceElementAddr,
                    targetAddress,
                    appKeyIndex,
                    CREDENTIAL_FLAG,
                    DEFAULT_PUBLISH_TTL,
                    PUBLICATION_STEPS,
                    PUBLICATION_RESOLUTION,
                    DEFAULT_RETRANSMIT_COUNT,
                    DEFAULT_RETRANSMIT_INTERVAL,
                    modelId
            );

            // Send the publication set message
            viewModel.getMeshManagerApi().createMeshPdu(
                    node.getUnicastAddress(),
                    publicationSet
            );

            Log.d(TAG, "✅ Publication set message sent for " + direction);

        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Failed to create publication set: " + e.getMessage());
        }
    }

    /**
     * Get element address by index (0-based)
     */
    private static int getElementAddress(ProvisionedMeshNode node, int elementIndex) {
        if (node == null || node.getElements() == null) return -1;

        List<Element> sortedElements = new ArrayList<>(node.getElements().values());
        Collections.sort(sortedElements, (a, b) ->
                Integer.compare(a.getElementAddress(), b.getElementAddress()));

        if (elementIndex >= 0 && elementIndex < sortedElements.size()) {
            return sortedElements.get(elementIndex).getElementAddress();
        }

        Log.e(TAG, "Element index " + elementIndex + " not found. Total elements: "
                + sortedElements.size());
        return -1;
    }

    /**
     * Get element index by element address
     */
    public static int getElementIndex(ProvisionedMeshNode node, int elementAddress) {
        if (node == null || node.getElements() == null) return -1;

        List<Element> sortedElements = new ArrayList<>(node.getElements().values());
        Collections.sort(sortedElements, (a, b) ->
                Integer.compare(a.getElementAddress(), b.getElementAddress()));

        for (int i = 0; i < sortedElements.size(); i++) {
            if (sortedElements.get(i).getElementAddress() == elementAddress) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Find Generic OnOff Client model ID in the element
     */
    private static int getClientModelId(ProvisionedMeshNode node, int elementAddr) {
        Element element = node.getElements().get(elementAddr);
        if (element == null) return -1;

        for (MeshModel model : element.getMeshModels().values()) {
            if (model.getModelId() == GENERIC_ONOFF_CLIENT) {
                Log.d(TAG, "Found Client model at element 0x" +
                        String.format("%04X", elementAddr));
                return model.getModelId();
            }
        }
        Log.w(TAG, "No Client model found at element 0x" +
                String.format("%04X", elementAddr));
        return -1;
    }

    /**
     * Find Generic OnOff Server model ID in the element
     */
    private static int getServerModelId(ProvisionedMeshNode node, int elementAddr) {
        Element element = node.getElements().get(elementAddr);
        if (element == null) return -1;

        for (MeshModel model : element.getMeshModels().values()) {
            if (model.getModelId() == GENERIC_ONOFF_SERVER) {
                Log.d(TAG, "Found Server model at element 0x" +
                        String.format("%04X", elementAddr));
                return model.getModelId();
            }
        }
        Log.w(TAG, "No Server model found at element 0x" +
                String.format("%04X", elementAddr));
        return -1;
    }


    public static void storePublicationInfo(Context context, String nodeUuid,
                                            int elementAddr, int modelId, int publishTo) {
        if (context == null) {
            Log.e(TAG, "Context is null, cannot store publication info");
            return;
        }

        SharedPreferences prefs = context.getSharedPreferences(PUBLICATION_PREFS, MODE_PRIVATE);
        String key = String.format("pub_%s_%04X_%04X", nodeUuid, elementAddr, modelId);
        prefs.edit().putInt(key, publishTo).apply();

        Log.d(TAG, "Stored publication info: " + key + " → 0x" +
                String.format("%04X", publishTo));
    }

    /**
     * Get stored publication info
     */
    public static int getPublicationTarget(Context context, String nodeUuid,
                                           int elementAddr, int modelId) {
        if (context == null) return -1;

        SharedPreferences prefs = context.getSharedPreferences(PUBLICATION_PREFS, MODE_PRIVATE);
        String key = String.format("pub_%s_%04X_%04X", nodeUuid, elementAddr, modelId);
        return prefs.getInt(key, -1);
    }

    /**
     * Check if publication is already setup for a pair
     */
    public static boolean isPublicationSetupComplete(SharedPreferences prefs,
                                                     int clientAddr, int serverAddr) {
        if (prefs == null) return false;
        String key = "pub_setup_" + clientAddr + "_" + serverAddr;
        return prefs.getBoolean(key, false);
    }

    /**
     * Mark publication as setup for a pair
     */
    public static void markPublicationSetupComplete(SharedPreferences prefs,
                                                    int clientAddr, int serverAddr) {
        if (prefs == null) return;
        String key = "pub_setup_" + clientAddr + "_" + serverAddr;
        prefs.edit().putBoolean(key, true).apply();
        Log.d(TAG, String.format("✅ Marked publication setup complete: 0x%04X ↔ 0x%04X",
                clientAddr, serverAddr));
    }
}