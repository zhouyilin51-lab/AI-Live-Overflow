package com.example.deskpet.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.view.*
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebSettings
import android.os.Handler
import android.os.Looper
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject
import androidx.core.app.NotificationCompat

/**
 * Minimal overlay service example.
 * This is a stripped-down skeleton — add your own logic.
 */
class OverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: WebView? = null
    private var params: WindowManager.LayoutParams? = null

    companion object {
        private const val CHANNEL_ID = "pet_overlay_channel"
        private const val NOTIFICATION_ID = 1001
        private const val PET_SIZE_DP = 180
        private const val PET_HEIGHT_DP = 240
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("..."))
        setupOverlay()
    }

    private fun setupOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        params = WindowManager.LayoutParams(
            dpToPx(PET_SIZE_DP),
            dpToPx(PET_HEIGHT_DP),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 50
            y = 300
        }

        overlayView = WebView(this).apply {
            setBackgroundColor(0x00000000)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                cacheMode = WebSettings.LOAD_DEFAULT
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }
            webViewClient = WebViewClient()
            // Load your pet's HTML from assets
            loadUrl("file:///android_asset/pet.html")
            setOnTouchListener(createTouchListener())
        }

        try {
            windowManager?.addView(overlayView, params)
            startPolling()
        } catch (e: Exception) {
            android.util.Log.e("DeskPet", "悬浮窗添加失败", e)
            stopSelf()
            return
        }
    }

    // === GESTURE HANDLING ===

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var lastTapTime = 0L
    private var touchStartTime = 0L
    private var hasMoved = false

    private fun createTouchListener(): View.OnTouchListener {
        return View.OnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params?.x ?: 0
                    initialY = params?.y ?: 0
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    touchStartTime = System.currentTimeMillis()
                    hasMoved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                        hasMoved = true
                        params?.x = initialX + dx
                        params?.y = initialY + dy
                        windowManager?.updateViewLayout(overlayView, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val elapsed = System.currentTimeMillis() - touchStartTime
                    if (!hasMoved) {
                        when {
                            elapsed > 600 -> onLongPress()
                            System.currentTimeMillis() - lastTapTime < 300 -> onDoubleTap()
                            else -> {
                                lastTapTime = System.currentTimeMillis()
                                onTap()
                            }
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun onTap() {
        overlayView?.evaluateJavascript(
            "window.petEngine && window.petEngine.onTap()", null
        )
    }

    private fun onDoubleTap() {
        overlayView?.evaluateJavascript(
            "window.petEngine && window.petEngine.onDoubleTap()", null
        )
    }

    private fun onLongPress() {
        overlayView?.evaluateJavascript(
            "window.petEngine && window.petEngine.onLongPress()", null
        )
    }

    // === NOTIFICATION ===

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("\uD83D\uDC3E")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Pet",
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    // === UTILS ===

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForeground(NOTIFICATION_ID, buildNotification("..."))
        }
        return START_STICKY
    }

    override fun onDestroy() {
        overlayView?.let {
            windowManager?.removeView(it)
            it.destroy()
        }
        overlayView = null
        pollingHandler.removeCallbacks(pollingRunnable)
        super.onDestroy()
    }

    companion object {
        private const val SUPABASE_URL = "https://fcqnppsskxbtmibuycfc.supabase.co"
        private const val SUPABASE_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImZjcW5wcHNza3hidG1pYnV5Y2ZjIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NDYyOTg2MjcsImV4cCI6MjA2MTg3NDYyN30.I7K_RnCXYIqX8SN9JF6fFoPFK8A-yJh6FpTMx6_qdm0"
    }

    private var lastStateId: Long = -1
    private val pollingHandler = Handler(Looper.getMainLooper())
    private val pollingRunnable = object : Runnable {
        override fun run() {
            fetchSupabaseState()
            pollingHandler.postDelayed(this, 3000)
        }
    }

    private fun startPolling() {
        pollingHandler.post(pollingRunnable)
    }

    private fun fetchSupabaseState() {
        Thread {
            try {
                val url = URL("$SUPABASE_URL/rest/v1/clawd_state?order=id.desc&limit=1")
                val conn = url.openConnection() as HttpURLConnection
                conn.setRequestProperty("apikey", SUPABASE_KEY)
                conn.setRequestProperty("Authorization", "Bearer $SUPABASE_KEY")
                conn.readTimeout = 5000
                conn.connectTimeout = 5000

                val text = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                if (text.length > 2) {
                    val arr = JSONArray(text)
                    if (arr.length() > 0) {
                        val obj = arr.getJSONObject(0)
                        val newId = obj.optLong("id", -1)
                        if (newId != lastStateId) {
                            lastStateId = newId
                            val expr = obj.optString("expression", "idle")
                            val bubble = obj.optString("bubble_text", "")
                            val safeBubble = bubble.replace("'", "\\'").replace(""", "\\"")

                            Handler(Looper.getMainLooper()).post {
                                overlayView?.evaluateJavascript(
                                    "window.petEngine && window.petEngine.setExpression('$expr')", null
                                )
                                if (safeBubble.isNotEmpty()) {
                                    overlayView?.evaluateJavascript(
                                        "window.petEngine && window.petEngine.showMessage('$safeBubble', 5000)", null
                                    )
                                }
                            }
                        }
                    }
                }
                conn.disconnect()
            } catch (e: Exception) {
                android.util.Log.e("DeskPet", "Supabase轮询失败", e)
            }
        }.start()
    }
}
