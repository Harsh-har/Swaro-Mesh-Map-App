package no.nordicsemi.android.swarorgbww.data;

import androidx.room.Relation;

import java.util.List;

import no.nordicsemi.android.swarorgbww.MeshNetwork;
import no.nordicsemi.android.swarorgbww.Scene;

@SuppressWarnings("unused")
class Scenes {

    public String uuid;

    @Relation(entity = MeshNetwork.class, parentColumn = "mesh_uuid", entityColumn = "mesh_uuid")
    public List<Scene> scenes;

}
