package com.qingsong.qishuiautomation

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs

class ControlOverlay(private val context: Context) {
    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var contentView: View? = null
    private var expanded = false
    private var collapsedX = 0
    private var collapsedY = 160

    private val updater = object : Runnable {
        override fun run() {
            val view = contentView ?: return
            view.findViewById<TextView>(R.id.overlayStatus).text = AutomationState.status
            view.findViewById<TextView>(R.id.overlayCount).text =
                "已领取 ${AutomationState.claimed} 次"
            if (expanded) updateLogs(view)
            handler.postDelayed(this, 1000)
        }
    }

    fun show() {
        if (contentView != null || !Settings.canDrawOverlays(context)) return
        val view = LayoutInflater.from(context).inflate(R.layout.overlay_control, null)
        val metrics = context.resources.displayMetrics
        collapsedX = (metrics.widthPixels - dp(COLLAPSED_WIDTH_DP) - dp(12))
            .coerceAtLeast(0)
        collapsedY = dp(160)

        val params = WindowManager.LayoutParams(
            dp(COLLAPSED_WIDTH_DP),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = collapsedX
            y = collapsedY
        }

        view.findViewById<Button>(R.id.overlayToggle).setOnClickListener {
            expanded = !expanded
            applyExpandedState(view, params)
        }
        view.findViewById<Button>(R.id.overlayStop).setOnClickListener {
            context.startService(
                Intent(context, AutomationService::class.java).setAction(
                    AutomationService.ACTION_STOP,
                ),
            )
        }
        installDrag(view, params)
        applyExpandedState(view, params, updateLayout = false)
        windowManager.addView(view, params)
        contentView = view
        handler.post(updater)
    }

    private fun applyExpandedState(
        view: View,
        params: WindowManager.LayoutParams,
        updateLayout: Boolean = true,
    ) {
        val content = view.findViewById<View>(R.id.overlayContent)
        val toggle = view.findViewById<Button>(R.id.overlayToggle)
        val metrics = context.resources.displayMetrics

        if (expanded) {
            collapsedX = params.x
            collapsedY = params.y
            params.width = (metrics.widthPixels - dp(24)).coerceAtLeast(dp(240))
            params.height = (metrics.heightPixels * EXPANDED_HEIGHT_RATIO).toInt()
            content.visibility = View.VISIBLE
            toggle.text = "最小化"
        } else {
            params.width = dp(COLLAPSED_WIDTH_DP)
            params.height = WindowManager.LayoutParams.WRAP_CONTENT
            params.x = collapsedX
            params.y = collapsedY
            content.visibility = View.GONE
            toggle.text = "最大化"
        }
        clampToScreen(view, params)
        if (updateLayout && view.parent != null) {
            windowManager.updateViewLayout(view, params)
            if (expanded) view.post { updateLogs(view) }
        }
    }

    private fun installDrag(view: View, params: WindowManager.LayoutParams) {
        val handle = view.findViewById<TextView>(R.id.overlayDragHandle)
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        var startX = 0
        var startY = 0
        var startRawX = 0f
        var startRawY = 0f
        var dragging = false

        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    startRawX = event.rawX
                    startRawY = event.rawY
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.rawX - startRawX
                    val deltaY = event.rawY - startRawY
                    if (!dragging && (abs(deltaX) > touchSlop || abs(deltaY) > touchSlop)) {
                        dragging = true
                    }
                    if (dragging) {
                        params.x = startX + deltaX.toInt()
                        params.y = startY + deltaY.toInt()
                        clampToScreen(view, params)
                        if (view.parent != null) {
                            windowManager.updateViewLayout(view, params)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }

    private fun clampToScreen(view: View, params: WindowManager.LayoutParams) {
        val metrics = context.resources.displayMetrics
        val width = if (params.width > 0) params.width else view.width
        val height = if (params.height > 0) params.height else view.height
        val maxX = (metrics.widthPixels - width).coerceAtLeast(0)
        val maxY = (metrics.heightPixels - height).coerceAtLeast(0)
        params.x = params.x.coerceIn(0, maxX)
        params.y = params.y.coerceIn(0, maxY)
    }

    private fun updateLogs(view: View) {
        val scroll = view.findViewById<ScrollView>(R.id.overlayLogScroll)
        val logView = view.findViewById<TextView>(R.id.overlayLogs)
        val logs = AutomationState.recentLogs().ifEmpty { "暂无日志" }
        if (logView.text.toString() == logs) return

        val child = scroll.getChildAt(0)
        val wasAtBottom = child == null ||
            scroll.scrollY + scroll.height >= child.height - dp(32)
        logView.text = logs
        if (wasAtBottom) {
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    fun hide() {
        handler.removeCallbacks(updater)
        contentView?.let { runCatching { windowManager.removeView(it) } }
        contentView = null
    }

    companion object {
        private const val COLLAPSED_WIDTH_DP = 280
        private const val EXPANDED_HEIGHT_RATIO = 0.68
    }
}
