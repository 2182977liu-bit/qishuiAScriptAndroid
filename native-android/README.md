# 汽水自动助手（原生 Android）

原生 Kotlin 版本，不依赖 AScript、Python 或 VS Code。目标应用包名为 `com.luna.music`。

> 当前为 0.2.0 源代码修改版；编译与真机验证结果以实际构建记录为准。

## 0.2.0 修改内容

### 悬浮控制窗

- 按住顶部“⋮⋮ 汽水助手”标题栏可拖动。
- 默认最小化，只显示标题、最大化按钮和停止按钮。
- 点击“最大化”展开运行状态、领取次数和实时日志。
- 日志位于底部时自动滚动到最新内容；向上查看历史日志时不会强制拉回。
- 点击“最小化”收起日志并回到小窗口。
- 拖动时会限制在屏幕范围内，最大化前的小窗口位置会在收起后恢复。

### APK 体积优化

- 只保留 `arm64-v8a`，不再同时打包 32 位 ARM、x86 和 x86_64 原生库。
- Debug 和 Release 均启用 R8 代码压缩与资源压缩。
- 移除重复声明的 `play-services-tasks` 直接依赖，由 OCR 库传递提供。
- 提供 Offline 和 Lite 两种 OCR 构建变体。

## Offline 与 Lite 的区别

| 版本 | 中文 OCR 模型 | 优点 | 使用条件 | Debug 输出文件 |
|---|---|---|---|---|
| Offline | 随 APK 捆绑 | 安装后即可识别，不依赖 Google Play 服务 | 适合无 Google Play 服务的手机 | `app-offline-debug.apk` |
| Lite | Google Play 服务按需下载 | APK 最小 | 需要可用的 Google Play 服务；首次识别前需等待模型下载 | `app-lite-debug.apk` |

两个版本仅 OCR 模型分发方式不同，悬浮窗和自动化逻辑相同，不能据此预先承诺具体 APK 大小；需要构建后测量。

Offline 依赖：

```text
com.google.mlkit:text-recognition-chinese:16.0.1
```

Lite 依赖：

```text
com.google.android.gms:play-services-mlkit-text-recognition-chinese:16.0.2
```

Lite 的 `app/src/lite/AndroidManifest.xml` 声明了 `ocr_chinese`。通过侧载 APK 安装时，模型可能在首次识别时才开始下载；模型未就绪前 OCR 可能返回失败或空结果。没有 Google Play 服务的设备应使用 Offline 版。

官方说明：<https://developers.google.cn/ml-kit/vision/text-recognition/v2/android?hl=zh-cn>

## 构建变体（供后续执行）

```text
gradle :app:assembleOfflineDebug
gradle :app:assembleLiteDebug
```

输出目录：

```text
app/build/outputs/apk/offline/debug/app-offline-debug.apk
app/build/outputs/apk/lite/debug/app-lite-debug.apk
```

仓库根目录的 GitHub Actions 配置也已同步为一次构建两个变体，并一起上传 Artifact。本轮会由 GitHub Actions 构建。

## 兼容性说明

- 当前仅支持 arm64 手机；不支持 32 位 ARM、x86/x86_64 模拟器。若需这些架构，要在 `app/build.gradle.kts` 中重新添加 ABI。
- minSdk 26，即 Android 8.0 及以上。
- 无障碍、屏幕录制和悬浮窗权限仍需用户手动授权。
- R8 已启用，但混淆后的实际兼容性与 ML Kit 识别效果仍须真机验证。
- 不快进广告、不修改倒计时、不绕过平台限制。
