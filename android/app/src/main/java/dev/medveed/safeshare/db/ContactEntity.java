package dev.medveed.safeshare.db;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(tableName = "contacts", indices = {@Index(value = {"pub_key"}, unique = true)})
public class ContactEntity {
    @PrimaryKey(autoGenerate = true)
    public long id;

    @ColumnInfo(name = "name")
    public String name;

    @ColumnInfo(name = "pub_key")
    public byte[] pubKey;

    @ColumnInfo(name = "added_at")
    public long addedAt;
}
