package com.qingsong.qishuiautomation

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AutomationEngine(
    private val context: Context,
    private val capture: ScreenCapture,
    private val ocr: OcrEngine,
    private val overlay: ControlOverlay?,
    private val onFinished: () -> Unit,
) {
    @Volatile
    private var running = true

    private var lastOcrErrorAt = 0L
    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Thread({ runSafely() }, "qishui-automation")

    fun start() {
        AutomationState.beginRun()
        worker.start()
    }

    fun stop() {
        running = false
        AutomationState.running = false
        worker.interrupt()
    }

    private fun runSafely() {
        try {
            runAutomation()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            AutomationState.status = "已停止"
        } catch (error: Exception) {
            AutomationState.status = "运行异常"
            AutomationState.log("主流程异常：${error.message ?: error.javaClass.simpleName}")
        } finally {
            running = false
            AutomationState.running = false
            runCatching { ocr.close() }
            runCatching { capture.close() }
            onFinished()
        }
    }

    private fun runAutomation() {
        AutomationState.log("原生自动化开始")
        if (!QishuiAccessibilityService.isConnected()) {
            throw IllegalStateException("无障碍服务未连接")
        }
        if (!launchApp()) {
            AutomationState.status = "未找到汽水音乐"
            return
        }

        skipSplashAd()
        dismissMembershipPopup()
        AutomationState.log("请手动进入第一个广告，后续由脚本接管")
        runAdLoop()
    }

    private fun launchApp(): Boolean {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(APP_PACKAGE)
        if (launchIntent == null) {
            AutomationState.log("未安装汽水音乐或无法获取启动入口")
            return false
        }
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launchIntent)
        AutomationState.log("已启动汽水音乐")
        sleepChecked(2500)
        return true
    }

    private fun readHits(): List<OcrHit> {
        val bitmap = capture.capture() ?: return emptyList()
        return try {
            ocr.recognize(bitmap)
        } catch (interrupted: InterruptedException) {
            throw interrupted
        } catch (error: Exception) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastOcrErrorAt >= 5000) {
                AutomationState.log("OCR 识别异常：${error.message ?: error.javaClass.simpleName}")
                lastOcrErrorAt = now
            }
            emptyList()
        } finally {
            bitmap.recycle()
        }
    }

    private fun region(
        left: Double,
        top: Double,
        right: Double,
        bottom: Double,
    ): ScreenRect = ScreenRect(
        (capture.width * left).toInt(),
        (capture.height * top).toInt(),
        (capture.width * right).toInt(),
        (capture.height * bottom).toInt(),
    )

    private fun splashRegion() = region(0.55, 0.02, 1.0, 0.22)
    private fun topClaimRegion() = region(0.40, 0.02, 1.0, 0.20)
    private fun rewardDialogRegion() = region(0.03, 0.12, 0.97, 0.94)
    private fun continueRegion() = region(0.07, 0.25, 0.93, 0.68)
    private fun membershipRegion() = region(0.0, 0.82, 1.0, 1.0)
    private fun liveCloseRegion() = region(0.50, 0.0, 1.0, 0.22)

    private fun findText(
        hits: List<OcrHit>,
        patterns: List<String>,
        rect: ScreenRect? = null,
        exclude: List<String> = emptyList(),
    ): OcrHit? {
        for (pattern in patterns) {
            val regex = Regex(pattern, RegexOption.IGNORE_CASE)
            for (hit in hits) {
                if (exclude.any { hit.text.contains(it) }) continue
                if (rect != null && !rect.contains(hit.centerX, hit.centerY)) continue
                if (regex.containsMatchIn(hit.text)) return hit
            }
        }
        return null
    }

    private fun click(hit: OcrHit, description: String): Boolean {
        if (!running) return false
        if (hit.centerX !in 0 until capture.width || hit.centerY !in 0 until capture.height) {
            AutomationState.log("拦截越界点击：$description")
            return false
        }
        return performClick(hit.centerX, hit.centerY, "$description：${hit.text}")
    }

    private fun performClick(x: Int, y: Int, description: String): Boolean {
        if (!running) return false
        // 手势点击前临时隐藏悬浮窗，避免窗口拦截触摸；点击后恢复。
        val overlayWasVisible = overlay?.isVisible() == true
        if (overlayWasVisible) setOverlayVisible(false)
        val dispatched = QishuiAccessibilityService.click(x, y)
        AutomationState.log("${if (dispatched) "点击" else "点击失败"} $description ($x,$y)")
        if (dispatched) sleepChecked(700)
        if (overlayWasVisible) setOverlayVisible(true)
        return dispatched
    }

    private fun setOverlayVisible(visible: Boolean) {
        val target = overlay ?: return
        if (visible && !running) return
        val completed = CountDownLatch(1)
        mainHandler.post {
            runCatching {
                if (visible) target.show() else target.hide()
            }.onFailure {
                AutomationState.log("悬浮窗状态更新失败：${it.message ?: it.javaClass.simpleName}")
            }
            completed.countDown()
        }
        try {
            if (!completed.await(2, TimeUnit.SECONDS)) {
                AutomationState.log("悬浮窗状态更新超时")
            }
        } catch (_: InterruptedException) {
            // 停止任务时可能中断等待；保留中断标志，不覆盖调用方的返回值。
            Thread.currentThread().interrupt()
        }
    }

    private fun clickDescription(description: String, rect: ScreenRect): Boolean {
        val hit = QishuiAccessibilityService.findDescription(description, rect) ?: return false
        return click(hit, "控件$description")
    }

    private fun foregroundPackage(): String =
        QishuiAccessibilityService.currentPackage().orEmpty()

    private fun ensureInApp(): Boolean {
        val current = foregroundPackage()
        if (current.isEmpty() || current.contains(APP_PACKAGE)) return false
        if (SAFE_SYSTEM_PACKAGES.any { current.contains(it) }) return false
        AutomationState.log("检测到离开汽水音乐（$current），尝试拉回")
        return launchApp()
    }

    private fun skipSplashAd() {
        AutomationState.status = "正在处理开屏"
        val deadline = SystemClock.elapsedRealtime() + 7000
        while (running && SystemClock.elapsedRealtime() < deadline) {
            val hits = readHits()
            val skip = findText(hits, listOf("跳过广告", "跳过"), splashRegion())
            if (skip != null && click(skip, "跳过开屏广告")) return
            sleepChecked(350)
        }
        AutomationState.log("未检测到开屏跳过按钮")
    }

    private fun dismissMembershipPopup() {
        val hits = readHits()
        val pay = findText(hits, listOf("元开通"), membershipRegion()) ?: return
        AutomationState.log("检测到会员弹窗：${pay.text}，点击外部遮罩")
        performClick(capture.width / 2, (capture.height * 0.30).toInt(), "关闭会员弹窗")
        sleepChecked(1000)
    }

    private fun handleLivePrompt(hits: List<OcrHit>): Boolean {
        val prompt = findText(
            hits,
            listOf("继续互动", "自动进入直播", "秒后进入直播", "即将进入直播"),
        ) ?: return false
        AutomationState.log("检测到直播互动提示：${prompt.text}")
        val exit = findText(
            hits,
            listOf("退出互动", "退出直播间", "不感兴趣", "^退出$", "^取消$"),
            exclude = listOf("继续", "进入", "坚持", "放弃"),
        ) ?: return false
        return click(exit, "退出直播互动")
    }

    private fun handleLiveRoom(hits: List<OcrHit>): Boolean {
        val room = findText(
            hits,
            listOf("更多直播", "说点什么", "在线观众", "直播间人数"),
        ) ?: return false
        AutomationState.log("检测到直播间：${room.text}")

        for (description in listOf("关闭", "退出")) {
            if (clickDescription(description, liveCloseRegion())) return true
        }
        val close = findText(
            hits,
            listOf("^关闭$", "^退出$", "^[×✕✖Xx]$"),
            liveCloseRegion(),
        )
        if (close != null && click(close, "关闭直播间")) return true

        AutomationState.log("未找到直播间关闭按钮，使用系统返回")
        val sent = QishuiAccessibilityService.back()
        if (sent) sleepChecked(1200)
        return sent
    }

    private fun waitAdCountdown(timeoutSeconds: Int): WaitResult {
        AutomationState.status = "等待广告结束"
        val start = SystemClock.elapsedRealtime()
        var deadline = start + timeoutSeconds * 1000L
        val hardDeadline = start + maxOf(timeoutSeconds, MAX_AD_TIMEOUT) * 1000L
        var checks = 0
        var lastCountdown: Int? = null

        while (running && SystemClock.elapsedRealtime() < deadline) {
            checks += 1
            val hits = readHits()
            if (hits.isEmpty()) {
                sleepChecked(500)
                continue
            }

            val claim = findText(hits, listOf("领取成功"), topClaimRegion())
            if (claim != null) {
                AutomationState.log("检测到顶部领取成功")
                return WaitResult(WaitStatus.READY, claim)
            }

            if (findText(hits, QUOTA_PATTERNS) != null) {
                AutomationState.log("检测到当日额度已用完")
                return WaitResult(WaitStatus.QUOTA_EXHAUSTED)
            }

            if (handleLivePrompt(hits)) continue
            if (handleLiveRoom(hits)) continue

            val continueHit = findText(
                hits,
                listOf("继续观看", "继续看视频", "继续看广告"),
                continueRegion(),
                exclude = listOf("下载", "直播", "互动"),
            )
            if (continueHit != null && click(continueHit, "继续观看广告")) {
                sleepChecked(1500)
                continue
            }

            val countdown = findText(
                hits,
                listOf("\\d+\\s*秒后"),
                region(0.0, 0.0, 1.0, 0.22),
                exclude = listOf("直播", "进入"),
            )
            if (countdown != null) {
                val seconds = Regex("(\\d+)\\s*秒后")
                    .find(countdown.text)?.groupValues?.getOrNull(1)?.toIntOrNull()
                if (seconds != null) {
                    if (seconds != lastCountdown) {
                        AutomationState.log("广告倒计时：${countdown.text}")
                        lastCountdown = seconds
                    }
                    val extension = SystemClock.elapsedRealtime() + seconds * 1000L + 15000L
                    deadline = minOf(hardDeadline, maxOf(deadline, extension))
                }
            }

            if (checks % 5 == 0 && ensureInApp()) continue
            sleepChecked(600)
        }
        return WaitResult(if (running) WaitStatus.TIMEOUT else WaitStatus.STOPPED)
    }

    private fun claimReward(initialHit: OcrHit): String {
        AutomationState.status = "正在领取奖励"
        AutomationState.log("领取阶段临时隐藏悬浮窗，避免遮挡按钮")
        setOverlayVisible(false)
        try {
            if (QishuiAccessibilityService.clickText(listOf("领取成功"), topClaimRegion())) {
                AutomationState.log("通过控件点击领取成功")
                sleepChecked(700)
            } else if (!click(initialHit, "领取成功")) {
                return "failed"
            }

            val deadline = SystemClock.elapsedRealtime() + CLAIM_TIMEOUT * 1000L
            var retried = false
            while (running && SystemClock.elapsedRealtime() < deadline) {
                if (QishuiAccessibilityService.clickText(
                        REWARD_BUTTON_LABELS,
                        rewardDialogRegion(),
                    )
                ) {
                    AutomationState.log("通过控件点击领取奖励")
                    sleepChecked(1000)
                    return "claimed"
                }

                val hits = readHits()
                if (findText(hits, QUOTA_PATTERNS) != null) return "quota_exhausted"

                val reward = findText(
                    hits,
                    REWARD_BUTTON_LABELS,
                    rewardDialogRegion(),
                )
                if (reward != null && click(reward, "领取奖励")) {
                    sleepChecked(1000)
                    return "claimed"
                }

                val stillClaim = findText(hits, listOf("领取成功"), topClaimRegion())
                if (stillClaim != null && !retried) {
                    retried = true
                    click(stillClaim, "重试领取成功")
                    continue
                }
                sleepChecked(400)
            }
            AutomationState.log("未找到可点击的领取奖励按钮，本轮失败")
            return "failed"
        } finally {
            setOverlayVisible(true)
        }
    }

    private fun runAdLoop() {
        var success = 0
        var idle = 0

        for (round in 1..MAX_ROUNDS) {
            if (!running) break
            AutomationState.log("第 $round/$MAX_ROUNDS 轮")
            val timeout = if (round == 1) FIRST_AD_TIMEOUT else AD_TIMEOUT
            val result = waitAdCountdown(timeout)

            when (result.status) {
                WaitStatus.STOPPED -> break
                WaitStatus.QUOTA_EXHAUSTED -> break
                WaitStatus.TIMEOUT -> idle += 1
                WaitStatus.READY -> {
                    when (claimReward(requireNotNull(result.claimHit))) {
                        "claimed" -> {
                            success += 1
                            idle = 0
                            AutomationState.claimed = success
                            AutomationState.log("领取成功，累计 $success 次")
                        }
                        "quota_exhausted" -> break
                        else -> idle += 1
                    }
                }
            }

            if (idle > 0) {
                AutomationState.log("连续 $idle/$MAX_IDLE_ROUNDS 轮未领取")
            }
            if (idle >= MAX_IDLE_ROUNDS) {
                AutomationState.log("连续多轮无广告，停止")
                break
            }
            sleepChecked(1500)
        }
        AutomationState.status = if (running) "已结束：成功 $success 次" else "已停止"
    }

    private fun sleepChecked(milliseconds: Long) {
        if (milliseconds > 0) Thread.sleep(milliseconds)
    }

    companion object {
        private const val APP_PACKAGE = "com.luna.music"
        private const val MAX_ROUNDS = 50
        private const val MAX_IDLE_ROUNDS = 5
        private const val FIRST_AD_TIMEOUT = 120
        private const val AD_TIMEOUT = 75
        private const val MAX_AD_TIMEOUT = 120
        private const val CLAIM_TIMEOUT = 15

        private val REWARD_BUTTON_LABELS = listOf(
            "领取奖励",
            "立即领取",
            "确认领取",
        )

        private val SAFE_SYSTEM_PACKAGES = listOf(
            "android",
            "com.android.systemui",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.android.packageinstaller",
        )

        private val QUOTA_PATTERNS = listOf(
            "今日已领完",
            "今日次数已用完",
            "今日已达上限",
            "明日再来",
            "明天再来",
            "次数已用完",
        )
    }
}
