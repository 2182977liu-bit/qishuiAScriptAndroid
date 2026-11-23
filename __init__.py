# -*- coding: utf-8 -*-
"""汽水音乐 Android 自动化脚本（AScript 工程）。

流程：启动 App -> 跳过开屏 -> 用户手动进入首个广告 -> 等待广告结束
      -> 领取成功 -> 领取奖励 -> 下一轮。

仅适用于 AScript Android 的无障碍或 Root 点击通道；图色/HID 模式需要
另外接入对应点击实现，不能直接复用本文件中的 action.click。
"""

import re
import time
import traceback

from ascript.android import action, system  # type: ignore
from ascript.android.node import Selector  # type: ignore
from ascript.android.screen import Ocr  # type: ignore
from ascript.android.system import Device  # type: ignore


# ======================== 用户配置 ========================

APP_PACKAGE = "com.luna.music"
OCR_ENGINE = "paddle"  # 可选："paddle" / "mlkit"
SELECTOR_MODE = 2  # 无障碍默认为 2；Root 模式通常改为 9

MAX_ROUNDS = 50
MAX_IDLE_ROUNDS = 5
FIRST_AD_TIMEOUT = 120
AD_TIMEOUT = 75
MAX_AD_TIMEOUT = 120
CLAIM_TIMEOUT = 15
LOOP_INTERVAL = 1.5

# 默认屏幕尺寸只作为读取失败时的兜底，运行时会用真实尺寸覆盖。
SCREEN_W = 1080
SCREEN_H = 2400

SAFE_SYSTEM_PACKAGES = (
    "android",
    "com.android.systemui",
    "com.android.permissioncontroller",
    "com.google.android.permissioncontroller",
    "com.android.packageinstaller",
)

QUOTA_EXHAUSTED_PATTERNS = (
    r"今日已领完",
    r"今日次数已用完",
    r"今日已达上限",
    r"明日再来",
    r"明天再来",
    r"次数已用完",
)


# ======================== 基础工具 ========================


def log(message):
    print("[%s] %s" % (time.strftime("%H:%M:%S"), message))


def init_screen():
    global SCREEN_W, SCREEN_H
    try:
        display = Device.display()
        width = int(getattr(display, "widthPixels", 0))
        height = int(getattr(display, "heightPixels", 0))
        if width > 0 and height > 0:
            SCREEN_W, SCREEN_H = width, height
    except Exception as exc:
        log("读取屏幕尺寸失败，使用默认值：%s" % exc)
    log("屏幕尺寸：%s x %s" % (SCREEN_W, SCREEN_H))


def region(left, top, right, bottom):
    """按屏幕比例生成 [x1, y1, x2, y2]。"""
    return [
        int(SCREEN_W * left),
        int(SCREEN_H * top),
        int(SCREEN_W * right),
        int(SCREEN_H * bottom),
    ]


def splash_region():
    return region(0.55, 0.02, 1.0, 0.22)


def top_claim_region():
    return region(0.40, 0.02, 1.0, 0.20)


def reward_dialog_region():
    return region(0.08, 0.20, 0.92, 0.78)


def continue_region():
    return region(0.07, 0.25, 0.93, 0.68)


def membership_region():
    return region(0.0, 0.82, 1.0, 1.0)


def live_close_region():
    return region(0.50, 0.0, 1.0, 0.22)


def parse_rect(value):
    """兼容 list/dict、AScript Rect 与 Android Rect。"""
    if value is None:
        return None
    try:
        if isinstance(value, (list, tuple)) and len(value) == 4:
            values = list(value)
        elif isinstance(value, dict):
            if all(key in value for key in ("x1", "y1", "x2", "y2")):
                values = [value["x1"], value["y1"], value["x2"], value["y2"]]
            elif all(key in value for key in ("left", "top", "right", "bottom")):
                values = [value["left"], value["top"], value["right"], value["bottom"]]
            else:
                return None
        elif all(hasattr(value, key) for key in ("x1", "y1", "x2", "y2")):
            values = [value.x1, value.y1, value.x2, value.y2]
        elif all(hasattr(value, key) for key in ("left", "top", "right", "bottom")):
            values = [value.left, value.top, value.right, value.bottom]
        else:
            return None
        left, top, right, bottom = [int(item) for item in values]
        if right <= left or bottom <= top:
            return None
        return [left, top, right, bottom]
    except Exception:
        return None


def normalize_hit(item):
    """把 Ocr 返回的 dict/OcrText 统一成可点击坐标。"""
    if item is None:
        return None
    try:
        if isinstance(item, dict):
            text = item.get("text", "")
            center_x = item.get("center_x", 0)
            center_y = item.get("center_y", 0)
            raw_rect = item.get("rect", item.get("bounds"))
            confidence = item.get("confidence", 0)
        else:
            text = getattr(item, "text", "")
            center_x = getattr(item, "center_x", 0)
            center_y = getattr(item, "center_y", 0)
            raw_rect = getattr(item, "rect", getattr(item, "bounds", None))
            confidence = getattr(item, "confidence", 0)
        text = str(text).strip()
        center_x, center_y = int(center_x or 0), int(center_y or 0)
        if center_x <= 0 or center_y <= 0:
            rect = parse_rect(raw_rect)
            if rect:
                center_x = (rect[0] + rect[2]) // 2
                center_y = (rect[1] + rect[3]) // 2
        if not text or center_x <= 0 or center_y <= 0:
            return None
        return {
            "text": text,
            "center_x": center_x,
            "center_y": center_y,
            "confidence": confidence,
        }
    except Exception:
        return None


def scan_screen():
    """一次截屏识别全部文字，后续匹配不再重复截图。"""
    try:
        results = Ocr.find_all() or []
        hits = []
        for item in results:
            hit = normalize_hit(item)
            if hit:
                hits.append(hit)
        return hits
    except Exception as exc:
        log("OCR 识别异常：%s" % exc)
        return []


def in_region(x, y, rect):
    if rect is None:
        return True
    return rect[0] <= x <= rect[2] and rect[1] <= y <= rect[3]


def find_text(hits, patterns, rect=None, exclude=()):
    for pattern in patterns:
        for hit in hits:
            text = hit["text"]
            if any(word in text for word in exclude):
                continue
            if not in_region(hit["center_x"], hit["center_y"], rect):
                continue
            try:
                if re.search(pattern, text):
                    return hit
            except re.error:
                if pattern in text:
                    return hit
    return None


def click_hit(hit, description):
    """所有 OCR 点击都必须由当前界面的有效命中触发。"""
    if not hit:
        return False
    x, y = int(hit["center_x"]), int(hit["center_y"])
    if not (0 <= x < SCREEN_W and 0 <= y < SCREEN_H):
        log("拦截越界点击：%s (%s, %s)" % (description, x, y))
        return False
    try:
        log("点击 %s：%s @ (%s, %s)" % (description, hit["text"], x, y))
        action.click(x, y)
        time.sleep(0.7)
        return True
    except Exception as exc:
        log("点击失败 [%s]：%s" % (description, exc))
        return False


def click_desc_in_region(description, rect):
    """用安卓控件 desc 查找纯图标按钮，并按区域过滤。"""
    try:
        query = Selector(mode=SELECTOR_MODE).desc(description)
        nodes = query.find_all()
        if nodes is None:
            node = query.find()
            nodes = [node] if node else []
        for node in nodes:
            rect_value = parse_rect(getattr(node, "rect", None))
            if not rect_value:
                continue
            x = (rect_value[0] + rect_value[2]) // 2
            y = (rect_value[1] + rect_value[3]) // 2
            if in_region(x, y, rect):
                return click_hit(
                    {"text": description, "center_x": x, "center_y": y},
                    "控件 %s" % description,
                )
    except Exception as exc:
        log("控件查找失败 [%s]：%s" % (description, exc))
    return False


def foreground_package():
    try:
        value = system.get_foreground_app()
        if isinstance(value, dict):
            return str(value.get("package", value.get("packageName", "")) or "")
        return str(value or "")
    except Exception:
        return ""


def ensure_in_app():
    """广告外跳时拉回；系统权限界面不处理。"""
    package = foreground_package()
    if not package or APP_PACKAGE in package:
        return False
    if any(safe in package for safe in SAFE_SYSTEM_PACKAGES):
        return False
    log("检测到离开汽水音乐（%s），尝试拉回" % package)
    try:
        system.open(APP_PACKAGE)
        time.sleep(2)
        return True
    except Exception as exc:
        log("拉回汽水音乐失败：%s" % exc)
        return False


# ======================== 启动与弹窗 ========================


def launch_app():
    try:
        log("启动汽水音乐：%s" % APP_PACKAGE)
        system.open(APP_PACKAGE)
        time.sleep(2.5)
        return True
    except Exception as exc:
        log("启动失败：%s" % exc)
        return False


def skip_splash_ad(timeout=7):
    log("检测开屏广告")
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        hits = scan_screen()
        skip = find_text(hits, (r"跳过广告", r"跳过"), splash_region())
        if skip and click_hit(skip, "跳过开屏广告"):
            return True
        time.sleep(0.35)
    log("未检测到开屏跳过按钮")
    return False


def dismiss_membership_popup():
    """仅在底部出现“元开通”时，点击弹窗外遮罩。"""
    hits = scan_screen()
    pay = find_text(hits, (r"元开通",), membership_region())
    if not pay:
        return False
    try:
        x, y = SCREEN_W // 2, int(SCREEN_H * 0.30)
        log("检测到会员续费弹窗，点击外部遮罩关闭")
        action.click(x, y)
        time.sleep(1)
        return True
    except Exception as exc:
        log("关闭会员弹窗失败：%s" % exc)
        return False


# ======================== 广告状态处理 ========================


def handle_live_prompt(hits):
    """只在明确出现直播互动提示时，点击安全退出选项。"""
    prompt = find_text(
        hits,
        (r"继续互动", r"自动进入直播", r"秒后进入直播", r"即将进入直播"),
    )
    if not prompt:
        return False
    exit_hit = find_text(
        hits,
        (r"退出互动", r"退出直播间", r"不感兴趣", r"^退出$", r"^取消$"),
        exclude=("继续", "进入", "坚持", "放弃"),
    )
    if exit_hit:
        return click_hit(exit_hit, "退出直播互动")
    return False


def handle_live_room(hits):
    """已进入直播间时优先关闭，找不到关闭按钮再使用系统返回。"""
    room = find_text(hits, (r"更多直播", r"说点什么", r"在线观众", r"直播间人数"))
    if not room:
        return False

    for description in ("关闭", "退出"):
        if click_desc_in_region(description, live_close_region()):
            return True

    close_hit = find_text(
        hits,
        (r"^关闭$", r"^退出$", r"^[×✕✖Xx]$"),
        live_close_region(),
    )
    if close_hit and click_hit(close_hit, "关闭直播间"):
        return True

    try:
        log("未找到直播间关闭按钮，使用 Android 返回键")
        action.Key.back()
        time.sleep(1.2)
        return True
    except Exception as exc:
        log("退出直播间失败：%s" % exc)
        return False


def wait_ad_countdown(timeout):
    """返回 (ready/timeout/quota_exhausted, 领取成功坐标)。"""
    log("等待广告结束，最长 %s 秒" % timeout)
    start = time.monotonic()
    deadline = start + timeout
    hard_deadline = start + max(timeout, MAX_AD_TIMEOUT)
    checks = 0
    last_countdown = None

    while time.monotonic() < deadline:
        checks += 1
        hits = scan_screen()
        if not hits:
            time.sleep(0.5)
            continue

        claim = find_text(hits, (r"领取成功",), top_claim_region())
        if claim:
            log("检测到顶部“领取成功”")
            return "ready", claim

        if find_text(hits, QUOTA_EXHAUSTED_PATTERNS):
            log("检测到当日额度已用完")
            return "quota_exhausted", None

        if handle_live_prompt(hits):
            continue
        if handle_live_room(hits):
            continue

        continue_hit = find_text(
            hits,
            (r"继续观看", r"继续看视频", r"继续看广告"),
            continue_region(),
            exclude=("下载", "直播", "互动"),
        )
        if continue_hit and click_hit(continue_hit, "继续观看广告"):
            time.sleep(1.5)
            continue

        countdown = find_text(
            hits,
            (r"\d+\s*秒后",),
            region(0.0, 0.0, 1.0, 0.22),
            exclude=("直播", "进入"),
        )
        if countdown:
            match = re.search(r"(\d+)\s*秒后", countdown["text"])
            if match:
                seconds = int(match.group(1))
                if seconds != last_countdown:
                    log("广告倒计时：%s" % countdown["text"])
                    last_countdown = seconds
                deadline = min(
                    hard_deadline,
                    max(deadline, time.monotonic() + seconds + 15),
                )

        if checks % 5 == 0 and ensure_in_app():
            continue
        time.sleep(0.6)

    log("等待广告结束超时")
    return "timeout", None


# ======================== 领取与主循环 ========================


def claim_reward(claim_hit):
    if not click_hit(claim_hit, "领取成功"):
        return "failed"

    log("等待“领取奖励”弹窗")
    deadline = time.monotonic() + CLAIM_TIMEOUT
    retried = False
    while time.monotonic() < deadline:
        hits = scan_screen()
        if find_text(hits, QUOTA_EXHAUSTED_PATTERNS):
            return "quota_exhausted"

        reward = find_text(
            hits,
            (r"领取奖励", r"立即领取", r"确认领取"),
            reward_dialog_region(),
            exclude=("领取成功",),
        )
        if reward and click_hit(reward, "领取奖励"):
            time.sleep(1)
            return "claimed"

        # 第一次点击未生效时只允许重试一次，避免重复领取或误触。
        still_claim = find_text(hits, (r"领取成功",), top_claim_region())
        if still_claim and not retried:
            retried = True
            click_hit(still_claim, "重试领取成功")
            continue
        time.sleep(0.4)

    log("未找到“领取奖励”，本轮按失败处理")
    return "failed"


def run_ad_loop():
    success_count = 0
    idle_count = 0

    for round_index in range(1, MAX_ROUNDS + 1):
        log("---- 第 %s/%s 轮 ----" % (round_index, MAX_ROUNDS))
        timeout = FIRST_AD_TIMEOUT if round_index == 1 else AD_TIMEOUT
        status, claim_hit = wait_ad_countdown(timeout)

        if status == "quota_exhausted":
            break
        if status == "ready":
            result = claim_reward(claim_hit)
            if result == "claimed":
                success_count += 1
                idle_count = 0
                log("领取成功，累计 %s 次" % success_count)
            elif result == "quota_exhausted":
                break
            else:
                idle_count += 1
        else:
            idle_count += 1
            log("连续 %s/%s 轮未检测到可领取广告" % (idle_count, MAX_IDLE_ROUNDS))

        if idle_count >= MAX_IDLE_ROUNDS:
            log("连续多轮无广告，停止脚本")
            break
        time.sleep(LOOP_INTERVAL)

    return success_count


def main():
    log("汽水音乐 Android AScript 工程启动")
    try:
        init_screen()
        Ocr.set_engine(OCR_ENGINE)
        if not launch_app():
            return
        skip_splash_ad()
        dismiss_membership_popup()
        log("请手动进入第一个广告；后续领取流程由脚本接管")
        total = run_ad_loop()
        log("脚本结束，成功领取 %s 次" % total)
    except KeyboardInterrupt:
        log("收到停止指令")
    except Exception as exc:
        log("主流程异常：%s" % exc)
        traceback.print_exc()


# AScript 工程入口必须顶层执行，不能使用 if __name__ == "__main__"。
main()
