package com.dalong.pengpaitool;

import java.util.List;
import java.util.stream.Collectors;

/** Shared route metadata for host menus, search and internal tool pages; performs no IO. */
public final class RouteCatalog {
    private RouteCatalog() {}
    public static final class Group {
        private final String groupId, title, subtitle;
        private Group(String groupId, String title, String subtitle) { this.groupId=groupId; this.title=title; this.subtitle=subtitle; }
        public String getGroupId() { return groupId; }
        public String getRoute() { return groupId; }
        public String getTitle() { return title; }
        public String getSubtitle() { return subtitle; }
    }
    public static final class Entry {
        private final String route, title, groupId, subtitle;
        private final boolean requiresRoot;
        private Entry(String route, String title, String groupId, String subtitle, boolean requiresRoot) {
            this.route=route; this.title=title; this.groupId=groupId; this.subtitle=subtitle; this.requiresRoot=requiresRoot;
        }
        public String getRoute() { return route; }
        public String getTitle() { return title; }
        public String getGroupId() { return groupId; }
        public String getSubtitle() { return subtitle; }
        public boolean isRequiresRoot() { return requiresRoot; }
    }
    private static final List<Group> GROUPS = List.of(
        new Group("slim_group", "系统精简", "组件管理、去广告、日志清理与更新"),
        new Group("display", "显示与锁屏", "刷新率、字体、色彩与方向"),
        new Group("performance", "电池与性能", "充电控制、电池状态与处理器"),
        new Group("tools", "系统工具", "设备美化、调试、同步与设置入口"),
        new Group("records", "日志与诊断", "精简与操作行为日志、充电记录及备份"));
    private static final List<Entry> ENTRIES = List.of(
        new Entry("slim", "系统精简", "slim_group", "推荐与自定义精简、冻结、卸载、APK 备份及恢复", true),
        new Entry("ad", "去广告", "slim_group", "hosts 广告与统计域名拦截、推荐开关", true),
        new Entry("adjump", "广告设置", "slim_group", "系统应用广告和推荐设置入口", false),
        new Entry("wlanlog", "WLAN 日志清理", "slim_group", "调试日志清理与云控抓包管理", true),
        new Entry("antifraud", "反诈与上报管理", "slim_group", "SystemHelper、系统安全组件、应用列表上报、云端反诈与恢复", true),
        new Entry("ota", "屏蔽系统更新", "slim_group", "更新检查、通知与角标", true),
        new Entry("fps", "全局高刷", "display", "智能、强制及省电强制刷新率", true),
        new Entry("font", "字体大小", "display", "系统字体缩放", true),
        new Entry("icons", "系统图标隐藏", "display", "系统状态栏图标与通知图标设置", true),
        new Entry("color", "色彩风格", "display", "完整色彩选项", true),
        new Entry("rotlock", "竖屏锁定", "display", "保持竖屏与自动旋转设置", true),
        new Entry("battery", "电池健康度", "performance", "电池容量及健康状态", true),
        new Entry("cycle", "电池循环伪装", "performance", "系统显示的电池循环次数", true),
        new Entry("charge", "充电控制", "performance", "温控、阶梯限流、充电上限与过热停充", true),
        new Entry("chargelog", "充电记录", "performance", "充电曲线与会话详情", true),
        new Entry("thermalwall", "系统温度墙", "performance", "温控策略及驱动支持状态", true),
        new Entry("whitelist", "电池优化白名单", "performance", "应用电池优化管理", true),
        new Entry("cpu", "处理器调整", "performance", "处理器频率与性能限制", true),
        new Entry("dexopt", "应用深度优化", "performance", "应用编译与优化", true),
        new Entry("guard", "远程进程守卫", "performance", "安全中心远程进程负载管理", true),
        new Entry("devmodel", "机型修改", "tools", "设备机型属性和预设", true),
        new Entry("mydevice", "我的设备美化", "tools", "关于手机主题、自定义图片与米兔 · 需要 LSPosed 设置作用域", false),
        new Entry("dev", "开发者选项", "tools", "USB 调试、无线调试与快捷磁贴", true),
        new Entry("adbdiag", "无线调试诊断", "tools", "配对、连接与诊断记录", true),
        new Entry("sync", "账号同步", "tools", "同步开关及开机保持", true),
        new Entry("wifi", "WLAN 密码", "tools", "按需读取已保存的 WLAN 网络", true),
        new Entry("shortcuts", "隐藏设置入口", "tools", "原生设置快捷入口", false),
        new Entry("backup", "备份与模块设置", "records", "备份目录、记录导入、开机保持和卸载恢复", true),
        new Entry("modlog", "运行日志", "records", "操作记录与开机检查", true),
        new Entry("about", "关于澎湃工坊", "records", "项目介绍、版本、包名与开源仓库 HyperCraft", false));
    public static List<Group> groups() { return GROUPS; }
    public static List<Entry> entries() { return ENTRIES; }
    public static List<Entry> entriesFor(String groupId) { return ENTRIES.stream().filter(e -> e.groupId.equals(groupId)).collect(Collectors.toList()); }
    public static Entry entry(String route) { return ENTRIES.stream().filter(e -> e.route.equals(route)).findFirst().orElse(null); }
    public static String title(String route) {
        if ("home".equals(route)) return "系统工具";
        if (route != null && route.startsWith("chargelog:")) return "充电详情";
        Entry item=entry(route); if (item != null) return item.title;
        return GROUPS.stream().filter(g -> g.groupId.equals(route)).map(g -> g.title).findFirst().orElse("系统工具");
    }
    public static String validRoute(String route) {
        return "home".equals(route) || entry(route) != null || GROUPS.stream().anyMatch(g -> g.groupId.equals(route)) ? route : "home";
    }
    public static boolean requiresRoot(String route) {
        Entry item=entry(route); return route != null && route.startsWith("chargelog:") || item != null && item.requiresRoot;
    }
}
