package com.example.deskpet

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.deskpet.service.OverlayService

class MainActivity : AppCompatActivity() {

    private lateinit var btnStart: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(com.example.deskpet.R.layout.activity_main)

        btnStart = findViewById(com.example.deskpet.R.id.btn_start)

        btnStart.setOnClickListener {
            checkAndStart()
        }
    }

    private fun checkAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !Settings.canDrawOverlays(this)
        ) {
            AlertDialog.Builder(this)
                .setTitle("需要悬浮窗权限")
                .setMessage("桌宠需要悬浮窗权限才能显示在屏幕上。")
                .setPositiveButton("去开启") { _, _ ->
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }

        startForegroundService(Intent(this, OverlayService::class.java))
        Toast.makeText(this, "桌宠已启动 🐾", Toast.LENGTH_SHORT).show()
        btnStart.text = "桌宠运行中 ❤️"
        btnStart.isEnabled = false
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            Settings.canDrawOverlays(this)
        ) {
            btnStart.isEnabled = true
        }
    }
}
