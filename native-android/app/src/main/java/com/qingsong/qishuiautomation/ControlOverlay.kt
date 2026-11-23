package com.qingsong.qishuiautomation

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView

class ControlOverlay(private val context: Context) {
    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var contentView: View? = null

    private val updater = object : Runnable {
        override fun run() {
            val view = contentView ?: return
            view.findViewById<TextView>(R.id.overlayStatus).text = AutomationState.status
            view.findViewById<TextView>(R.id.overlayCount).text =
                "已领取 ${AutomationState.claimed} 次"
            handler.postDelayed(this, 1000)
        }
    }

    fun show() {
        if (contentView != null || !Settings.canDrawOverlays(context)) return
        val view = LayoutInflater.from(context).inflate(R.layout.overlay_control, null)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 20
            y = 160
        }

        view.findViewById<Button>(R.id.overlayStop).setOnClickListener {
            context.startService(
                Intent(context, AutomationService::class.java).setAction(
                    AutomationService.ACTION_STOP,
                ),
            )
        }
        windowManager.addView(view, params)
        contentView = view
        handler.post(updater)
    }

    fun hide() {
        handler.removeCallbacks(updater)
        contentView?.let { runCatching { windowManager.removeView(it) } }
        contentView = null
    }
}
