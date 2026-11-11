# 澎湃工坊 · HyperOS 3

澎湃工坊是一款面向 HyperOS 3 的系统工具 App，沿用原工坊的首页、卡片、配色和工具界面。从首页可以浏览五组工具，也可以直接按名称搜索功能。

当前版本 **0.3.0-hyperos3.1**（38），包名 `io.github.dalong.hypercraft`。HyperOS 3 版已移除「桌面」「系统界面」两组功能及其 Hook，LSPosed 作用域只保留「设置」，供「我的设备美化」使用。

## 真机截图

以下界面截自运行 HyperOS `OS3.0.306.0.WGNMIXM` 的 POCO F9 Ultra。

| 首页 | 系统精简 | 显示与锁屏 | 电池与性能 |
| --- | --- | --- | --- |
| <img src="docs/screenshots/home.png" width="220" alt="澎湃工坊首页和搜索入口"> | <img src="docs/screenshots/system-cleanup.png" width="220" alt="系统精简工具列表"> | <img src="docs/screenshots/display.png" width="220" alt="显示与锁屏工具列表"> | <img src="docs/screenshots/battery.png" width="220" alt="电池与性能工具列表"> |

## 功能一览

| 分组 | 页面 |
| --- | --- |
| 系统精简 | 系统精简、去广告、广告设置、WLAN 日志清理、反诈与上报管理、屏蔽系统更新 |
| 显示与锁屏 | 全局高刷、字体大小、系统图标隐藏、色彩风格、竖屏锁定 |
| 电池与性能 | 电池健康度、电池循环伪装、充电控制、充电记录、系统温度墙、电池优化白名单、处理器调整、应用深度优化、远程进程守卫 |
| 系统工具 | 机型修改、我的设备美化、开发者选项、无线调试诊断、账号同步、WLAN 密码、隐藏设置入口 |
| 日志与诊断 | 备份与模块设置、运行日志、关于澎湃工坊 |

**精简和恢复。** 可从推荐清单或自定义列表选择系统组件。冻结后可解冻，按用户卸载前会备份 APK。已处理的应用可在「系统精简」里查看和恢复。备份目录和开机保持选项位于「备份与模块设置」。

**显示与硬件控制。** 全局高刷列出设备支持的模式，系统图标隐藏使用 HyperOS 原生设置。充电控制、系统温度墙、循环次数和处理器调整根据本机硬件及内核能力显示可用项。充电控制和充电记录默认关闭，开启后可在「充电记录」查看会话数据。

**诊断。** 「运行日志」汇总模块与操作记录。设备或 ROM 支持情况可查阅[适配说明](docs/compatibility.md)和[真机验证记录](docs/testing.md)。

## 安装与权限

1. 在 [GitHub Releases](https://github.com/Android-Modules/pengpai-tool/releases) 选择对应版本，下载 `HyperCraft-HyperOS3-*.apk`，用手机的安装器打开。每个发布版本同时提供 `.sha256` 校验文件。
2. 打开澎湃工坊，进入任一 Root 工具页。若出现「授权并准备工具」，点击后在 KernelSU 等 Root 管理器中授权。
3. 如需「我的设备美化」，在支持 libxposed API 102 的 LSPosed 中启用澎湃工坊，勾选「设置」`com.android.settings`，然后重新打开系统设置。其他 Root 工具可独立使用。

本版在 POCO F9 Ultra（`songyuan`）、HyperOS 3 / Android 16、KernelSU 3.3.1 与 LSPosed API 102 环境下验证。其他机型的刷新率、充电节点及厂商接口以页面检测结果为准。旧版澎湃小工具的配置需要手动重新设置，本版数据独立保存在 `/data/adb/hypercraft/state`。

Releases 中的 APK 使用 CI 生成的 Android 开发签名。Android 要求覆盖安装的两个版本使用相同签名，已安装的本地构建版或其他 CI 构建版可能需要卸载后才能安装。替换前可在「备份与模块设置」中备份。

## 构建与验证

进入 `HyperCraft-HyperOS3` 后，使用 JDK 21 和 Android SDK 37 构建。目标 API 36，最低 API 35。

```sh
bash "./gradlew" :app:assembleRelease :app:testDebugUnitTest :streamline:testDebugUnitTest :app:lintRelease :streamline:lintRelease
python3 -m unittest discover -s "streamline/tests" -v
```

输出文件为 `app/build/outputs/apk/release/app-release.apk`。推送 `v<版本号>` 形式的 HyperOS 3 版本标签后，GitHub Actions 会构建 APK、运行测试、校验签名和版本号，并将 APK 和 SHA-256 校验文件发布到 Releases。标签去掉 `v` 后应与 `app/build.gradle.kts` 的 `versionName` 一致，例如 `v0.3.0-hyperos3.1`。

## 源码组织

- `app`：澎湃工坊首页、搜索、框架连接与全局诊断。
- `streamline`：原工坊工具页面、Root 后端、规则、机型预设及设备美化。
- `streamline/tests`：在临时目录验证生产脚本的主机测试。
- `app/src/debug`、`streamline/src/debug`：仅调试构建包含的真机探针；release 不包含。

保留源码许可证及上游声明；不分发厂商 APK、设备提取库、私人日志和本机签名。
