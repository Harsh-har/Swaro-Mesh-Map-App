package no.nordicsemi.android.swarorgbww.data;

import androidx.annotation.RestrictTo;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;
import no.nordicsemi.android.swarorgbww.Scene;

@Dao
@RestrictTo(RestrictTo.Scope.LIBRARY)
public interface SceneDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(final Scene scene);

    @Update(onConflict = OnConflictStrategy.REPLACE)
    void update(final Scene scene);

    @Query("DELETE FROM scene WHERE `number` = :number")
    void delete(final int number);
}
