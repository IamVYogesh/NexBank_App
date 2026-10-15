package com.nexbank.app.core.database

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "payments")
data class PaymentEntity(@PrimaryKey val id: String, val payee: String, val amountMinor: Long, val currency: String, val status: String, val createdAt: Long, val expectedBy: Long?)

@Entity(tableName = "cards")
data class CardEntity(@PrimaryKey val id: String, val last4: String, val network: String, val status: String)

@Entity(tableName = "statements")
data class StatementEntity(@PrimaryKey val id: String, val year: Int, val month: Int, val sizeBytes: Long, val downloadedAt: Long?)

/** Every PII column is stored encrypted (suffix `Enc`). Plain text never touches disk. */
@Entity(tableName = "contact_details")
data class ContactEntity(@PrimaryKey val id: Int = 1, val emailEnc: String, val phoneEnc: String, val addressEnc: String, val postcodeEnc: String, val updatedAt: Long)

@Dao
abstract class PaymentDao {
    @Query("SELECT * FROM payments ORDER BY createdAt DESC") abstract fun observeAll(): Flow<List<PaymentEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun upsertAll(items: List<PaymentEntity>)
    @Query("DELETE FROM payments") abstract suspend fun clear()
    @Transaction open suspend fun replaceAll(items: List<PaymentEntity>) { clear(); upsertAll(items) }
}

@Dao
abstract class CardDao {
    @Query("SELECT * FROM cards ORDER BY last4") abstract fun observeAll(): Flow<List<CardEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun upsertAll(items: List<CardEntity>)
    @Query("UPDATE cards SET status = :status WHERE id = :id") abstract suspend fun updateStatus(id: String, status: String)
    @Query("DELETE FROM cards") abstract suspend fun clear()
    @Transaction open suspend fun replaceAll(items: List<CardEntity>) { clear(); upsertAll(items) }
}

@Dao
abstract class StatementDao {
    @Query("SELECT * FROM statements ORDER BY year DESC, month DESC") abstract fun observeAll(): Flow<List<StatementEntity>>
    @Query("SELECT * FROM statements WHERE id = :id") abstract suspend fun find(id: String): StatementEntity?
    /** IGNORE keeps `downloadedAt` of rows we already have when the list is refreshed. */
    @Insert(onConflict = OnConflictStrategy.IGNORE) abstract suspend fun insertIgnore(items: List<StatementEntity>)
    @Query("UPDATE statements SET downloadedAt = :ts WHERE id = :id") abstract suspend fun markDownloaded(id: String, ts: Long)
    @Query("SELECT * FROM statements WHERE year * 12 + month < :cutoff") abstract suspend fun olderThan(cutoff: Int): List<StatementEntity>
    @Query("DELETE FROM statements WHERE id IN (:ids)") abstract suspend fun deleteAll(ids: List<String>)
}

@Dao
abstract class ContactDao {
    @Query("SELECT * FROM contact_details WHERE id = 1") abstract fun observe(): Flow<ContactEntity?>
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun upsert(entity: ContactEntity)
    @Query("DELETE FROM contact_details") abstract suspend fun clear()
}

@Database(
    entities = [PaymentEntity::class, CardEntity::class, StatementEntity::class, ContactEntity::class],
    version = 1,
    exportSchema = true, // schemas/ is committed to git so migrations can be reviewed & tested
)
abstract class NexDatabase : RoomDatabase() {
    abstract fun paymentDao(): PaymentDao
    abstract fun cardDao(): CardDao
    abstract fun statementDao(): StatementDao
    abstract fun contactDao(): ContactDao
}
