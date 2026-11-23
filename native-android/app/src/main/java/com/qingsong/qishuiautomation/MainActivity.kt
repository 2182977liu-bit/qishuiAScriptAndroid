package com.qingsong.qishuiautomation

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var permissionSummary: TextView
    private lateinit var statusText: TextView
    private lateinit var countText: TextView
    private lateinit var logText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button

    private val refresher = object : Runnable {
        override fun run() {
            refreshUi()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        permissionSummary = findViewById(R.id.permissionSummary)
        statusText = findViewById(R.id.statusText)
        countText = findViewById(R.id.countText)
        logText = findViewById(R.id.logText)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)

        findViewById<Button>(R.id.accessibilityButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.projectionButton).setOnClickListener {
            requestProjection()
        }
        findViewById<Button>(R.id.overlayButton).setOnClickListener {
            requestOverlay()
        }
        findViewById<Button>(R.id.batteryButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
        startButton.setOnClickListener { startAutomation() }
        stopButton.setOnClickListener { stopAutomation() }

        requestNotificationPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(refresher)
        handler.post(refresher)
    }

    override fun onPause() {
        handler.removeCallbacks(refresher)
        super.onPause()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2001)
        }
    }

    private fun requestProjection() {
        if (!QishuiAccessibilityService.isConnected()) {
            toast("请先完成第 1 步：开启无障碍")
            return
        }
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_PROJECTION)
    }

    private fun requestOverlay() {
        if (!ProjectionConsent.isValid() && !AutomationState.running) {
            toast("请先完成第 2 步：授权屏幕识别")
            return
        }
        if (Settings.canDrawOverlays(this)) {
            toast("悬浮窗权限已开启")
            return
        }
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"),
            ),
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PROJECTION) {
            if (resultCode == RESULT_OK && data != null) {
                ProjectionConsent.save(resultCode, data)
                AutomationState.log("屏幕识别授权成功")
            } else {
                toast("未授权屏幕识别")
            }
            refreshUi()
        }
    }

    private fun startAutomation() {
        if (!QishuiAccessibilityService.isConnected() ||
            !ProjectionConsent.isValid() ||
            !Settings.canDrawOverlays(this)
        ) {
            toast("请先完成全部必需授权")
            refreshUi()
            return
        }
        val serviceIntent = Intent(this, AutomationService::class.java).apply {
            action = AutomationService.ACTION_START
            putExtra(AutomationService.EXTRA_RESULT_CODE, ProjectionConsent.resultCode)
            putExtra(AutomationService.EXTRA_CONSENT_DATA, ProjectionConsent.data)
        }
        startForegroundService(serviceIntent)
    }

    private fun stopAutomation() {
        startService(
            Intent(this, AutomationService::class.java).setAction(
                AutomationService.ACTION_STOP,
            ),
        )
    }

    private fun refreshUi() {
        val accessibility = QishuiAccessibilityService.isConnected()
        val projection = ProjectionConsent.isValid()
        val overlay = Settings.canDrawOverlays(this)
        val projectionText = when {
            projection -> "已授权"
            AutomationState.projectionUsed -> "重新运行前需再次授权"
            else -> "未授权"
        }
        permissionSummary.text = """
            无障碍：${if (accessibility) "已开启" else "未开启"}
            屏幕识别：$projectionText
            悬浮窗：${if (overlay) "已开启" else "未开启"}
        """.trimIndent()
        statusText.text = "状态：${AutomationState.status}"
        countText.text = "成功领取：${AutomationState.claimed} 次"
        startButton.isEnabled = accessibility && projection && overlay && !AutomationState.running
        stopButton.isEnabled = AutomationState.running
        val logs = AutomationState.recentLogs()
        if (logText.text.toString() != logs) logText.text = logs
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val REQUEST_PROJECTION = 1001
    }
}
