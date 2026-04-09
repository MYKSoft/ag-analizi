package com.myksoft.aganalizi

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.myksoft.aganalizi.databinding.ActivityLogsBinding
import java.io.File
import java.io.FileOutputStream

class LogsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Full screen mode and edge-to-edge
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val windowInsetsController = WindowInsetsControllerCompat(window, window.decorView)
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
        
        binding = ActivityLogsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.txtLogs.text = MainActivity.logBuffer.toString()

        binding.btnDownloadLogs.setOnClickListener {
            shareLogs()
        }
    }

    private fun shareLogs() {
        try {
            val logs = MainActivity.logBuffer.toString()
            val file = File(cacheDir, "network_logs.txt")
            FileOutputStream(file).use {
                it.write(logs.toByteArray())
            }

            val uri: Uri = FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                file
            )

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.share_logs)))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
