package com.threadprotection.app.analyst.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CveDao {
    @Query("SELECT * FROM cve_watchlist ORDER BY addedAtMs DESC")
    fun observeAll(): Flow<List<WatchedCveEntity>>

    @Query("SELECT * FROM cve_watchlist")
    suspend fun getAll(): List<WatchedCveEntity>

    @Query("SELECT * FROM cve_watchlist WHERE cveId = :cveId")
    suspend fun get(cveId: String): WatchedCveEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM cve_watchlist WHERE cveId = :cveId)")
    fun observeIsWatched(cveId: String): Flow<Boolean>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: WatchedCveEntity)

    @Update
    suspend fun update(entity: WatchedCveEntity)

    @Query("DELETE FROM cve_watchlist WHERE cveId = :cveId")
    suspend fun remove(cveId: String)
}
