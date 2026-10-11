package com.digitalsmod.digitalliquid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

class IslandService : Service() {
    private lateinit var windowManager: WindowManager
    private var islandView: LinearLayout? = null
    private var expanded = false
    private var downY = 0f
    private var downX = 0f
    private var startY = 0
    private var params: WindowManager.LayoutParams? = null
    private val handler = Handler(Looper.getMainLooper())
    private var charging = false
    private var timerStartedAt = 0L
    private var timerRunning = false
    private var notificationTitle = "DigitalLiquid"
    private var notificationText = "Chạm để mở rộng"
    private var mediaTitle = ""
    private var mediaText = ""

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            render()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "DigitalLiquid overlay", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("DigitalLiquid đang hoạt động")
            .setContentText("Chạm để mở cài đặt ứng dụng")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
    }

    @Suppress("DEPRECATION")
    private fun showIsland() {
        if (islandView != null) return
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = rounded(Color.BLACK, 48)
            elevation = dp(10).toFloat()
        }
        islandView = root
        params = WindowManager.LayoutParams(
            dp(230),
            dp(48),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(8)
        }

        root.setOnTouchListener { view, event ->
            val lp = params ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startY = lp.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaY = (event.rawY - downY).toInt()
                    if (kotlin.math.abs(deltaY) > dp(8)) {
                        lp.y = (startY + deltaY).coerceAtLeast(dp(4))
                        try { windowManager.updateViewLayout(view, lp) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val deltaY = event.rawY - downY
                    if (deltaY > dp(40)) {
                        expanded = false
                        render()
                    } else if (kotlin.math.abs(deltaY) < dp(8)) {
                        expanded = !expanded
                        render()
                    }
                    true
                }
                else -> false
            }
        }
        windowManager.addView(root, params)
        render()
    }

    private fun render() {
        val root = islandView ?: return
        root.removeAllViews()
        root.background = rounded(Color.BLACK, if (expanded) 28 else 48)
        val heading = TextView(this).apply {
            text = if (charging) "⚡ Đang sạc" else if (mediaTitle.isNotBlank()) "♫ $mediaTitle" else "DigitalLiquid"
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1
            gravity = Gravity.CENTER
        }
        root.addView(heading, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(26)
        ))

        if (expanded) {
            val detail = TextView(this).apply {
                text = when {
                    mediaTitle.isNotBlank() -> mediaText.ifBlank { "Đang phát nhạc" }
                    timerRunning -> "Bộ đếm: ${formatElapsed((System.currentTimeMillis() - timerStartedAt) / 1000)}"
                    charging -> "Thiết bị đang được sạc"
                    else -> notificationText
                }
                setTextColor(0xFFBFC1C8.toInt())
                textSize = 12f
                gravity = Gravity.CENTER
                maxLines = 2
            }
            root.addView(detail, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(28)
            ))

            val controls = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            controls.addView(actionText("Timer") {
                if (timerRunning) timerRunning = false
                else {
                    timerStartedAt = System.currentTimeMillis()
                    timerRunning = true
                    handler.postDelayed(timerTick, 1000)
                }
                render()
            })
            controls.addView(actionText("Hide") {
                expanded = false
                render()
            })
            root.addView(controls)
        }
        params?.let {
            it.width = dp(if (expanded) 280 else 230)
            it.height = dp(if (expanded) 112 else 48)
            try { windowManager.updateViewLayout(root, it) } catch (_: Exception) {}
        }
    }

    private val timerTick = object : Runnable {
        override fun run() {
            if (timerRunning) {
                render()
                handler.postDelayed(this, 1000)
            }
        }
    }

    private fun actionText(label: String, action: () -> Unit) = TextView(this).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = 12f
        gravity = Gravity.CENTER
        setPadding(dp(14), dp(4), dp(14), dp(4))
        setOnClickListener { action() }
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun formatElapsed(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) String.format(Locale.getDefault(), "%d:%02d:%02d", h, m, s)
        else String.format(Locale.getDefault(), "%02d:%02d", m, s)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "digitalliquid_overlay"

        @Volatile private var instance: IslandService? = null

        fun updateNotification(title: String, text: String, isMedia: Boolean) {
            val service = instance ?: return
            if (isMedia) {
                service.mediaTitle = title
                service.mediaText = text
            } else {
                service.notificationTitle = title
                service.notificationText = text
            }
            service.handler.post { service.render() }
        }

        fun clearNotification() {
            val service = instance ?: return
            service.mediaTitle = ""
            service.mediaText = ""
            service.handler.post { service.render() }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForeground(42, buildForegroundNotification())
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        showIsland()
    }

    override fun onDestroy() {
        instance = null
        try { unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
        islandView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        islandView = null
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
