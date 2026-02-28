package no.nordicsemi.android.swaromesh.data;

import androidx.room.Relation;

import java.util.List;

import no.nordicsemi.android.swaromesh.Group;
import no.nordicsemi.android.swaromesh.MeshNetwork;

@SuppressWarnings("unused")
class Groups {

    public String uuid;

    @Relation(entity = MeshNetwork.class, parentColumn = "mesh_uuid", entityColumn = "mesh_uuid")
    public List<Group> groups;

}
