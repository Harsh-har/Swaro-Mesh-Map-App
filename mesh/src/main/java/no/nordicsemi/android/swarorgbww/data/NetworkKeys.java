package no.nordicsemi.android.swarorgbww.data;

import androidx.room.Relation;

import java.util.List;

import no.nordicsemi.android.swarorgbww.MeshNetwork;
import no.nordicsemi.android.swarorgbww.NetworkKey;

@SuppressWarnings("unused")
class NetworkKeys {

    public String uuid;

    @Relation(entity = MeshNetwork.class, parentColumn = "mesh_uuid", entityColumn = "mesh_uuid")
    public List<NetworkKey> loadNetworkKeys;

}
