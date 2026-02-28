package no.nordicsemi.android.swaromesh.data;

import androidx.room.Relation;

import java.util.List;

import no.nordicsemi.android.swaromesh.MeshNetwork;
import no.nordicsemi.android.swaromesh.Scene;

@SuppressWarnings("unused")
class Scenes {

    public String uuid;

    @Relation(entity = MeshNetwork.class, parentColumn = "mesh_uuid", entityColumn = "mesh_uuid")
    public List<Scene> scenes;

}
