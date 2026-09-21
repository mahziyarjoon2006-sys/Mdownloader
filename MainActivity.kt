package com.example.downloader

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.downloader.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import java.io.File
import java.net.URL
import java.net.URLDecoder

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var db: AppDatabase
    private val adapter = DownloadAdapter()

    private var pendingUrls: List<String>? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            pendingUrls?.let { startDownloads(it) }
        } else {
            Toast.makeText(this, "برای دانلود فایل نیاز به دسترسی حافظه است", Toast.LENGTH_LONG).show()
        }
        pendingUrls = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        db = AppDatabase.getInstance(applicationContext)

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        db.downloadDao().getAll().observe(this) { list ->
            adapter.submitList(list)
            binding.emptyText.visibility =
                if (list.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        }

        binding.downloadButton.setOnClickListener {
            val raw = binding.urlInput.text.toString().trim()
            if (raw.isEmpty()) {
                Toast.makeText(this, "یک یا چند لینک وارد کن", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val urls = parseUrls(raw)
            if (urls.isEmpty()) {
                Toast.makeText(this, "لینک معتبری پیدا نشد", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            requestPermissionAndDownload(urls)
        }

        // Handle "Share" intent from browser / other apps
        handleShareIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!sharedText.isNullOrBlank()) {
                binding.urlInput.setText(sharedText.trim())
            }
        }
    }

    /**
     * Extracts all http(s) URLs from the input text.
     * Supports: one URL, multiple lines, or space/newline separated lists
     * (like the episode list you pasted).
     */
    private fun parseUrls(raw: String): List<String> {
        val urlRegex = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)
        return urlRegex.findAll(raw)
            .map { it.value.trimEnd('.', ',', ')', ']', '"', '\'') }
            .filter { it.startsWith("http://") || it.startsWith("https://") }
            .distinct()
            .toList()
    }

    private fun requestPermissionAndDownload(urls: List<String>) {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            val permission = Manifest.permission.WRITE_EXTERNAL_STORAGE
            if (ContextCompat.checkSelfPermission(this, permission)
                != PackageManager.PERMISSION_GRANTED
            ) {
                pendingUrls = urls
                permissionLauncher.launch(permission)
                return
            }
        }
        startDownloads(urls)
    }

    private fun startDownloads(urls: List<String>) {
        lifecycleScope.launch {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS
            )
            if (!downloadsDir.exists()) downloadsDir.mkdirs()

            var started = 0
            for (url in urls) {
                val fileName = guessFileName(url)
                val destFile = File(downloadsDir, fileName)

                // Skip if already fully present (optional convenience)
                val item = DownloadItem(
                    url = url,
                    fileName = fileName,
                    filePath = destFile.absolutePath
                )
                val id = db.downloadDao().insert(item)

                val serviceIntent = Intent(this@MainActivity, DownloadService::class.java).apply {
                    putExtra(DownloadService.EXTRA_URL, url)
                    putExtra(DownloadService.EXTRA_DOWNLOAD_ID, id)
                }
                ContextCompat.startForegroundService(this@MainActivity, serviceIntent)
                started++
            }

            binding.urlInput.text?.clear()
            val msg = if (started == 1) {
                "دانلود شروع شد"
            } else {
                "$started دانلود به صف اضافه شد"
            }
            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun guessFileName(url: String): String {
        return try {
            val path = URL(url).path
            val decoded = URLDecoder.decode(path, "UTF-8")
            val name = decoded.substringAfterLast('/').ifBlank { "file_${System.currentTimeMillis()}" }
            // Sanitize filename for Android filesystem
            name.replace(Regex("""[\\/:*?"<>|]"""), "_")
        } catch (e: Exception) {
            "file_${System.currentTimeMillis()}"
        }
    }
}
