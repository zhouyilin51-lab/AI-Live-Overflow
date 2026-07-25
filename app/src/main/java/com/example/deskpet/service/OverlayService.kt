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
import androidx.core.app.NotificationCompat
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray

class OverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: WebView? = null
    private var params: WindowManager.LayoutParams? = null

    private val PET_W = 180
    private val PET_H = 240

    private var isCollapsed = false
    private var lastStateId: Long = -1
    private val uiHandler = Handler(Looper.getMainLooper())
    private val pollingRunnable = object : Runnable {
        override fun run() {
            fetchState()
            uiHandler.postDelayed(this, 3000)
        }
    }

    // === 手势状态 ===
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var touchStartTime = 0L
    private var hasMoved = false
    private var lastTapTime = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTI_ID, buildNoti("..."))
        setupOverlay()
        uiHandler.post(pollingRunnable)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForeground(NOTI_ID, buildNoti("..."))
        }
        return START_STICKY
    }

    // ========== 悬浮窗 ==========

    private fun setupOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        params = WindowManager.LayoutParams(
            dp(PET_W), dp(PET_H),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 50; y = 300
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
            loadUrl("file:///android_asset/pet.html")
            setOnTouchListener(touchListener())
        }

        try {
            windowManager?.addView(overlayView, params)
        } catch (e: Exception) {
            android.util.Log.e("DeskPet", "addView失败", e)
            stopSelf()
        }
    }

    // ========== 触摸 ==========

    private fun touchListener(): View.OnTouchListener {
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
                        if (!hasMoved) {
                            hasMoved = true
                            js("window.petEngine && window.petEngine.onDrag()")
                        }
                        params?.x = initialX + dx
                        params?.y = initialY + dy
                        windowManager?.updateViewLayout(overlayView, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (hasMoved) {
                        js("window.petEngine && window.petEngine.onDrop()")
                        checkEdgeCollapse()
                    } else {
                        val t = System.currentTimeMillis() - touchStartTime
                        when {
                            t > 600 -> js("window.petEngine && window.petEngine.onLongPress()")
                            System.currentTimeMillis() - lastTapTime < 300 ->
                                js("window.petEngine && window.petEngine.onDoubleTap()")
                            else -> {
                                lastTapTime = System.currentTimeMillis()
                                js("window.petEngine && window.petEngine.onTap()")
                            }
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    // 拖到边缘自动收起
    private fun checkEdgeCollapse() {
        val dm = resources.displayMetrics
        val screenW = dm.widthPixels
        val x = params?.x ?: 0
        if (x < -dp(PET_W) / 3) {
            params?.x = -dp(PET_W) + dp(12)
            windowManager?.updateViewLayout(overlayView, params)
            isCollapsed = true
            js("window.petEngine && window.petEngine.setExpression('sleep')")
        } else if (x > screenW - dp(PET_W) * 2 / 3) {
            params?.x = screenW - dp(12)
            windowManager?.updateViewLayout(overlayView, params)
            isCollapsed = true
            js("window.petEngine && window.petEngine.setExpression('sleep')")
        } else {
            isCollapsed = false
        }
    }

    // 双击恢复展开
    override fun onResume() {
        super.onResume()
        if (isCollapsed) {
            params?.x = 50
            windowManager?.updateViewLayout(overlayView, params)
            isCollapsed = false
            js("window.petEngine && window.petEngine.setExpression('wake')")
        }
    }

    // ========== 缩放手势 ==========

    private var lastScaleDist = 0f
    private var currentScale = 1.0f

    // 简单缩放：通过调整LayoutParams大小
    private fun setScale(scale: Float) {
        currentScale = scale.coerceIn(0.3f, 2.0f)
        val w = (dp(PET_W) * currentScale).toInt()
        val h = (dp(PET_H) * currentScale).toInt()
        params?.width = w
        params?.height = h
        windowManager?.updateViewLayout(overlayView, params)
    }

    // ========== Supabase 轮询 ==========

    private fun fetchState() {
        Thread {
            try {
                val url = URL("${SUPABASE}/rest/v1/clawd_state?order=id.desc&limit=1")
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

                            uiHandler.post {
                                js("window.petEngine && window.petEngine.setExpression('$expr')")
                                if (bubble.isNotEmpty()) {
                                    val safe = bubble
                                        .replace("'", "\\'")
                                        .replace("\"", "\\\"")
                                        .replace("\n", " ")
                                    js("window.petEngine && window.petEngine.showMessage('$safe', 5000)")
                                }
                            }
                        }
                    }
                }
                conn.disconnect()
            } catch (e: Exception) {
                android.util.Log.e("DeskPet", "Supabase轮询", e)
            }
        }.start()
    }

    // ========== 工具方法 ==========

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun js(code: String) {
        overlayView?.evaluateJavascript(code, null)
    }

    private fun buildNoti(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("\uD83E\uDD8A")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pi)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL, "Pet", NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    override fun onDestroy() {
        uiHandler.removeCallbacks(pollingRunnable)
        overlayView?.let {
            windowManager?.removeView(it)
            it.destroy()
        }
        overlayView = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "pet_overlay_channel"
        private const val NOTI_ID = 1001
        private const val SUPABASE = "https://fcqnppsskxbtmibuycfc.supabase.co"
        private const val SUPABASE_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImZjcW5wcHNza3hidG1pYnV5Y2ZjIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NDYyOTg2MjcsImV4cCI6MjA2MTg3NDYyN30.I7K_RnCXYIqX8SN9JF6fFoPFK8A-yJh6FpTMx6_qdm0"
    }
}
