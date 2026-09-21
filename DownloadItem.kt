package com.example.downloader

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "downloads")
data class DownloadItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val fileName: String,
    val filePath: String,
    var status: String = STATUS_PENDING,   // PENDING, RUNNING, PAUSED, DONE, FAILED
    var progress: Int = 0,                 // 0-100
    var totalBytes: Long = -1,
    var downloadedBytes: Long = 0,
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val STATUS_PENDING = "PENDING"
        const val STATUS_RUNNING = "RUNNING"
        const val STATUS_PAUSED = "PAUSED"
        const val STATUS_DONE = "DONE"
        const val STATUS_FAILED = "FAILED"
    }
}
