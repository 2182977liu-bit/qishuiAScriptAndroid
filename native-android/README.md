# 汽水自动助手（原生 Android）

这是 `qishuiAScriptAndroid` 的原生 Kotlin 改造版。目标是在不安装 AScript、Python 或 VS Code 的情况下，将自动化能力打包为独立 APK。

> 当前交付为源代码，尚未安装或进行真机验证。

## 已实现

- 首次使用权限引导：无障碍、屏幕识别、悬浮窗
- Android 13+ 通知权限请求
- 前台服务运行状态与通知栏停止入口
- 基于 `AccessibilityService` 的坐标手势、返回键和关闭控件查找
- 基于 `MediaProjection` 的屏幕采集
- 基于 ML Kit 中文识别的本地 OCR
- 悬浮窗显示运行状态、领取次数和停止按钮
- 保留原脚本的广告状态机：启动汽水音乐、开屏跳过、等待倒计时、直播退出、两步领取、外跳恢复和额度检测

## 不包含

- 不包含 AScript 或其运行库
- 不需要 Python 环境
- 不包含汽水音乐安装包
- 不快进广告，不修改倒计时，不绕过平台限制

## GitHub Actions 云端构建

仓库根目录的 `.github/workflows/android-build.yml` 会在本目录发生变化时自动构建 Debug APK，也支持在 Actions 页面手动运行。

构建环境为 Ubuntu、JDK 17、Gradle 8.9 和 Android SDK 35。成功后，在该次 workflow run 的 Artifacts 区域下载 `QishuiAutomationApp-debug`，其中包含 `app-debug.apk`。

Debug APK 使用 Android 构建工具自动生成的调试签名，仅用于测试，不等同于正式发布签名。

## 本地构建

1. 使用 Android Studio 打开本目录。
2. 准备 JDK 17、Android SDK 35 和 Gradle 8.9。
3. 执行 `gradle :app:assembleDebug`，或由 Android Studio 构建。
4. APK 输出到 `app/build/outputs/apk/debug/app-debug.apk`。

## 用户使用流程

1. 安装 APK，并确认手机已安装汽水音乐。
2. 打开“汽水自动助手”。
3. 依次开启无障碍、授权屏幕识别、开启悬浮控制。
4. 回到应用，点击“开始自动化”。
5. 日志提示后手动进入第一个广告，后续由状态机接管。
6. 可通过悬浮窗或通知栏停止。

## 权限与系统限制

- 无障碍、录屏和悬浮窗必须由用户手动授权，应用不能静默开启。
- 部分新 Android 版本的录屏授权只能用于一次 MediaProjection 会话，重新运行前可能需要再次授权。
- 后台保活受手机品牌、系统版本和电池策略影响。
- 当前屏幕采集按启动时的竖屏尺寸创建，横竖屏切换后需要重新启动自动化。

## 真机验证重点

- ML Kit 对“领取成功”“领取奖励”“继续观看”等文字的识别准确率
- 汽水音乐当前版本的按钮位置与广告样式
- realme、小米、华为等系统的无障碍手势兼容性
- 录屏授权撤销、应用外跳和屏幕关闭后的恢复行为
- 多次启动/停止后 MediaProjection 与悬浮窗资源是否正确释放
