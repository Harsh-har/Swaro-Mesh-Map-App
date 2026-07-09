package no.nordicsemi.android.swaromapmesh.data;

import androidx.room.Relation;

import java.util.List;

import no.nordicsemi.android.swaromapmesh.MeshNetwork;
import no.nordicsemi.android.swaromapmesh.transport.ProvisionedMeshNode;

@SuppressWarnings("unused")
class ProvisionedMeshNodes {

    public String meshUuid;

    @Relation(entity = MeshNetwork.class, parentColumn = "mesh_uuid", entityColumn = "mesh_uuid")
    public List<ProvisionedMeshNode> meshNodes;

}
