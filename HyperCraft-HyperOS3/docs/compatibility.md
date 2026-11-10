# HyperOS 3 适配范围

本版由 `/Users/dalong/Documents/codeWork/other/hyperos4/hyperhome` 的澎湃工坊复制，保留其工具实现与视觉样式。当前真机为 POCO F9 Ultra（songyuan / 26077PC53G），HyperOS `OS3.0.306.0.WGNMIXM`，Android 16/API 36，KernelSU 3.3.1，LSPosed API 102。

## 版本边界

- 应用目标 SDK 调整为 36，保留 SDK 37 编译。
- 已彻底删除桌面、系统界面模块、配置桥和原生库，不需要匹配 HyperOS 4 桌面二进制。
- Xposed Java 入口仅保留 `com.dalong.pengpaitool.xp.Entry`，推荐作用域仅为 `com.android.settings`。
- 关于手机美化补齐 HyperOS 3 六参数动画回调的标题透明度恢复；七参数实现沿用源项目逻辑。
- 原工坊的 Root 工具保留逐项能力检测，不将 HyperOS 4 设备的节点、更新组件或显示模式视为本机事实。

## 工具边界

循环次数与 hosts 绑定在 init 挂载命名空间执行，只撤销本模块拥有的绑定。CPU、充电与刷新率受内核节点和系统策略约束；不能仅凭界面保存成功认定硬件生效。反诈、更新、包状态及系统开关保存基线后恢复，不清空应用数据。

“我的设备美化”依赖系统设置的厂商接口，其他 ROM 需要重新验证。WLAN 页面可能显示保存的密码，调试结果不导出其内容。未在其他机型、其他 HyperOS 3 版本进行完整验证；不能沿用源项目 HyperOS 4 的真机结论。
