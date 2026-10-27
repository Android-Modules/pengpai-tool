package com.dalong.pengpaitool

enum class Level(val label: String) { REC("推荐"), OPT("可选"), WARN("谨慎") }

data class Item(val pkg: String, val name: String, val level: Level, val desc: String)

data class Category(val title: String, val desc: String, val items: List<Item>)

// 已核对 REDMI K90 / OS3.0.306 上的实际包名与中文名。
// 小米服务框架、互联、投屏、小爱等在 adclean.sh 的 PROTECTED 中，脚本层面拒绝处理。
private fun rec(p: String, n: String, d: String) = Item(p, n, Level.REC, d)
private fun opt(p: String, n: String, d: String = "") = Item(p, n, Level.OPT, d)
private fun warn(p: String, n: String, d: String) = Item(p, n, Level.WARN, d)

val CATALOG = listOf(
    Category(
        "系统广告", "广告下发、推荐、跳转来源", listOf(
            rec("com.miui.systemAdSolution", "智能服务 (MSA)", "系统广告核心，负责开屏广告和系统应用内的推荐广告"),
            rec("com.miui.analytics", "Analytics", "广告 SDK 配置拉取与数据上报"),
            rec("com.xiaomi.minigame", "小游戏服务", "小游戏推广入口"),
            rec("com.miui.hybrid", "快应用服务框架", "广告经常通过快应用弹出；冻结后快应用打不开"),
            opt("com.android.adservices.api", "Android 广告服务", "Google Privacy Sandbox 广告接口，国行基本用不到"),
            opt("com.android.ondevicepersonalization.services", "设备端个性化服务", "Google 广告个性化组件"),
            opt("com.android.federatedcompute.services", "联合计算服务", "Google 广告个性化配套组件"),
            warn("com.xiaomi.aireco", "小爱建议", "桌面/负一屏的智能推荐卡片。小爱语音不依赖它，但「小爱建议」卡片会失效"),
            warn("com.miui.personalassistant", "智能助理 (负一屏)", "负一屏的资讯和推荐；冻结后负一屏不可用"),
            warn("com.android.quicksearchbox", "搜索", "有热搜和推荐广告；冻结后桌面搜索框和下拉搜索不可用"),
            warn("com.android.browser", "小米浏览器", "有资讯流广告；冻结前先把默认浏览器换成别的"),
            warn("com.xiaomi.market", "应用商店", "会推推广通知；冻结后无法更新系统应用，安装时也没有安全检测"),
            warn("com.miui.yellowpage", "生活黄页", "有服务推荐；冻结后来电号码识别和骚扰标记会失效"),
        )
    ),
    Category(
        "统计与日志上报", "后台收集数据上传，冻结后不影响使用", listOf(
            rec("com.miui.daemon", "系统质量服务", "后台收集性能/异常数据上报"),
            rec("com.miui.misightservice", "系统质量监测", "MiSight 故障与使用数据上报"),
            rec("com.miui.uireporter", "UI 信息工具", "UI 异常上报"),
            rec("com.xiaomi.security.onetrack", "SecurityOnetrack", "安全中心的 OneTrack 统计上报"),
            rec("com.bsp.catchlog", "CatchLog", "系统日志抓取"),
            rec("com.xiaomi.ab", "售后支持", "售后诊断数据收集"),
            opt("com.miui.bugreport", "用户反馈", "冻结后无法提交反馈"),
            warn("com.miui.powerinsight", "PowerInsight", "功耗统计；电池页的部分耗电分析可能会缺失"),
        )
    ),
    Category(
        "按需可选", "与广告无关，用不到再冻结", listOf(
            opt("com.miui.thirdappassistant", "智能应用助手", "第三方应用的兼容辅助"),
            opt("com.xiaomi.barrage", "弹幕通知", "游戏/视频全屏时的弹幕式通知"),
            opt("com.miui.contentextension", "传送门", "长按识别内容（会推荐服务）"),
            opt("com.xiaomi.mis", "小米汽车互联服务", "不用小米汽车可以冻结"),
            opt("com.xiaomi.mtb", "鲁班 (MTB)", "基带测试工具"),
            warn("com.miui.contentcatcher", "应用扩展服务", "传送门/识屏会用到"),
            warn("com.xiaomi.joyose", "Joyose", "云控温控和游戏帧率策略，不建议动，可能影响性能调度"),
            warn("com.xiaomi.gamecenter.sdk.service", "游戏服务 SDK", "小米渠道 (.mi) 游戏的登录和支付依赖它，你装了好几款 .mi 游戏"),
        )
    ),
    // 快应用、浏览器已在「系统广告」里；系统更新不能整包冻结，用「屏蔽系统更新」处理。
    Category(
        "预装应用", "小米自家和第三方预装的应用", listOf(
            rec("com.xiaomi.shop", "小米商城", "商城推广和活动通知"),
            rec("com.xiaomi.youpin", "小米有品", "电商应用"),
            rec("com.xiaomi.vipaccount", "小米社区", "论坛和内测报名入口"),
            rec("com.miui.newhome", "内容中心", "资讯信息流"),
            rec("com.mfashiongallery.emag", "小米画报", "锁屏画报，夹带推广内容；冻结后锁屏壁纸不再自动轮换"),
            rec("com.xiaomi.tinygame", "小游戏", "小游戏推广入口"),
            rec("com.duokan.reader", "多看", "电子书阅读"),
            rec("com.baidu.input_mi", "百度输入法小米版", "冻结前确认默认输入法不是它"),
            rec("com.iflytek.inputmethod.miui", "讯飞输入法小米版", "冻结前确认默认输入法不是它"),
            rec("com.miui.virtualsim", "全球上网", "出境流量购买"),
            rec("com.miui.huanji", "小米换机", "换机完成后就用不到了"),
            opt("com.xiaomi.gamecenter", "小米游戏中心", ".mi 游戏的下载和更新入口；游戏登录支付靠的是游戏服务 SDK，不受影响"),
            opt("com.miui.video", "小米视频", "系统视频播放器；冻结后文件管理里打开视频需要选别的播放器"),
            opt("com.miui.player", "音乐", "系统音乐播放器"),
            opt("com.android.email", "电子邮件", "系统邮件客户端"),
            opt("com.miui.compass", "指南针", "指南针和水平仪"),
            opt("com.duokan.phone.remotecontroller", "万能遥控", "红外遥控家电"),
            opt("com.miui.newmidrive", "小米云盘", "小米云服务的网盘"),
            opt("cn.wps.moffice_eng.xiaomi.lite", "小米文档查看器", "WPS 定制版；冻结后打开 Office/PDF 文档需要装别的应用"),
            opt("com.miui.notes", "笔记", "冻结不会删除笔记数据，但冻结期间打不开"),
            opt("com.xiaomi.smarthome", "米家", "不用米家设备可以冻结"),
            opt("com.mi.health", "小米运动健康", "连接小米手环/手表需要它"),
            opt("com.android.emergency", "急救信息", "锁屏紧急呼叫页里的急救信息"),
            opt("com.android.providers.userdictionary", "用户字典", "输入法的自定义词库存储，主流输入法都用自己的词库"),
            warn("com.mipay.wallet", "钱包", "NFC 交通卡、门卡、银行卡都在这里，用了就不要动"),
            warn("com.xiaomi.payment", "米币支付", "主题商店、小米游戏等内购付款依赖它"),
            warn("com.xiaomi.scanner", "AI 扫描", "控制中心「扫一扫」、文档扫描依赖它"),
            warn("com.miui.mediaeditor", "小米相册-编辑", "相册里的编辑、裁剪、AI 消除都在这里，冻结后相册不能编辑"),
            warn("com.hyper.gallery.plugin", "相册扩展插件", "相册的部分 AI 功能"),
        )
    ),
    Category(
        "原生冗余", "Android / Google 附带组件", listOf(
            opt("com.android.egg", "安卓彩蛋"),
            opt("com.android.traceur", "系统跟踪", "开发者选项里的系统跟踪"),
            opt("com.android.bips", "默认打印服务", "不用系统打印可以冻结"),
            opt("com.google.android.printservice.recommendation", "打印服务推荐"),
            opt("com.google.android.gms.location.history", "Google 位置记录"),
            opt("com.google.android.onetimeinitializer", "Google 一次性初始化"),
        )
    ),
)

/** 清单里的中文名优先；清单外的应用用系统里的应用名，查不到就显示包名 */
fun labelOf(pkg: String, pm: android.content.pm.PackageManager? = null): String =
    CATALOG.asSequence().flatMap { it.items }.firstOrNull { it.pkg == pkg }?.name
        ?: pm?.let { runCatching { it.getApplicationLabel(it.getApplicationInfo(pkg, android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS or android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES)).toString() }.getOrNull() }
        ?: pkg
