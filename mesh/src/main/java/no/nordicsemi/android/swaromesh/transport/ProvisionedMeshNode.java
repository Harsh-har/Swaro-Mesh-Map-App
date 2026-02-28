package no.nordicsemi.android.swaromesh.transport;


import android.annotation.SuppressLint;
import android.os.Parcel;

import com.google.gson.annotations.SerializedName;  // ✅ ADD THIS IMPORT

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import androidx.annotation.NonNull;
import androidx.annotation.RestrictTo;
import androidx.annotation.VisibleForTesting;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Ignore;
import androidx.room.Index;

import no.nordicsemi.android.swaromesh.ApplicationKey;
import no.nordicsemi.android.swaromesh.Features;
import no.nordicsemi.android.swaromesh.MeshNetwork;
import no.nordicsemi.android.swaromesh.NetworkKey;
import no.nordicsemi.android.swaromesh.NodeKey;
import no.nordicsemi.android.swaromesh.Provisioner;
import no.nordicsemi.android.swaromesh.models.ConfigurationServerModel;
import no.nordicsemi.android.swaromesh.models.SigModelParser;
import no.nordicsemi.android.swaromesh.provisionerstates.UnprovisionedMeshNode;
import no.nordicsemi.android.swaromesh.utils.MeshParserUtils;
import no.nordicsemi.android.swaromesh.utils.NetworkTransmitSettings;
import no.nordicsemi.android.swaromesh.utils.RelaySettings;
import no.nordicsemi.android.swaromesh.utils.SecureUtils;
import no.nordicsemi.android.swaromesh.utils.SparseIntArrayParcelable;

import static androidx.room.ForeignKey.CASCADE;

@Entity(tableName = "nodes",
        foreignKeys = @ForeignKey(entity = MeshNetwork.class,
                parentColumns = "mesh_uuid",
                childColumns = "mesh_uuid",
                onUpdate = CASCADE, onDelete = CASCADE),
        indices = @Index("mesh_uuid"))
public final class ProvisionedMeshNode extends ProvisionedBaseMeshNode {

    // ✅ MAC address column with SerializedName annotation
    @ColumnInfo(name = "mac_address")
    @SerializedName("mac_address")  // ✅ ADD THIS ANNOTATION
    private String macAddress;

    public static final Creator<ProvisionedMeshNode> CREATOR = new Creator<ProvisionedMeshNode>() {
        @Override
        public ProvisionedMeshNode createFromParcel(Parcel in) {
            return new ProvisionedMeshNode(in);
        }

        @Override
        public ProvisionedMeshNode[] newArray(int size) {
            return new ProvisionedMeshNode[size];
        }
    };

    @VisibleForTesting(otherwise = VisibleForTesting.PROTECTED)
    @RestrictTo(RestrictTo.Scope.LIBRARY)
    public ProvisionedMeshNode() {
    }

    /**
     * Constructor to be used only by the library
     *
     * @param node {@link UnprovisionedMeshNode}
     */
    @Ignore
    @RestrictTo(RestrictTo.Scope.LIBRARY)
    public ProvisionedMeshNode(final UnprovisionedMeshNode node) {

        uuid = node.getDeviceUuid().toString();
        //isConfigured = node.isConfigured();
        nodeName = node.getNodeName();
        mAddedNetKeys.add(new NodeKey(node.getKeyIndex()));
        mFlags = node.getFlags();
        unicastAddress = node.getUnicastAddress();
        deviceKey = node.getDeviceKey();
        ttl = node.getTtl();
        mTimeStampInMillis = node.getTimeStamp();

        // ✅ MAC COPY HERE - CRITICAL FIX
        macAddress = node.getMacAddress();

        // Here we add some dummy elements with empty models to occupy the addresses in use.
        for (int i = 0; i < node.getProvisioningCapabilities().getNumberOfElements(); i++) {
            mElements.put(unicastAddress + i, new Element(unicastAddress + i, 0, new HashMap<>()));
        }
        security = node.isSecure() ? HIGH : LOW;
    }

    /**
     * Constructor to be used only by the library
     *
     * @param provisioner {@link Provisioner}
     * @param netKeys     List of {@link NetworkKey}
     * @param appKeys     List of {@link ApplicationKey}
     */
    @Ignore
    @RestrictTo(RestrictTo.Scope.LIBRARY)
    @SuppressLint("UseSparseArrays")
    public ProvisionedMeshNode(@NonNull final Provisioner provisioner,
                               @NonNull final List<NetworkKey> netKeys,
                               @NonNull final List<ApplicationKey> appKeys) {
        this.meshUuid = provisioner.getMeshUuid();
        uuid = provisioner.getProvisionerUuid();
        nodeName = provisioner.getProvisionerName();

        for (NetworkKey key : netKeys) {
            mAddedNetKeys.add(new NodeKey(key.getKeyIndex(), false));
        }
        for (ApplicationKey key : appKeys) {
            mAddedAppKeys.add(new NodeKey(key.getKeyIndex(), false));
        }

        if (provisioner.getProvisionerAddress() != null)
            unicastAddress = provisioner.getProvisionerAddress();

        sequenceNumber = 0;
        deviceKey = SecureUtils.generateRandomNumber();
        ttl = provisioner.getGlobalTtl();
        mTimeStampInMillis = System.currentTimeMillis();
        final MeshModel model = SigModelParser.getSigModel(SigModelParser.CONFIGURATION_CLIENT);
        final HashMap<Integer, MeshModel> models = new HashMap<>();
        models.put(model.getModelId(), model);
        final Element element = new Element(unicastAddress, 0, models);
        final HashMap<Integer, Element> elements = new HashMap<>();
        elements.put(unicastAddress, element);
        mElements = elements;
        nodeFeatures = new Features(Features.UNSUPPORTED, Features.UNSUPPORTED, Features.UNSUPPORTED, Features.UNSUPPORTED);

        // Provisioner node has no MAC
        macAddress = null;
    }

    @Ignore
    ProvisionedMeshNode(Parcel in) {
        uuid = in.readString();
        isConfigured = in.readByte() != 1;
        nodeName = in.readString();
        in.readList(mAddedNetKeys, NodeKey.class.getClassLoader());
        mFlags = in.createByteArray();
        unicastAddress = in.readInt();
        deviceKey = in.createByteArray();
        ttl = (Integer) in.readValue(Integer.class.getClassLoader());
        sequenceNumber = in.readInt();
        companyIdentifier = (Integer) in.readValue(Integer.class.getClassLoader());
        productIdentifier = (Integer) in.readValue(Integer.class.getClassLoader());
        versionIdentifier = (Integer) in.readValue(Integer.class.getClassLoader());
        crpl = (Integer) in.readValue(Integer.class.getClassLoader());
        nodeFeatures = (Features) in.readValue(Features.class.getClassLoader());
        in.readMap(mElements, Element.class.getClassLoader());
        sortElements(mElements);
        in.readList(mAddedAppKeys, NodeKey.class.getClassLoader());
        mTimeStampInMillis = in.readLong();
        mSeqAuth = in.readParcelable(SparseIntArrayParcelable.class.getClassLoader());
        secureNetworkBeaconSupported = (Boolean) in.readValue(Boolean.class.getClassLoader());
        networkTransmitSettings = in.readParcelable(NetworkTransmitSettings.class.getClassLoader());
        relaySettings = in.readParcelable(RelaySettings.class.getClassLoader());
        excluded = in.readInt() != 1;

        // ✅ Read MAC
        macAddress = in.readString();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(uuid);
        dest.writeByte((byte) (isConfigured ? 1 : 0));
        dest.writeString(nodeName);
        dest.writeList(mAddedNetKeys);
        dest.writeByteArray(mFlags);
        dest.writeInt(unicastAddress);
        dest.writeByteArray(deviceKey);
        dest.writeValue(ttl);
        dest.writeInt(sequenceNumber);
        dest.writeValue(companyIdentifier);
        dest.writeValue(productIdentifier);
        dest.writeValue(versionIdentifier);
        dest.writeValue(crpl);
        dest.writeValue(nodeFeatures);
        dest.writeMap(mElements);
        dest.writeList(mAddedAppKeys);
        dest.writeLong(mTimeStampInMillis);
        dest.writeParcelable(mSeqAuth, flags);
        dest.writeValue(secureNetworkBeaconSupported);
        dest.writeParcelable(networkTransmitSettings, flags);
        dest.writeParcelable(relaySettings, flags);
        dest.writeInt((excluded ? 1 : 0));

        // ✅ Write MAC
        dest.writeString(macAddress);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    // ======================================================
    // ✅ MAC GETTER/SETTER
    // ======================================================
    public String getMacAddress() {
        return macAddress;
    }


    public void setMacAddress(final String macAddress) {
        this.macAddress = macAddress;
    }

    // ======================================================
    // Existing methods (same as your file)
    // ======================================================

    public Map<Integer, Element> getElements() {
        return mElements;
    }

    public boolean hasUnicastAddress(final int unicastAddress) {
        if (unicastAddress == getUnicastAddress())
            return true;
        for (Element element : mElements.values()) {
            if (element.getElementAddress() == unicastAddress)
                return true;
        }
        return false;
    }

    @RestrictTo(RestrictTo.Scope.LIBRARY)
    public void setElements(final Map<Integer, Element> elements) {
        mElements = elements;
    }

    public byte[] getDeviceKey() {
        return deviceKey;
    }

    @RestrictTo(RestrictTo.Scope.LIBRARY)
    public void setDeviceKey(final byte[] deviceKey) {
        this.deviceKey = deviceKey;
    }

    public int getSequenceNumber() {
        return sequenceNumber;
    }

    public void setSequenceNumber(final int sequenceNumber) {
        this.sequenceNumber = sequenceNumber;
    }

    public Integer getCompanyIdentifier() {
        return companyIdentifier;
    }

    @RestrictTo(RestrictTo.Scope.LIBRARY)
    public void setCompanyIdentifier(final Integer companyIdentifier) {
        this.companyIdentifier = companyIdentifier;
    }

    public Integer getProductIdentifier() {
        return productIdentifier;
    }

    @RestrictTo(RestrictTo.Scope.LIBRARY)
    public void setProductIdentifier(final Integer productIdentifier) {
        this.productIdentifier = productIdentifier;
    }

    public Integer getVersionIdentifier() {
        return versionIdentifier;
    }

    @RestrictTo(RestrictTo.Scope.LIBRARY)
    public void setVersionIdentifier(final Integer versionIdentifier) {
        this.versionIdentifier = versionIdentifier;
    }

    public Integer getCrpl() {
        return crpl;
    }

    public void setCrpl(final Integer crpl) {
        this.crpl = crpl;
    }

    public Features getNodeFeatures() {
        return nodeFeatures;
    }

    @RestrictTo(RestrictTo.Scope.LIBRARY)
    public void setNodeFeatures(final Features features) {
        this.nodeFeatures = features;
    }

    public List<NodeKey> getAddedNetKeys() {
        return Collections.unmodifiableList(mAddedNetKeys);
    }

    @RestrictTo(RestrictTo.Scope.LIBRARY)
    public void setAddedNetKeys(final List<NodeKey> addedNetKeyIndexes) {
        mAddedNetKeys = addedNetKeyIndexes;
    }

    @RestrictTo(RestrictTo.Scope.LIBRARY)
    void setAddedNetKeyIndex(final int index) {
        if (!MeshParserUtils.isNodeKeyExists(mAddedNetKeys, index)) {
            mAddedNetKeys.add(new NodeKey(index));
        }
    }

    @RestrictTo(RestrictTo.Scope.LIBRARY)
    void updateAddedNetKey(final int index) {
        final NodeKey nodeKey = MeshParserUtils.getNodeKey(mAddedNetKeys, index);
        if (nodeKey != null) {
            nodeKey.setUpdated(true);
        }
    }

    @RestrictTo(RestrictTo.Scope.LIBRARY)
    void updateNetKeyList(final List<Integer> indexes) {
        mAddedNetKeys.clear();
        for (Integer index : indexes) {
            mAddedNetKeys.add(new NodeKey(index, false));
        }
    }

    @RestrictTo(RestrictTo.Scope.LIBRARY)
    void removeAddedNetKeyIndex(final int index) {
        for (int i = 0; i < mAddedNetKeys.size(); i++) {
            final int keyIndex = mAddedNetKeys.get(i).getIndex();
            if (keyIndex == index) {
                mAddedNetKeys.remove(i);
                for (Element element : mElements.values()) {
                    for (MeshModel model : element.getMeshModels().values()) {
                        if (model.getModelId() == SigModelParser.CONFIGURATION_SERVER) {
                            final ConfigurationServerModel configServerModel = (ConfigurationServerModel) model;
                            if (configServerModel.getHeartbeatPublication() != null &&
                                    configServerModel.getHeartbeatPublication().getNetKeyIndex() == index) {
                                configServerModel.setHeartbeatPublication(null);
                            }
                        }
                    }
                }
                break;
            }
        }
    }

    public List<NodeKey> getAddedAppKeys() {
        return mAddedAppKeys;
    }

    @RestrictTo(RestrictTo.Scope.LIBRARY)
    public void setAddedAppKeys(final List<NodeKey> addedAppKeyIndexes) {
        mAddedAppKeys = addedAppKeyIndexes;
    }


    @RestrictTo(RestrictTo.Scope.LIBRARY)
    void setAddedAppKeyIndex(final int index) {
        if (!MeshParserUtils.isNodeKeyExists(mAddedAppKeys, index)) {
            this.mAddedAppKeys.add(new NodeKey(index));
        }
    }
    @RestrictTo(RestrictTo.Scope.LIBRARY)
    void updateAddedAppKey(final int index) {
        final NodeKey nodeKey = MeshParserUtils.getNodeKey(mAddedNetKeys, index);
        if (nodeKey != null) {
            nodeKey.setUpdated(true);
        }
    }

    @RestrictTo(RestrictTo.Scope.LIBRARY)
    void updateAppKeyList(final int netKeyIndex, @NonNull final List<Integer> indexes, @NonNull final List<ApplicationKey> keyIndexes) {
        if (mAddedAppKeys.isEmpty()) {
            mAddedAppKeys.addAll(addAppKeyList(indexes, new ArrayList<>()));
        } else {
            final ArrayList<NodeKey> tempList = new ArrayList<>(mAddedAppKeys);
            for (ApplicationKey applicationKey : keyIndexes) {
                if (applicationKey.getBoundNetKeyIndex() == netKeyIndex) {
                    for (NodeKey nodeKey : mAddedAppKeys) {
                        if (nodeKey.getIndex() == applicationKey.getKeyIndex()) {
                            tempList.remove(nodeKey);
                        }
                    }
                }
            }
            mAddedAppKeys.clear();
            addAppKeyList(indexes, tempList);
            mAddedAppKeys.addAll(tempList);
        }
    }

    private List<NodeKey> addAppKeyList(@NonNull final List<Integer> indexes, @NonNull final ArrayList<NodeKey> tempList) {
        for (Integer index : indexes) {
            tempList.add(new NodeKey(index, false));
        }
        return tempList;
    }

    @RestrictTo(RestrictTo.Scope.LIBRARY)
    void removeAddedAppKeyIndex(final int index) {
        for (int i = 0; i < mAddedAppKeys.size(); i++) {
            final int keyIndex = mAddedAppKeys.get(i).getIndex();
            if (keyIndex == index) {
                mAddedAppKeys.remove(i);
                for (Map.Entry<Integer, Element> elementEntry : getElements().entrySet()) {
                    final Element element = elementEntry.getValue();
                    for (Map.Entry<Integer, MeshModel> modelEntry : element.getMeshModels().entrySet()) {
                        final MeshModel model = modelEntry.getValue();
                        if (model != null) {
                            for (int j = 0; j < model.getBoundAppKeyIndexes().size(); j++) {
                                final int boundKeyIndex = model.getBoundAppKeyIndexes().get(j);
                                if (boundKeyIndex == index) {
                                    model.mBoundAppKeyIndexes.remove(j);
                                    break;
                                }
                            }
                        }
                    }
                }
                break;
            }
        }
    }

    void setCompositionData(
            @NonNull final ConfigCompositionDataStatus configCompositionDataStatus) {
        companyIdentifier = configCompositionDataStatus.getCompanyIdentifier();
        productIdentifier = configCompositionDataStatus.getProductIdentifier();
        versionIdentifier = configCompositionDataStatus.getVersionIdentifier();
        crpl = configCompositionDataStatus.getCrpl();
        final boolean relayFeatureSupported = configCompositionDataStatus.isRelayFeatureSupported();
        final boolean proxyFeatureSupported = configCompositionDataStatus.isProxyFeatureSupported();
        final boolean friendFeatureSupported = configCompositionDataStatus.isFriendFeatureSupported();
        final boolean lowPowerFeatureSupported = configCompositionDataStatus.isLowPowerFeatureSupported();
        nodeFeatures = new Features(friendFeatureSupported ? Features.DISABLED : Features.UNSUPPORTED,
                lowPowerFeatureSupported ? Features.DISABLED : Features.UNSUPPORTED,
                proxyFeatureSupported ? Features.DISABLED : Features.UNSUPPORTED,
                relayFeatureSupported ? Features.DISABLED : Features.UNSUPPORTED);
        mElements.putAll(configCompositionDataStatus.getElements());
    }
    void setAppKeyBindStatus(@NonNull final ConfigModelAppStatus configModelAppStatus) {
        if (configModelAppStatus.isSuccessful()) {
            final Element element = mElements.get(configModelAppStatus.getElementAddress());
            if (element != null) {
                final int modelIdentifier = configModelAppStatus.getModelIdentifier();
                final MeshModel model = element.getMeshModels().get(modelIdentifier);
                if (model != null) {
                    final int appKeyIndex = configModelAppStatus.getAppKeyIndex();
                    model.setBoundAppKeyIndex(appKeyIndex);
                }
            }
        }
    }

    void setAppKeyUnbindStatus(@NonNull final ConfigModelAppStatus configModelAppStatus) {
        if (configModelAppStatus.isSuccessful()) {
            final Element element = mElements.get(configModelAppStatus.getElementAddress());
            if (element != null) {
                final int modelIdentifier = configModelAppStatus.getModelIdentifier();
                final MeshModel model = element.getMeshModels().get(modelIdentifier);
                final int appKeyIndex = configModelAppStatus.getAppKeyIndex();
                if (model != null) {
                    model.removeBoundAppKeyIndex(appKeyIndex);
                }
            }
        }
    }

    private void sortElements(final Map<Integer, Element> unorderedElements) {
        final Set<Integer> unorderedKeys = unorderedElements.keySet();
        final List<Integer> orderedKeys = new ArrayList<>(unorderedKeys);
        Collections.sort(orderedKeys);
        for (int key : orderedKeys) {
            mElements.put(key, unorderedElements.get(key));
        }
    }

    @RestrictTo(RestrictTo.Scope.LIBRARY)
    void setSeqAuth(final int src, final int seqAuth) {
        mSeqAuth.put(src, seqAuth);
    }

    public Integer getSeqAuth(final int src) {
        if (mSeqAuth.size() == 0) {
            return null;
        }
        return mSeqAuth.get(src);
    }

    public boolean isExist(final int modelId) {
        for (Map.Entry<Integer, Element> elementEntry : mElements.entrySet()) {
            final Element element = elementEntry.getValue();
            for (Map.Entry<Integer, MeshModel> modelEntry : element.getMeshModels().entrySet()) {
                final MeshModel model = modelEntry.getValue();
                if (model != null && model.getModelId() == modelId) {
                    return true;
                }
            }
        }
        return false;
    }

    public int incrementSequenceNumber() {
        return sequenceNumber = sequenceNumber + 1;
    }

}