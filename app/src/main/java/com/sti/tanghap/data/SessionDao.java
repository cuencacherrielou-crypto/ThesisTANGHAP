package com.sti.tanghap.data;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import java.util.List;

@Dao
public interface SessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(SessionEntity s);

    @Query("SELECT * FROM sessions WHERE userId = :userId ORDER BY startTimeMillis DESC")
    List<SessionEntity> getAllForUser(String userId);

    @Query("SELECT * FROM sessions WHERE id = :id AND userId = :userId LIMIT 1")
    SessionEntity getByIdForUser(String id, String userId);

    @Query("SELECT * FROM sessions WHERE userId = :userId AND startTimeMillis >= :sinceMillis ORDER BY startTimeMillis ASC")
    List<SessionEntity> getSinceForUser(String userId, long sinceMillis);

    /** Sessions that were still running at or after the given time (for "today" counts across midnight). */
    @Query("SELECT * FROM sessions WHERE userId = :userId AND endTimeMillis >= :sinceMillis ORDER BY startTimeMillis ASC")
    List<SessionEntity> getEndedSinceForUser(String userId, long sinceMillis);

    @Query("SELECT * FROM sessions WHERE userId = :userId AND synced = 0 ORDER BY startTimeMillis ASC")
    List<SessionEntity> getUnsyncedForUser(String userId);

    @Query("UPDATE sessions SET synced = 1 WHERE id = :id")
    void markSynced(String id);
}
