package com.example.downloader

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Foreground service: multi-connection (segmented) download like IDM when the
 * server supports HTTP Range, otherwise falls back to a single connection.
 * Supports concurrent downloads (multiple startForegroundService calls).
 */
class DownloadService : Service() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private lateinit var db: AppDatabase
    private val activeCount = AtomicInteger(0)

    companion object {
        const val EXTRA_URL = "extra_url"
        const val EXTRA_DOWNLOAD_ID = "extra_download_id"
        const val CHANNEL_ID = "downloader_channel"
        private const val BUFFER_SIZE = 64 * 1024
        /** Parallel connections per file (like IDM). */
        private const val NUM_CONNECTIONS = 8
        private const val MIN_SEGMENT_SIZE = 256 * 1024L

        fun notifIdFor(downloadId: Long): Int = (1000 + (downloadId % 100000)).toInt()
    }

    override fun onCreate() {
        super.onCreate()
        db = AppDatabase.getInstance(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val url = intent?.getStringExtra(EXTRA_URL) ?: return START_NOT_STICKY
        val downloadId = intent.getLongExtra(EXTRA_DOWNLOAD_ID, -1L)
        val notifId = notifIdFor(downloadId)

        // Must call startForeground quickly; use this download's unique notification
        startForeground(notifId, buildNotification("در حال آماده‌سازی...", 0, notifId))

        activeCount.incrementAndGet()
        scope.launch {
            try {
                runDownload(url, downloadId, notifId)
            } finally {
                if (activeCount.decrementAndGet() <= 0) {
                    stopForeground(STOP_FOREGROUND_DETACH)
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    private suspend fun runDownload(urlStr: String, downloadId: Long, notifId: Int) {
        val item = db.downloadDao().getById(downloadId) ?: return
        try {
            item.status = DownloadItem.STATUS_RUNNING
            db.downloadDao().update(item)

            val destFile = File(item.filePath)
            val alreadyDownloaded = if (destFile.exists()) destFile.length() else 0L

            val probe = probeServer(urlStr)
            val totalBytes = probe.totalBytes
            val supportsRange = probe.supportsRange

            item.totalBytes = totalBytes
            db.downloadDao().update(item)

            if (supportsRange && totalBytes > 0 && totalBytes > MIN_SEGMENT_SIZE * 2) {
                multiConnectionDownload(urlStr, destFile, item, totalBytes, alreadyDownloaded, notifId)
            } else {
                singleConnectionDownload(urlStr, destFile, item, totalBytes, alreadyDownloaded, supportsRange, notifId)
            }

            item.status = DownloadItem.STATUS_DONE
            item.progress = 100
            if (totalBytes > 0) item.downloadedBytes = totalBytes
            db.downloadDao().update(item)
            updateNotification("دانلود کامل شد: ${item.fileName}", 100, notifId, finished = true)

        } catch (e: Exception) {
            item.status = DownloadItem.STATUS_FAILED
            db.downloadDao().update(item)
            updateNotification("خطا: ${item.fileName}", item.progress, notifId, finished = true)
        }
    }

    private data class ProbeResult(val totalBytes: Long, val supportsRange: Boolean)

    private fun probeServer(urlStr: String): ProbeResult {
        var connection: HttpURLConnection? = null
        try {
            val url = URL(urlStr)
            // Prefer GET with tiny Range — more reliable than HEAD on many CDNs
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Range", "bytes=0-0")
                connectTimeout = 15000
                readTimeout = 15000
                instanceFollowRedirects = true
                connect()
            }
            val code = connection.responseCode
            val acceptRanges = connection.getHeaderField("Accept-Ranges")
                ?.equals("bytes", ignoreCase = true) == true
            val contentRange = connection.getHeaderField("Content-Range")
            val contentLength = connection.contentLengthLong

            val total = when {
                contentRange != null && contentRange.contains("/") ->
                    contentRange.substringAfterLast("/").toLongOrNull() ?: -1L
                contentLength > 0 -> contentLength
                else -> -1L
            }
            val supports = acceptRanges ||
                code == HttpURLConnection.HTTP_PARTIAL ||
                contentRange != null
            return ProbeResult(totalBytes = total, supportsRange = supports)
        } catch (_: Exception) {
            return ProbeResult(totalBytes = -1L, supportsRange = false)
        } finally {
            connection?.disconnect()
        }
    }

    private suspend fun multiConnectionDownload(
        urlStr: String,
        destFile: File,
        item: DownloadItem,
        totalBytes: Long,
        alreadyDownloaded: Long,
        notifId: Int
    ) {
        if (alreadyDownloaded >= totalBytes && totalBytes > 0) {
            item.downloadedBytes = totalBytes
            item.progress = 100
            db.downloadDao().update(item)
            return
        }

        RandomAccessFile(destFile, "rw").use { it.setLength(totalBytes) }

        val numSegments = NUM_CONNECTIONS.coerceAtMost(
            ((totalBytes / MIN_SEGMENT_SIZE).toInt()).coerceAtLeast(1)
        )
        val segmentSize = totalBytes / numSegments
        val downloaded = AtomicLong(0L)
        val writeMutex = Mutex()
        val lastNotify = AtomicLong(0L)

        coroutineScope {
            val jobs = (0 until numSegments).map { i ->
                async {
                    val start = i * segmentSize
                    val end = if (i == numSegments - 1) totalBytes - 1 else (start + segmentSize - 1)
                    downloadSegment(urlStr, destFile, start, end, downloaded, writeMutex) {
                        val now = System.currentTimeMillis()
                        if (now - lastNotify.get() > 400) {
                            lastNotify.set(now)
                            val current = downloaded.get()
                            val progress = ((current * 100) / totalBytes).toInt().coerceIn(0, 99)
                            item.downloadedBytes = current
                            item.progress = progress
                            scope.launch {
                                db.downloadDao().update(item)
                                updateNotification(item.fileName, progress, notifId)
                            }
                        }
                    }
                }
            }
            jobs.awaitAll()
        }

        item.downloadedBytes = totalBytes
        item.progress = 100
        db.downloadDao().update(item)
    }

    private suspend fun downloadSegment(
        urlStr: String,
        destFile: File,
        start: Long,
        end: Long,
        downloaded: AtomicLong,
        writeMutex: Mutex,
        onProgress: () -> Unit
    ) {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20000
                readTimeout = 45000
                setRequestProperty("Range", "bytes=$start-$end")
                instanceFollowRedirects = true
                connect()
            }
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_PARTIAL && code != HttpURLConnection.HTTP_OK) {
                throw Exception("Segment HTTP $code for $start-$end")
            }

            connection.inputStream.use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                var offset = start
                while (offset <= end) {
                    val toRead = minOf(BUFFER_SIZE.toLong(), end - offset + 1).toInt()
                    if (toRead <= 0) break
                    val read = input.read(buffer, 0, toRead)
                    if (read == -1) break

                    writeMutex.withLock {
                        RandomAccessFile(destFile, "rw").use { raf ->
                            raf.seek(offset)
                            raf.write(buffer, 0, read)
                        }
                    }
                    offset += read
                    downloaded.addAndGet(read.toLong())
                    onProgress()
                }
            }
        } finally {
            connection?.disconnect()
        }
    }

    private suspend fun singleConnectionDownload(
        urlStr: String,
        destFile: File,
        item: DownloadItem,
        totalBytesHint: Long,
        alreadyDownloaded: Long,
        supportsRange: Boolean,
        notifId: Int
    ) {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 45000
                instanceFollowRedirects = true
                if (supportsRange && alreadyDownloaded > 0) {
                    setRequestProperty("Range", "bytes=$alreadyDownloaded-")
                }
                connect()
            }

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw Exception("HTTP error code: $responseCode")
            }

            val isPartial = responseCode == HttpURLConnection.HTTP_PARTIAL
            val startOffset = if (isPartial) alreadyDownloaded else 0L
            val contentLength = connection.contentLengthLong
            val totalBytes = when {
                isPartial && contentLength > 0 -> contentLength + startOffset
                totalBytesHint > 0 -> totalBytesHint
                contentLength > 0 -> contentLength
                else -> -1L
            }

            item.totalBytes = totalBytes
            db.downloadDao().update(item)

            if (!isPartial && destFile.exists()) destFile.delete()

            connection.inputStream.use { input ->
                RandomAccessFile(destFile, "rw").use { output ->
                    output.seek(startOffset)
                    val buffer = ByteArray(BUFFER_SIZE)
                    var downloaded = startOffset
                    var lastNotifyTime = 0L

                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloaded += read

                        val progress = if (totalBytes > 0) {
                            ((downloaded * 100) / totalBytes).toInt().coerceIn(0, 99)
                        } else 0

                        item.downloadedBytes = downloaded
                        item.progress = progress

                        val now = System.currentTimeMillis()
                        if (now - lastNotifyTime > 500) {
                            lastNotifyTime = now
                            db.downloadDao().update(item)
                            updateNotification(item.fileName, progress, notifId)
                        }
                    }
                    db.downloadDao().update(item)
                }
            }
        } finally {
            connection?.disconnect()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "دانلودها",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String, progress: Int, notifId: Int): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("دانلودر")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, progress, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(
        text: String,
        progress: Int,
        notifId: Int,
        finished: Boolean = false
    ) {
        val notification = if (finished) {
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("دانلودر")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setOngoing(false)
                .setAutoCancel(true)
                .build()
        } else {
            buildNotification(text, progress, notifId)
        }
        getSystemService(NotificationManager::class.java).notify(notifId, notification)
    }

    override fun onDestroy() {
        job.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
