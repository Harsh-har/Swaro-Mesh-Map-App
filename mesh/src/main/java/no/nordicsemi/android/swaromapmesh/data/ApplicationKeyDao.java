package no.nordicsemi.android.swaromapmesh.data;

import androidx.annotation.RestrictTo;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Update;
import no.nordicsemi.android.swaromapmesh.ApplicationKey;

@RestrictTo(RestrictTo.Scope.LIBRARY)
@Dao
public interface ApplicationKeyDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insert(final ApplicationKey applicationKey);

    @Update(onConflict = OnConflictStrategy.REPLACE)
    void update(final ApplicationKey applicationKey);

    @Delete
    void delete(final ApplicationKey applicationKey);

}
