package com.qingsong.qishuiautomation

import android.app.Activity
import android.content.Intent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AutomationState {
    @Volatile
    var running: Boolean = false

    @Volatile
    var status: String = "未运行"

    @Volatile
    var claimed: Int = 0

    @Volatile
    var projectionUsed: Boolean = false

    private val logs = ArrayDeque<String>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.CHINA)

    @Synchronized
    fun beginRun() {
        running = true
        claimed = 0
        status = "正在启动"
        logs.clear()
    }

    @Synchronized
    fun log(message: String) {
        val line = "${timeFormat.format(Date())}  $message"
        logs.addLast(line)
        while (logs.size > 160) {
            logs.removeFirst()
        }
    }

    @Synchronized
    fun recentLogs(): String = logs.takeLast(70).joinToString("\n")
}

object ProjectionConsent {
    var resultCode: Int = Activity.RESULT_CANCELED
        private set
    var data: Intent? = null
        private set

    fun save(resultCode: Int, data: Intent?) {
        this.resultCode = resultCode
        this.data = data
        AutomationState.projectionUsed = false
    }

    fun isValid(): Boolean =
        resultCode == Activity.RESULT_OK && data != null && !AutomationState.projectionUsed

    fun clear() {
        resultCode = Activity.RESULT_CANCELED
        data = null
    }
}
