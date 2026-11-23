# 汽水音乐 Android 自动化脚本

基于 AScript Android API 重新实现的自动化工程。功能流程参考 [wei6718189/qishuiAScriptProject](https://github.com/wei6718189/qishuiAScriptProject)，不依赖 iOS / WebDriverAgent，也不复制原项目的图片资源。

> 当前版本只完成代码移植，未进行真机验证。不同汽水音乐版本、手机分辨率及广告样式可能需要调整识别区域。

## 功能

- 通过 Android 包名 `com.luna.music` 启动汽水音乐
- OCR 识别并跳过开屏广告
- 处理底部会员续费弹窗
- 用户手动进入第一个广告后，自动等待倒计时结束
- 支持普通视频广告、继续观看弹窗和直播间退出
- 依次点击“领取成功”与“领取奖励”
- 检测当日额度提示，连续多轮无广告时停止
- 检测广告外跳并尝试切回汽水音乐

## 环境要求

- Android 手机
- 支持 `Ocr.find_all()`、`Device.display()` 的 AScript Android 版本
- AScript 已获取无障碍、屏幕识别等必要权限
- 默认使用无障碍点击通道；Root 模式可将 `SELECTOR_MODE` 改为 `9`

**不要直接用于 `screen_only` 或未配置点击实现的 HID 模式。** 这些模式不能直接使用本项目的 `action.click`，需要自行接入对应点击通道。

## 使用方法

1. 在 AScript 中新建 Android 工程。
2. 将本仓库的 `__init__.py` 放到工程根目录，保持文件名不变。
3. 确认手机已安装汽水音乐，包名为 `com.luna.music`。
4. 开启 AScript 所需权限，并在真机上运行工程。
5. 开屏处理完成后，按日志提示手动进入第一个广告；之后的领取流程由脚本接管。

AScript 工程入口由框架加载，因此文件末尾直接调用 `main()`，不能使用 `if __name__ == "__main__"`。

## 配置

修改 `__init__.py` 顶部常量：

| 配置 | 默认值 | 用途 |
| --- | --- | --- |
| `APP_PACKAGE` | `com.luna.music` | Android 汽水音乐包名 |
| `OCR_ENGINE` | `paddle` | OCR 引擎，可改为 `mlkit` |
| `SELECTOR_MODE` | `2` | 无障碍控件模式；Root 通常使用 `9` |
| `MAX_ROUNDS` | `50` | 最大广告轮数 |
| `MAX_IDLE_ROUNDS` | `5` | 连续无广告退出阈值 |
| `FIRST_AD_TIMEOUT` | `120` | 首轮等待用户进入广告的秒数 |
| `AD_TIMEOUT` | `75` | 后续单轮基础等待秒数 |
| `MAX_AD_TIMEOUT` | `120` | 识别倒计时后允许延长到的上限 |
| `CLAIM_TIMEOUT` | `15` | 等待奖励弹窗的秒数 |

## 实现说明

- 每轮优先做一次全屏 OCR，再在内存中匹配文字，减少重复截图。
- “领取成功”只匹配屏幕顶部，“领取奖励”只匹配中间弹窗区域。
- “继续观看”只匹配屏幕中间区域，并排除含“下载”“直播”“互动”的文本。
- 直播互动提示出现后只选择退出类按钮；进入直播间后依次尝试控件、OCR 和 Android 返回键。
- 所有 OCR 点击都需要当前画面命中有效文字和坐标；没有命中时不会盲点奖励按钮。

## 与原 iOS 项目的差异

| 项目 | 原 iOS 版 | 本 Android 版 |
| --- | --- | --- |
| 命名空间 | `ascript.ios.*` | `ascript.android.*` |
| App 标识 | `com.soda.music` | `com.luna.music` |
| 启动 API | `app_start()` | `system.open()` |
| 屏幕尺寸 | `screen_size()` | `Device.display()` |
| 直播间关闭 | 模板图为主 | 控件 / OCR / 返回键 |
| 图片资源 | 需要模板图 | 不需要 |

## 已知限制

- 未连接真机验证，不能承诺在你的设备上直接跑通。
- 广告页面多为动态内容，OCR 可能受字体、清晰度、分辨率和 App 版本影响。
- 本项目不绕过广告时长、次数限制、验证码或平台风控。
- 请遵守汽水音乐及广告服务的相关协议，出现额度用完提示后不要反复运行。

## 来源与许可证

功能参考：[wei6718189/qishuiAScriptProject](https://github.com/wei6718189/qishuiAScriptProject)。原仓库在参考时未提供许可证；本仓库为重新实现的 Android 代码，没有复制其图片资源，详见 [NOTICE](./NOTICE)。

本仓库新增代码采用 [MIT License](./LICENSE)。AScript、汽水音乐及相关名称和权利分别归其权利人所有。
