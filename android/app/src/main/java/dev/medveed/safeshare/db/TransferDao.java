package dev.medveed.safeshare.db;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Dao
public interface TransferDao {

    @Insert
    long insert(TransferEntity entity);

    @Update
    void update(TransferEntity entity);

    @Query("SELECT * FROM transfers WHERE id = :id LIMIT 1")
    TransferEntity byId(long id);

    @Query("SELECT * FROM transfers ORDER BY createdAt DESC")
    LiveData<List<TransferEntity>> observeAll();

    @Query("DELETE FROM transfers WHERE id = :id")
    void delete(long id);

    @Query("UPDATE transfers SET status = :status, error = :error WHERE id = :id")
    void setStatus(long id, int status, String error);

    @Query("UPDATE transfers SET fileId = :fileId, expiresAt = :expiresAt, "
            + "ownerTokenHex = :ownerTokenHex, status = :status "
            + "WHERE id = :id")
    void markSendDone(long id, String fileId, long expiresAt,
                      String ownerTokenHex, int status);
}
