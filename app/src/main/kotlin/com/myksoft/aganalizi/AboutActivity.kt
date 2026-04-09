package com.myksoft.aganalizi

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.myksoft.aganalizi.databinding.ActivityAboutBinding
import java.io.File
import java.io.FileOutputStream

class AboutActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAboutBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Full screen mode and edge-to-edge
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val windowInsetsController = WindowInsetsControllerCompat(window, window.decorView)
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
        
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.txtVersion.text = getString(R.string.version_label, "0.0.2")
        binding.txtDeveloper.text = getString(R.string.developer_label, "MYK Soft")

        binding.btnOpenLogs.setOnClickListener {
            val intent = Intent(this, LogsActivity::class.java)
            startActivity(intent)
        }

        binding.btnSendFeedback.setOnClickListener {
            sendFeedback()
        }

        binding.btnPrivacyPolicy.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Myasarkar/ag-analizi/blob/main/PRIVACY_POLICY.md"))
            startActivity(intent)
        }
    }

    private fun sendFeedback() {
        try {
            val logs = MainActivity.logBuffer.toString()
            val file = File(cacheDir, "network_logs_feedback.txt")
            FileOutputStream(file).use {
                it.write(logs.toByteArray())
            }

            val uri: Uri = FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                file
            )

            val emailIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_EMAIL, arrayOf("mustafa.yasar.kar@gmail.com"))
                putExtra(Intent.EXTRA_SUBJECT, "Ağ Analizi Uygulaması Geri Bildirim")
                putExtra(Intent.EXTRA_TEXT, "Merhaba,\n\nUygulama hakkındaki geri bildirimim aşağıdadır:\n\n[Buraya mesajınızı yazın]\n\n--- Sistem Logları Ektedir ---")
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            
            startActivity(Intent.createChooser(emailIntent, "Geri Bildirim Gönder"))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
