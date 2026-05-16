package dev.medveed.safeshare.db;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "transfers")
public class TransferEntity {

    public static final int DIRECTION_SEND = 0;
    public static final int DIRECTION_RECEIVE = 1;

    public static final int STATUS_IN_PROGRESS = 0;
    public static final int STATUS_DONE = 1;
    public static final int STATUS_FAILED = 2;
    public static final int STATUS_REVOKED = 3;

    @PrimaryKey(autoGenerate = true)
    public long id;

    public int direction;

    @NonNull
    public String fileId = "";

    @NonNull
    public String filename = "";

    public long sizeBytes;
    public long createdAt;
    public long expiresAt;

    public int status;

    @Nullable
    public String error;

    @Nullable
    public String ownerTokenHex;
}
