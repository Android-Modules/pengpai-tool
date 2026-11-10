# 澎湃工坊 · HyperOS 3

基于本机 `hyperos4/hyperhome` 的澎湃工坊完整源码复制，保留原有首页、卡片、配色与工具界面，适配 HyperOS 3。新版位于独立子目录，不修改源项目。

当前版本 **0.3.0-hyperos3.1**（38），包名 `io.github.dalong.hypercraft`。按用户要求，已删除「桌面」「系统界面」全部功能、配置桥、Java/原生 Hook、菜单、搜索入口及对应作用域，同时移除依赖 SystemUI Hook 的小横条显示与沉浸。仅保留设置作用域，用于“我的设备美化”。

## 保留功能

| 分组 | 页面 |
| --- | --- |
| 系统精简 | 系统精简、去广告、广告设置、WLAN 日志清理、反诈与上报管理、屏蔽系统更新 |
| 显示与锁屏 | 全局高刷、字体大小、系统图标隐藏、色彩风格、竖屏锁定 |
| 电池与性能 | 电池健康度、电池循环伪装、充电控制、充电记录、系统温度墙、电池优化白名单、处理器调整、应用深度优化、远程进程守卫 |
| 系统工具 | 机型修改、我的设备美化、开发者选项、无线调试诊断、账号同步、WLAN 密码、隐藏设置入口 |
| 日志与诊断 | 备份与模块设置、运行日志、关于澎湃工坊 |

五组共 30 个页面。首页搜索与菜单共用同一目录，避免已删除功能残留入口。这里的系统图标工具使用原生设置键，未包含已删除的状态栏 Hook。

## 安装与权限

安装 APK 后，首次进入 Root 工具点击「授权并准备工具」，在 KernelSU 等管理器授权。Root 工具可独立运行。“我的设备美化”另外需要支持 libxposed API 102 的 LSPosed，启用模块并仅勾选「设置」`com.android.settings`；启用或更新后重新打开系统设置，必要时重启手机。

后端独立使用 `/data/adb/hypercraft/runtime` 和 `/data/adb/hypercraft/state`，开机入口为 `post-fs-data.d/hypercraft.sh`、`service.d/hypercraft.sh`。升级 APK 保留配置，不自动导入旧模块配置。充电控制、充电记录、设备美化及米兔默认关闭；页面按设备实际能力显示可用状态。

## 构建与验证

需要 JDK 21、Android SDK 37；目标 Android 16/API 36，最低 API 35。已移除桌面 C++ 构建与 NDK 依赖。

```sh
bash "./gradlew" :app:assembleRelease :app:testDebugUnitTest :streamline:testDebugUnitTest :app:lintRelease :streamline:lintRelease
python3 -m unittest discover -s "streamline/tests" -v
```

输出为 `app/build/outputs/apk/release/app-release.apk`。当前 release 使用本机构建环境的 Android debug 开发签名，正式分发需另配签名。兼容范围见[适配说明](docs/compatibility.md)，本次测试与限制见[验证记录](docs/testing.md)。

## 源码组织

- `app`：澎湃工坊首页、搜索、框架连接与全局诊断。
- `streamline`：原工坊工具页面、Root 后端、规则、机型预设及设备美化。
- `streamline/tests`：在临时目录验证生产脚本的主机测试。
- `app/src/debug`、`streamline/src/debug`：仅调试构建包含的真机探针；release 不包含。

保留源码许可证及上游声明；不分发厂商 APK、设备提取库、私人日志和本机签名。
