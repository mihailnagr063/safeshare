package dev.medveed.safeshare.db;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

@Database(entities = {TransferEntity.class, ContactEntity.class}, version = 4, exportSchema = false)
public abstract class AppDatabase extends RoomDatabase {

    private static volatile AppDatabase instance;

    public abstract TransferDao transferDao();
    public abstract ContactDao contactDao();

    public static AppDatabase get(Context context) {
        AppDatabase local = instance;
        if (local != null) return local;
        synchronized (AppDatabase.class) {
            if (instance == null) {
                instance = Room.databaseBuilder(
                                context.getApplicationContext(),
                                AppDatabase.class,
                                "safeshare.db")
                        .fallbackToDestructiveMigration()
                        .build();
            }
            return instance;
        }
    }
}
