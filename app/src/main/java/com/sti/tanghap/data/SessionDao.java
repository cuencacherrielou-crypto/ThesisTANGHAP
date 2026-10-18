package com.sti.tanghap.data;
import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import java.util.List;

@Dao
public interface SessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(SessionEntity s);

    @Query("SELECT * FROM sessions ORDER BY startTimeMillis DESC")
    List<SessionEntity> getAll();

    @Query("SELECT * FROM sessions WHERE userId = :userId ORDER BY startTimeMillis DESC")
    List<SessionEntity> getAllForUser(String userId);

    @Query("SELECT * FROM sessions ORDER BY startTimeMillis DESC")
    LiveData<List<SessionEntity>> getAllLiveData();

    @Query("SELECT * FROM sessions WHERE userId = :userId ORDER BY startTimeMillis DESC")
    LiveData<List<SessionEntity>> getAllLiveDataForUser(String userId);

    @Query("SELECT * FROM sessions WHERE startTimeMillis >= :sinceMillis ORDER BY startTimeMillis ASC")
    List<SessionEntity> getSince(long sinceMillis);

    @Query("SELECT * FROM sessions WHERE userId = :userId AND startTimeMillis >= :sinceMillis ORDER BY startTimeMillis ASC")
    List<SessionEntity> getSinceForUser(String userId, long sinceMillis);

    @Query("SELECT * FROM sessions WHERE synced = 0 ORDER BY startTimeMillis ASC")
    List<SessionEntity> getUnsynced();

    @Query("UPDATE sessions SET synced = 1 WHERE id = :id")
    void markSynced(String id);
}