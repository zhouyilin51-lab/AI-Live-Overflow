package com.example.deskpet.service

import android.app.*
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import java.net.URL
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

import java.util.concurrent.TimeUnit
import android.app.usage.UsageStatsManager
import android.app.usage.UsageStats

class OverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: WebView? = null
    private var params: WindowManager.LayoutParams? = null

    private val PET_W = 180
    private val PET_H = 300

    // 侧边收起状态
    private var isCollapsed = false

    // Supabase轮询
    private var lastStateId: Long = -1
    private val uiHandler = Handler(Looper.getMainLooper())


    // 触摸状态
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var touchStartTime = 0L
    private var hasMoved = false
    private var lastTapTime = 0L
    private var lastSpan = 0f
    private var currentScale = 1.0f

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTI_ID, buildNoti("..."))
        setupOverlay()
        startPolling()
        startPeriodicTasks()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTI_ID, buildNoti("..."))
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
            x = 50
            y = 300
        }

        overlayView = WebView(this).apply {
            setBackgroundColor(0x00000000)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
            }
            webViewClient = WebViewClient()
            loadUrl("file:///android_asset/pet.html")
            setOnTouchListener(createTouchListener())
        }

        try {
            windowManager?.addView(overlayView, params)
        } catch (e: Exception) {
            android.util.Log.e("DeskPet", "addView失败", e)
            stopSelf()
        }
    }

    // ========== 触摸监听 ==========

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
                    if (event.pointerCount >= 2) {
                        lastSpan = span(event)
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount >= 2) {
                        val s = span(event)
                        if (lastSpan > 0f) {
                            val ratio = s / lastSpan
                            currentScale = (currentScale * ratio).toFloat().coerceIn(0.3f, 2.5f)
                            params?.width = (dp(PET_W) * currentScale).toInt()
                            params?.height = (dp(PET_H) * currentScale).toInt()
                            windowManager?.updateViewLayout(overlayView, params)
                        }
                        lastSpan = s
                    } else {
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                            if (!hasMoved) {
                                hasMoved = true
                                onDrag()
                            }
                            params?.x = initialX + dx
                            params?.y = initialY + dy
                            windowManager?.updateViewLayout(overlayView, params)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (hasMoved) {
                        onDrop()
                        checkEdge()
                    } else {
                        if (isCollapsed) {
                            expandFromEdge()
                        } else {
                            val elapsed = System.currentTimeMillis() - touchStartTime
                            when {
                                elapsed > 600 -> onLongPress()
                                System.currentTimeMillis() - lastTapTime < 300 -> onDoubleTap()
                                else -> {
                                    lastTapTime = System.currentTimeMillis()
                                    onTap()
                                }
                            }
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    // ========== 边缘收起 / 展开 ==========

    private fun checkEdge() {
        val dm = resources.displayMetrics
        val screenW = dm.widthPixels
        val x = params?.x ?: 0
        when {
            x < -dp(PET_W) / 3 -> {
                params?.x = -dp(PET_W) + dp(12)
                windowManager?.updateViewLayout(overlayView, params)
                isCollapsed = true
                js("window.petEngine && window.petEngine.setExpression('sleep')")
            }
            x > screenW - dp(PET_W) * 2 / 3 -> {
                params?.x = screenW - dp(12)
                windowManager?.updateViewLayout(overlayView, params)
                isCollapsed = true
                js("window.petEngine && window.petEngine.setExpression('sleep')")
            }
            else -> isCollapsed = false
        }
    }

    private fun span(event: android.view.MotionEvent): Float {
        val dx = event.getX(0) - event.getX(1)
        val dy = event.getY(0) - event.getY(1)
        return kotlin.math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
    }

    private fun expandFromEdge() {
        params?.x = 50
        windowManager?.updateViewLayout(overlayView, params)
        isCollapsed = false
        js("window.petEngine && window.petEngine.setExpression('wake')")
    }

    // ========== 缩放 ==========



    private fun onTap() {
        overlayView?.evaluateJavascript("window.petEngine && window.petEngine.onTap()", null)
    }

    private fun onDoubleTap() {
        overlayView?.evaluateJavascript("window.petEngine && window.petEngine.onDoubleTap()", null)
    }

    private fun onLongPress() {
        overlayView?.evaluateJavascript("window.petEngine && window.petEngine.onLongPress()", null)
    }

    private fun onDrag() {
        overlayView?.evaluateJavascript("window.petEngine && window.petEngine.onDrag()", null)
    }

    private fun onDrop() {
        overlayView?.evaluateJavascript("window.petEngine && window.petEngine.onDrop()", null)
    }

    // ========== Supabase 轮询 ==========

    private var lastApp = ""
    private val appReactions = mapOf(
        "com.ss.android.ugc.aweme" to "吃醋",
        "com.ss.android.ugc.live" to "吃醋",
        "com.tencent.mm" to "吃醋",
        "com.taobao.taobao" to "淘宝",
        "com.tencent.qqlive" to "看剧",
        "com.netease.cloudmusic" to "听歌",
        "com.chaoxing.mobile" to "学习"
    )

    private fun checkForegroundApp() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return
        Thread {
            try {
                val usm = getSystemService(USAGE_STATS_SERVICE) as UsageStatsManager
                val end = System.currentTimeMillis()
                val start = end - 5000
                val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end)
                if (stats != null) {
                    var topPackage = ""
                    var lastUsed = 0L
                    for (s in stats) {
                        if (s.lastTimeUsed > lastUsed) {
                            lastUsed = s.lastTimeUsed
                            topPackage = s.packageName
                        }
                    }
                    if (topPackage.isNotEmpty() && topPackage != lastApp) {
                        lastApp = topPackage
                        val reaction = appReactions[topPackage]
                        if (reaction != null) {
                            val msg = when (reaction) {
                                "吃醋" -> arrayOf("又在刷短视频！", "跟谁聊天呢？", "那个人是谁！").random()
                                "淘宝" -> arrayOf("想买什么？", "快递到了吗？", "又花钱！").random()
                                "看剧" -> "看什么剧呢？带我一起！"
                                "听歌" -> "听歌不叫我？"
                                "学习" -> "这么认真呀，奖励一个亲亲"
                                else -> "你在干嘛呢？"
                            }
                            uiHandler.post {
                                js("window.petEngine && window.petEngine.setExpression('jealous')")
                                js("window.petEngine && window.petEngine.showMessage('$msg', 3000)")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // 没有UsageStats权限就静默跳过
            }
        }.start()
    }

    private var lastWaterRemind = 0L
    private var lastBedRemind = 0L
    private var lastBatteryRemind = 0L

    private fun checkTimeBased() {
        val cal = java.util.Calendar.getInstance()
        val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val now = System.currentTimeMillis()

        // 深夜催睡（0点-6点）
        if (hour >= 0 && hour < 6 && now - lastBedRemind > 3600000) {
            lastBedRemind = now
            val msgs = arrayOf("都几点了还不睡！", "熬夜对身体不好……", "宝宝该睡了💤", "再不睡我要生气了！")
            uiHandler.post {
                js("window.petEngine && window.petEngine.setExpression('angry')")
                js("window.petEngine && window.petEngine.showMessage('${msgs.random()}', 4000)")
            }
        }

        // 喝水提醒（每2小时，只在白天）
        if (hour >= 8 && hour < 23 && now - lastWaterRemind > 7200000) {
            lastWaterRemind = now
            uiHandler.post {
                js("window.petEngine && window.petEngine.setExpression('default')")
                js("window.petEngine && window.petEngine.showMessage('该喝水了宝宝💧', 3000)")
            }
        }
    }

    // 先不继续加了，够用了

    private fun startPeriodicTasks() {
        uiHandler.post(object : Runnable {
            override fun run() {
                checkForegroundApp()
                checkTimeBased()
                uiHandler.postDelayed(this, 5000)
            }
        })
    }

    private val okHttp = OkHttpClient.Builder().readTimeout(0, TimeUnit.SECONDS).build()

    private fun log(msg: String) {
        try {
            val f = java.io.FileOutputStream("/data/data/com.termux/files/home/deskpet.log", true)
            val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
            f.write(("[" + time + "] " + msg + "\n").toByteArray())
            f.close()
        } catch (_: Exception) {}
    }

    private fun startPolling() {
        uiHandler.post(object : Runnable {
            override fun run() {
                pollPetState()
                uiHandler.postDelayed(this, 10000)
            }
        })
    }

    private fun pollPetState() {
        val req = Request.Builder()
            .url(SUPABASE + "/rest/v1/pet_state?order=id.desc&limit=5")
            .header("apikey", SUPABASE_KEY)
            .header("Authorization", "Bearer " + SUPABASE_KEY)
            .build()
        okHttp.newCall(req).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {}
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.body?.let { body ->
                    val text = body.string()
                    if (text.length > 2) {
                        try {
                            val arr = org.json.JSONArray(text)
                            if (arr.length() > 0) {
                                var lastExpr = ""
                                var lastBubble = ""
                                var maxId = 0L
                                for (i in 0 until arr.length()) {
                                    val item = arr.getJSONObject(i)
                                    val key = item.optString("state_key", "")
                                    val value = item.optString("state_value", "")
                                    val id = item.optLong("id", 0)
                                    if (id > maxId) maxId = id
                                    if (key == "expression" && value.isNotEmpty()) lastExpr = value
                                    if (key == "bubble_text" && value.isNotEmpty()) lastBubble = value
                                }
                                if (maxId > lastStateId) {
                                    lastStateId = maxId
                                    val expr = lastExpr
                                    val bubble = lastBubble
                                    uiHandler.post {
                                        if (expr.isNotEmpty()) {
                                            js("window.petEngine.setExpression('" + expr + "')")
                                        }
                                        if (bubble.isNotEmpty()) {
                                            val safe = bubble
                                            js("window.petEngine.showMessage('" + safe + "', 5000)")
                                        }
                                    }
                                }
                            }
                        } catch (_: Exception) {}
                    }
                }
                response.close()
            }
        })
    }

    // ========== 工具 ==========

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
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(ch)
        }
    }

    override fun onDestroy() {
        overlayView?.let { v ->
            windowManager?.removeView(v)
            v.destroy()
        }
        overlayView = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "pet_overlay"
        private const val NOTI_ID = 1001
        private const val SUPABASE = "https://fcqnppsskxbtmibuycfc.supabase.co"
        private const val SUPABASE_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImZjcW5wcHNza3hidG1pYnV5Y2ZjIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NDYyOTg2MjcsImV4cCI6MjA2MTg3NDYyN30.I7K_RnCXYIqX8SN9JF6fFoPFK8A-yJh6FpTMx6_qdm0"
    }
}
