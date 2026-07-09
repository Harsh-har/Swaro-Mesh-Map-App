package no.nordicsemi.android.swaromapmesh.data;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;
import no.nordicsemi.android.swaromapmesh.NetworkKey;

@Dao
public interface NetworkKeyDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(final NetworkKey networkKey);

    @Update
    int update(final NetworkKey networkKey);

    @Query("DELETE FROM network_key WHERE `index` = :index")
    int delete(final int index);
}
