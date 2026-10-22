package com.sti.tanghap.data;
import android.content.Context;
import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

@Database(entities = {SessionEntity.class}, version = 4)
public abstract class AppDatabase extends RoomDatabase {
    private static volatile AppDatabase INSTANCE;
    public abstract SessionDao sessionDao();

    // v3 adds the recorded pressure trace. A real migration keeps existing (and unsynced) sessions.
    static final Migration MIGRATION_2_3 = new Migration(2, 3) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE sessions ADD COLUMN pressureTrace TEXT");
        }
    };

    // v4 adds time stamps (event log, trace times), lost-packet count and the possible condition.
    static final Migration MIGRATION_3_4 = new Migration(3, 4) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE sessions ADD COLUMN pressureTraceTimes TEXT");
            db.execSQL("ALTER TABLE sessions ADD COLUMN eventLog TEXT");
            db.execSQL("ALTER TABLE sessions ADD COLUMN droppedPackets INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE sessions ADD COLUMN possibleCondition TEXT");
            db.execSQL("ALTER TABLE sessions ADD COLUMN medicineKey TEXT");
        }
    };

    public static AppDatabase getInstance(Context ctx) {
        if (INSTANCE == null) {
            synchronized (AppDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(
                                    ctx.getApplicationContext(),
                                    AppDatabase.class,
                                    "tanghap.db"
                    )
                    .addMigrations(MIGRATION_2_3, MIGRATION_3_4)
                    // Only used for versions without a migration path (e.g. old v1 dev builds)
                    .fallbackToDestructiveMigration()
                    .build();
                }
            }
        }
        return INSTANCE;
    }
}
