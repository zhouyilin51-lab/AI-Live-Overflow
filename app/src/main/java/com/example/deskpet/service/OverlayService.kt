package com.example.deskpet.service

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.BatteryManager
import android.os.Build
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

class OverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: WebView? = null
    private var params: WindowManager.LayoutParams? = null

    private val PET_W = 180
    private val PET_H = 280 // 增高给气泡留空间

    // 界面状态
    private var isCollapsed = false
    private var isAwake = true

    // Supabase
    private var lastStateId: Long = -1
    private val uiHandler = Handler(Looper.getMainLooper())

    // 触摸
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var touchStartTime = 0L
    private var hasMoved = false
    private var lastTapTime = 0L
    private var lastSpan = 0f
    private var currentScale = 1.0f

    // ===== Heat 脸红系统 =====
    private var heat = 0
    private var heatDecayTimer: Runnable? = null

    // ===== 孤独系统 =====
    private var lastInteractionTime = System.currentTimeMillis()
    private var lonelyLevel = 0
    private var lonelyTimer: Runnable? = null

    // ===== APP快速切换检测 =====
    private val appSwitchHistory = java.util.LinkedList<Long>()
    private var lastForegroundApp = ""

    // ===== 截图检测 =====
    private var screenshotObserver: FileObserver? = null

    // ===== 电池状态 =====
    private var isCharging = false
    private var batteryPct = 100

    // ===== 定时行为 =====
    private var lastBehaviorTime = 0L

    // ===== 通知碎念 =====
    private var notiHour = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTI_ID, buildNoti("在想你"))
        setupOverlay()
        registerBatteryReceiver()
        registerScreenshotObserver()
        startPolling()
        startPeriodicTasks()
        startHeatDecay()
        startLonelyCheck()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTI_ID, buildNoti("在想你"))
        return START_STICKY
    }

    // ========== JS接口 (从HTML回调) ==========

    inner class PetJsInterface {
        @JavascriptInterface
        fun onUserInteraction() {
            lastInteractionTime = System.currentTimeMillis()
            lonelyLevel = 0
            if (!isAwake) {
                isAwake = true
                uiHandler.post {
                    js("window.petEngine && window.petEngine.doWake()")
                }
            }
        }
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

        overlayView = object : WebView(this) {
            override fun onOverScrolled(scrollX: Int, scrollY: Int, clampedX: Boolean, clampedY: Boolean) {
                super.onOverScrolled(scrollX, scrollY, clampedX, clampedY)
            }
        }.apply {
            setBackgroundColor(0x00000000)
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
            }
            webViewClient = WebViewClient()
            addJavascriptInterface(PetJsInterface(), "Android")
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
                    if (event.pointerCount >= 2) lastSpan = span(event)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount >= 2) {
                        val s = span(event)
                        if (lastSpan > 0f) {
                            val ratio = s / lastSpan
                            currentScale = (currentScale * ratio).coerceIn(0.3f, 2.5f)
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

    // ========== 手势响应 ==========

    private fun onTap() {
        recordInteraction("tap")
        overlayView?.evaluateJavascript("window.petEngine && window.petEngine.onTap()", null)
    }

    private fun onDoubleTap() {
        recordInteraction("double_tap")
        overlayView?.evaluateJavascript("window.petEngine && window.petEngine.onDoubleTap()", null)
    }

    private fun onLongPress() {
        recordInteraction("long_press")
        overlayView?.evaluateJavascript("window.petEngine && window.petEngine.onLongPress()", null)
    }

    private fun onDrag() {
        overlayView?.evaluateJavascript("window.petEngine && window.petEngine.onDrag()", null)
    }

    private fun onDrop() {
        overlayView?.evaluateJavascript("window.petEngine && window.petEngine.onDrop()", null)
    }

    private fun recordInteraction(type: String) {
        // 更新互动时间
        lastInteractionTime = System.currentTimeMillis()
        lonelyLevel = 0

        // Heat增加
        heat = (heat + 5).coerceAtMost(100)
        updateHeat()

        // 上报手势日志
        logGesture(type)
    }

    // ========== 边缘收起/展开 ==========

    private fun checkEdge() {
        val dm = resources.displayMetrics
        val screenW = dm.widthPixels
        val x = params?.x ?: 0
        when {
            x < -dp(PET_W) / 3 -> {
                params?.x = -dp(PET_W) + dp(12)
                windowManager?.updateViewLayout(overlayView, params)
                isCollapsed = true
                isAwake = false
                js("window.petEngine && window.petEngine.setExpression('sleep')")
            }
            x > screenW - dp(PET_W) * 2 / 3 -> {
                params?.x = screenW - dp(12)
                windowManager?.updateViewLayout(overlayView, params)
                isCollapsed = true
                isAwake = false
                js("window.petEngine && window.petEngine.setExpression('sleep')")
            }
            else -> isCollapsed = false
        }
    }

    private fun expandFromEdge() {
        params?.x = 50
        windowManager?.updateViewLayout(overlayView, params)
        isCollapsed = false
        isAwake = true
        lastInteractionTime = System.currentTimeMillis()
        js("window.petEngine && window.petEngine.doWake()")
    }

    private fun span(event: android.view.MotionEvent): Float {
        val dx = event.getX(0) - event.getX(1)
        val dy = event.getY(0) - event.getY(1)
        return kotlin.math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
    }

    // ========== APP检测 (已存在,优化版) ==========

    private var lastAppCheck = ""
    private val appReactions = mapOf(
        "com.ss.android.ugc.aweme" to "吃醋",
        "com.ss.android.ugc.live" to "吃醋",
        "com.tencent.mm" to "吃醋",
        "com.tencent.mobileqq" to "吃醋",
        "com.taobao.taobao" to "淘宝",
        "com.tencent.qqlive" to "看剧",
        "com.netease.cloudmusic" to "听歌",
        "com.chaoxing.mobile" to "学习",
        "com.zhihu.android" to "学习",
        "com.tencent.tim" to "吃醋"
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
                    if (topPackage.isNotEmpty() && topPackage != lastAppCheck) {
                        // 快速切换检测
                        if (topPackage != "com.example.deskpet" && lastForegroundApp != topPackage) {
                            appSwitchHistory.add(System.currentTimeMillis())
                            if (appSwitchHistory.size > 10) appSwitchHistory.removeFirst()
                            // 清理60秒前的记录
                            val cutoff = System.currentTimeMillis() - 60000
                            while (appSwitchHistory.isNotEmpty() && appSwitchHistory.first < cutoff) {
                                appSwitchHistory.removeFirst()
                            }
                            lastForegroundApp = topPackage

                            // 60秒内切了3个以上 → 杂耍模式
                            if (appSwitchHistory.size >= 3) {
                                uiHandler.post {
                                    js("window.petEngine && window.petEngine.setExpression('angry')")
                                    js("window.petEngine && window.petEngine.showMessage('切这么快！我都看花眼了', 3000)")
                                }
                                appSwitchHistory.clear()
                                return@Thread
                            }
                        }

                        lastAppCheck = topPackage
                        val reaction = appReactions[topPackage]
                        if (reaction != null) {
                            val (expr, msg) = when (reaction) {
                                "吃醋" -> Pair("jealous", arrayOf(
                                    "又在刷短视频！", "跟谁聊天呢？", "那个人是谁！",
                                    "你眼里只能有我", "我吃醋了", "看谁看得这么入迷"
                                ).random())
                                "淘宝" -> Pair("happy", arrayOf(
                                    "想买什么？", "快递到了吗？", "又花钱！", "给我也买一个"
                                ).random())
                                "看剧" -> Pair("happy", "看什么剧呢？带我一起！")
                                "听歌" -> Pair("love", "听歌不叫我？我也要听")
                                "学习" -> Pair("love", arrayOf(
                                    "这么认真呀", "奖励一个亲亲", "学完了来抱抱"
                                ).random())
                                else -> Pair("default", "你在干嘛呢？")
                            }
                            uiHandler.post {
                                if (reaction == "吃醋") heat = (heat + 15).coerceAtMost(100); updateHeat()
                                js("window.petEngine && window.petEngine.setExpression('$expr')")
                                js("window.petEngine && window.petEngine.showMessage('$msg', 3500)")
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }.start()
    }

    // ========== 截图检测 ==========

    private fun registerScreenshotObserver() {
        try {
            val path = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DCIM
            ).absolutePath + "/Screenshots"
            val dir = File(path)
            if (dir.exists()) {
                screenshotObserver = object : FileObserver(path, FileObserver.CREATE or FileObserver.CLOSE_WRITE) {
                    override fun onEvent(event: Int, path: String?) {
                        if (path != null && (path.endsWith(".png") || path.endsWith(".jpg"))) {
                            uiHandler.post {
                                val msgs = arrayOf("拍照了？我也要拍！", "截我干嘛", "被拍到了！", "看镜头📸")
                                js("window.petEngine && window.petEngine.setExpression('happy')")
                                js("window.petEngine && window.petEngine.showMessage('${msgs.random()}', 3000)")
                            }
                        }
                    }
                }
                screenshotObserver?.startWatching()
            }
        } catch (_: Exception) {}
    }

    // ========== 电池检测 ==========

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: return
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val pct = if (scale > 0) (level * 100 / scale) else 0
            batteryPct = pct

            val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
            val charging = plugged == BatteryManager.BATTERY_PLUGGED_AC ||
                    plugged == BatteryManager.BATTERY_PLUGGED_USB ||
                    plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS

            if (charging && !isCharging) {
                isCharging = true
                uiHandler.post {
                    js("window.petEngine && window.petEngine.setExpression('happy')")
                    js("window.petEngine && window.petEngine.showMessage('有电了！继续陪你', 3000)")
                }
            } else if (!charging && isCharging) {
                isCharging = false
            }

            if (!charging && pct <= 20) {
                uiHandler.post {
                    js("window.petEngine && window.petEngine.setExpression('sad')")
                    js("window.petEngine && window.petEngine.showMessage('快没电了……', 3000)")
                }
            }
        }
    }

    private fun registerBatteryReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        registerReceiver(batteryReceiver, filter)
    }

    // ========== Heat 脸红系统 ==========

    private fun updateHeat() {
        js("window.petEngine && window.petEngine.setHeat($heat)")
        // heat > 50 时偶尔触发害羞
        if (heat > 50 && Math.random() < 0.1) {
            js("window.petEngine && window.petEngine.setExpression('shy')")
        }
    }

    private fun startHeatDecay() {
        heatDecayTimer = object : Runnable {
            override fun run() {
                if (heat > 0) {
                    heat = (heat - 2).coerceAtLeast(0)
                    updateHeat()
                }
                uiHandler.postDelayed(this, 30000)
            }
        }
        uiHandler.post(heatDecayTimer!!)
    }

    // ========== 孤独递进系统 ==========

    private fun startLonelyCheck() {
        lonelyTimer = object : Runnable {
            override fun run() {
                if (isCollapsed) {
                    uiHandler.postDelayed(this, 10000)
                    return
                }
                val elapsed = System.currentTimeMillis() - lastInteractionTime
                val newLevel = when {
                    elapsed < 300000 -> 0  // 5分钟内
                    elapsed < 600000 -> 1  // 5-10分钟: 偷看
                    elapsed < 900000 -> 2  // 10-15分钟: 吹泡泡
                    elapsed < 1200000 -> 3 // 15-20分钟: 搬东西
                    elapsed < 1800000 -> 4 // 20-30分钟: 打瞌睡
                    else -> 5              // 30分钟+: 睡着
                }
                if (newLevel != lonelyLevel) {
                    lonelyLevel = newLevel
                    val lvl = lonelyLevel
                    uiHandler.post {
                        js("window.petEngine && window.petEngine.setLonely($lvl)")
                        if (lvl >= 5) {
                            isAwake = false
                            js("window.petEngine && window.petEngine.showMessage('睡着了……zzz', 3000)")
                        } else if (lvl >= 3) {
                            val msgs = arrayOf("好无聊……", "你还在吗", "理理我嘛")
                            js("window.petEngine && window.petEngine.showMessage('${msgs.random()}', 3000)")
                        }
                    }
                }
                uiHandler.postDelayed(this, 15000)
            }
        }
        uiHandler.post(lonelyTimer!!)
    }

    // ========== 20分钟定时行为 ==========

    private fun doRandomBehavior() {
        val now = System.currentTimeMillis()
        if (now - lastBehaviorTime < 1200000) return // 20分钟CD
        lastBehaviorTime = now

        val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val behaviors = mutableListOf<Pair<String, String>>()

        // 根据时段
        if (h in 0..5) {
            behaviors.add("sleep" to "好晚啦……")
            behaviors.add("sleep" to "zzz")
        } else if (h in 6..8) {
            behaviors.add("happy" to "早安！")
            behaviors.add("love" to "新的一天也要想你")
        } else if (h in 12..13) {
            behaviors.add("happy" to "吃饭了吗")
            behaviors.add("default" to "中午好")
        } else if (h >= 22) {
            behaviors.add("sleep" to "该睡了……")
            behaviors.add("sleep" to "晚安")
        } else {
            behaviors.add("love" to "在想你")
            behaviors.add("default" to "……")
            behaviors.add("happy" to "今天心情不错")
        }

        // 30%概率触发
        if (Math.random() < 0.3 && behaviors.isNotEmpty()) {
            val (expr, msg) = behaviors.random()
            uiHandler.post {
                js("window.petEngine && window.petEngine.setExpression('$expr')")
                js("window.petEngine && window.petEngine.showMessage('$msg', 3000)")
            }
        }
    }

    // ========== 手势日志上报 ==========

    private fun logGesture(type: String) {
        Thread {
            try {
                val json = JSONObject().apply {
                    put("gesture_type", type)
                    put("timestamp", System.currentTimeMillis())
                    put("heat", heat)
                    put("lonely_level", lonelyLevel)
                }
                val body = json.toString().toRequestBody("application/json".toMediaType())
                val req = Request.Builder()
                    .url("$SUPABASE/rest/v1/pet_gestures")
                    .header("apikey", SUPABASE_KEY)
                    .header("Authorization", "Bearer $SUPABASE_KEY")
                    .header("Prefer", "return=minimal")
                    .post(body)
                    .build()
                okHttp.newCall(req).execute()
            } catch (_: Exception) {}
        }.start()
    }

    // ========== 定时任务 ==========

    private var lastWaterRemind = 0L
    private var lastBedRemind = 0L
    private var lastBatteryRemind = 0L

    private fun checkTimeBased() {
        val cal = java.util.Calendar.getInstance()
        val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val now = System.currentTimeMillis()

        // 深夜催睡
        if (hour >= 0 && hour < 6 && now - lastBedRemind > 3600000) {
            lastBedRemind = now
            val msgs = arrayOf("都几点了还不睡！", "熬夜对身体不好……", "宝宝该睡了", "再不睡我要生气了")
            uiHandler.post {
                js("window.petEngine && window.petEngine.setExpression('angry')")
                js("window.petEngine && window.petEngine.showMessage('${msgs.random()}', 4000)")
            }
        }

        // 喝水提醒
        if (hour >= 8 && hour < 23 && now - lastWaterRemind > 7200000) {
            lastWaterRemind = now
            uiHandler.post {
                js("window.petEngine && window.petEngine.setExpression('default')")
                val msgs = arrayOf("该喝水了", "喝水时间到", "喝点水吧", "水水水！")
                js("window.petEngine && window.petEngine.showMessage('${msgs.random()}', 3000)")
            }
        }
    }

    // ========== 通知碎念 ==========

    private fun updateNotification() {
        val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        if (h == notiHour) return
        notiHour = h

        val text = when (h) {
            in 0..5 -> "还不睡💤"
            in 6..8 -> "早安☀️"
            in 9..11 -> "在想你❤️"
            in 12..13 -> "该吃饭了🍚"
            in 14..17 -> "陪你发呆……"
            in 18..19 -> "晚饭吃了没"
            in 20..21 -> "在看什么呢"
            in 22..23 -> "该睡了🌙"
            else -> "在想你❤️"
        }

        val notification = buildNoti(text)
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTI_ID, notification)
    }

    // ========== 主循环 ==========

    private val periodicCheck = object : Runnable {
        override fun run() {
            checkForegroundApp()
            checkTimeBased()
            updateNotification()
            doRandomBehavior()
            uiHandler.postDelayed(this, 5000)
        }
    }

    private fun startPeriodicTasks() {
        uiHandler.post(periodicCheck)
    }

    // ========== Supabase轮询 ==========

    private val okHttp = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

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
            .url("$SUPABASE/rest/v1/pet_state?order=id.desc&limit=5")
            .header("apikey", SUPABASE_KEY)
            .header("Authorization", "Bearer $SUPABASE_KEY")
            .build()
        okHttp.newCall(req).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {}
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.body?.let { body ->
                    val text = body.string()
                    if (text.length > 2) {
                        try {
                            val arr = JSONArray(text)
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
                                    if (key == "heat" && value.isNotEmpty()) {
                                        val h = value.toIntOrNull() ?: 0
                                        uiHandler.post { heat = h; updateHeat() }
                                    }
                                }
                                if (maxId > lastStateId) {
                                    lastStateId = maxId
                                    val expr = lastExpr
                                    val bubble = lastBubble
                                    uiHandler.post {
                                        if (expr.isNotEmpty()) {
                                            js("window.petEngine.setExpression('$expr')")
                                        }
                                        if (bubble.isNotEmpty()) {
                                            val safe = bubble.replace("'", "\\'").replace("\"", "\\\"")
                                            js("window.petEngine.showMessage('$safe', 5000)")
                                        }
                                    }
                                }
                            }
                        } catch (_: Exception) {}
                    }
                }
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
            .setContentTitle("🐶")
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
        try { unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
        screenshotObserver?.stopWatching()
        heatDecayTimer?.let { uiHandler.removeCallbacks(it) }
        lonelyTimer?.let { uiHandler.removeCallbacks(it) }
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
