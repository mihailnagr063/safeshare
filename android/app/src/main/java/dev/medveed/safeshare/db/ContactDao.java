package dev.medveed.safeshare.db;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Dao
public interface ContactDao {
    @Query("SELECT * FROM contacts ORDER BY name ASC")
    List<ContactEntity> getAll();

    @Query("SELECT * FROM contacts WHERE id = :id LIMIT 1")
    ContactEntity getById(long id);

    @Query("SELECT * FROM contacts WHERE pub_key = :pubKey LIMIT 1")
    ContactEntity getByPubKey(byte[] pubKey);

    @Query("SELECT COUNT(*) FROM contacts WHERE pub_key = :pubKey")
    int countByPubKey(byte[] pubKey);

    @Insert
    long insert(ContactEntity contact);

    @Update
    void update(ContactEntity contact);

    @Delete
    void delete(ContactEntity contact);
}
