package com.example.downloader

import androidx.lifecycle.LiveData
import androidx.room.*

@Dao
interface DownloadDao {

    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun getAll(): LiveData<List<DownloadItem>>

    @Insert
    suspend fun insert(item: DownloadItem): Long

    @Update
    suspend fun update(item: DownloadItem)

    @Delete
    suspend fun delete(item: DownloadItem)

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun getById(id: Long): DownloadItem?
}
