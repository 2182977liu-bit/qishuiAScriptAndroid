package com.qingsong.qishuiautomation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

class AutomationService : Service() {
    private var engine: AutomationEngine? = null
    private var overlay: ControlOverlay? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopAutomation()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                if (engine != null) return START_NOT_STICKY
                startAutomation(intent)
            }
        }
        return START_NOT_STICKY
    }

    private fun startAutomation(intent: Intent) {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
        val consentData = intent.getParcelableExtra<Intent>(EXTRA_CONSENT_DATA)
        if (resultCode != android.app.Activity.RESULT_OK || consentData == null) {
            AutomationState.status = "缺少屏幕识别授权"
            stopSelf()
            return
        }
        if (!QishuiAccessibilityService.isConnected()) {
            AutomationState.status = "请先开启无障碍"
            stopSelf()
            return
        }

        var capture: ScreenCapture? = null
        var ocr: OcrEngine? = null
        try {
            startForegroundWithType()
            AutomationState.projectionUsed = true

            val activeCapture = ScreenCapture(this, resultCode, consentData)
            capture = activeCapture
            val activeOcr = OcrEngine()
            ocr = activeOcr
            val activeOverlay = ControlOverlay(this).also { it.show() }
            overlay = activeOverlay
            engine = AutomationEngine(this, activeCapture, activeOcr, activeOverlay) {
                mainHandler.post { finishFromEngine() }
            }.also { it.start() }
        } catch (error: Exception) {
            AutomationState.running = false
            AutomationState.status = "启动失败"
            AutomationState.log("服务启动失败：${error.message ?: error.javaClass.simpleName}")
            runCatching { overlay?.hide() }
            runCatching { ocr?.close() }
            runCatching { capture?.close() }
            stopForeground(true)
            stopSelf()
        }
    }

    private fun startForegroundWithType() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "汽水自动化运行状态",
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, AutomationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("汽水自动助手")
            .setContentText("自动化正在运行")
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(contentIntent)
            .addAction(0, "停止", stopIntent)
            .setOngoing(true)
            .build()
    }

    private fun stopAutomation() {
        AutomationState.status = "正在停止"
        engine?.stop()
        runCatching { overlay?.hide() }
        stopForeground(true)
        stopSelf()
    }

    private fun finishFromEngine() {
        runCatching { overlay?.hide() }
        overlay = null
        engine = null
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        engine?.stop()
        runCatching { overlay?.hide() }
        engine = null
        overlay = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.qingsong.qishuiautomation.START"
        const val ACTION_STOP = "com.qingsong.qishuiautomation.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_CONSENT_DATA = "consent_data"
        private const val CHANNEL_ID = "automation_status"
        private const val NOTIFICATION_ID = 1001
    }
}
