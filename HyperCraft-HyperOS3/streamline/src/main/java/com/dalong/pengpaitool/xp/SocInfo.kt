package com.dalong.pengpaitool.xp

import android.app.ActivityManager
import android.content.Context
import android.os.Environment
import android.os.StatFs
import java.io.File

/** 处理器的一组核心（同一个 cpufreq policy） */
class Cluster(val cpus: List<Int>, val maxKhz: Long)

/** 处理器静态规格：核心分组从 cpufreq 读取，名称和制程查内置表 */
class SocInfo(
    val model: String,
    val brand: Brand,
    val name: String,
    val node: String,
    val clusters: List<Cluster>,
    val gpu: String,
    val npu: String,
    val modem: String,
    val gpuMaxMhz: Int,
    val ramGb: Int,
) {
    enum class Brand { QUALCOMM, MEDIATEK, XRING, OTHER }

    val cores get() = clusters.sumOf { it.cpus.size }

    /** 「2×4.61 + 6×3.63」 */
    val coreSpec get() = clusters.joinToString(" + ") { "${it.cpus.size}×${ghz(it.maxKhz)}" }

    /** 芯片上印的品牌：Snapdragon / 骁龙 */
    val brandEn get() = when (brand) {
        Brand.QUALCOMM -> "Snapdragon"
        Brand.MEDIATEK -> "Dimensity"
        Brand.XRING -> "XRING"
        Brand.OTHER -> ""
    }
    val brandCn get() = when (brand) {
        Brand.QUALCOMM -> "骁龙"
        Brand.MEDIATEK -> "天玑"
        Brand.XRING -> "玄戒"
        Brand.OTHER -> ""
    }

    /** 名称去掉品牌后拆成型号数字和后缀：骁龙 8 至尊版 Gen 5 → 8 / 至尊版 Gen 5；天玑 9400 → 9400 / 空 */
    val series: String get() = rest().substringBefore(' ')
    val suffix: String get() = rest().substringAfter(' ', "").trim()

    private fun rest() = (if (brandCn.isNotEmpty()) name.removePrefix(brandCn) else name).trim().ifBlank { model }

    /** 没有官方 Logo 时画的徽标字母 */
    val monogram get() = (brandEn.ifBlank { name }).firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "S"

    companion object {
        private class Known(val name: String, val node: String, val gpu: String? = null)

        private val TABLE = mapOf(
            "SM8850" to Known("骁龙 8 至尊版 Gen 5", "3nm", "Adreno 840"),
            "SM8845" to Known("骁龙 8 Gen 5", "3nm"),
            "SM8750" to Known("骁龙 8 至尊版", "3nm", "Adreno 830"),
            "SM8735" to Known("骁龙 8s Gen 4", "4nm", "Adreno 825"),
            "SM8650" to Known("骁龙 8 Gen 3", "4nm", "Adreno 750"),
            "SM8635" to Known("骁龙 8s Gen 3", "4nm", "Adreno 735"),
            "SM8550" to Known("骁龙 8 Gen 2", "4nm", "Adreno 740"),
            "SM8475" to Known("骁龙 8+ Gen 1", "4nm", "Adreno 730"),
            "SM8450" to Known("骁龙 8 Gen 1", "4nm", "Adreno 730"),
            "SM7750" to Known("骁龙 7+ Gen 4", "4nm"),
            "SM7675" to Known("骁龙 7+ Gen 3", "4nm", "Adreno 732"),
            "SM7635" to Known("骁龙 7s Gen 3", "4nm", "Adreno 710"),
            "SM7550" to Known("骁龙 7 Gen 3", "4nm", "Adreno 720"),
            "SM7475" to Known("骁龙 7+ Gen 2", "4nm", "Adreno 725"),
            "SM7435" to Known("骁龙 7s Gen 2", "4nm", "Adreno 710"),
            "SM6475" to Known("骁龙 6 Gen 3", "4nm", "Adreno 710"),
            "SM6450" to Known("骁龙 6 Gen 1", "4nm", "Adreno 710"),
            "SM6375" to Known("骁龙 695", "6nm", "Adreno 619"),
            "SM4450" to Known("骁龙 4 Gen 2", "4nm", "Adreno 613"),
            "MT6993" to Known("天玑 9500", "3nm", "Mali-G1 Ultra"),
            "MT6991" to Known("天玑 9400", "3nm", "Immortalis-G925"),
            "MT6989" to Known("天玑 9300", "4nm", "Immortalis-G720"),
            "MT6899" to Known("天玑 8400", "4nm", "Mali-G720"),
            "MT6897" to Known("天玑 8300", "4nm", "Mali-G615"),
            "MT6896" to Known("天玑 8200", "4nm", "Mali-G610"),
            "MT6895" to Known("天玑 8100", "5nm", "Mali-G610"),
            "MT6985" to Known("天玑 9200", "4nm", "Immortalis-G715"),
            "MT6983" to Known("天玑 9000", "4nm", "Mali-G710"),
            "MT6878" to Known("天玑 7300", "4nm", "Mali-G615"),
            "MT6886" to Known("天玑 7200", "4nm", "Mali-G610"),
            "XRING O1" to Known("玄戒 O1", "3nm", "Immortalis-G925"),
        )

        @Volatile private var cached: SocInfo? = null

        fun get(ctx: Context): SocInfo = cached ?: load(ctx).also { cached = it }

        /** 预览用的典型处理器：型号 to 各簇（核心数, 最高 MHz） */
        val DEMOS = listOf(
            "SM8850" to listOf(2 to 4610, 6 to 3630),
            "SM8650" to listOf(1 to 3300, 3 to 3150, 2 to 2960, 2 to 2270),
            "MT6991" to listOf(1 to 3630, 3 to 3300, 4 to 2400),
            "XRING O1" to listOf(2 to 3900, 4 to 3400, 2 to 1890, 2 to 1800),
            "SM7675" to listOf(1 to 2800, 4 to 2600, 3 to 1900),
        )

        fun demo(ctx: Context, model: String): SocInfo {
            val real = get(ctx)
            if (model == real.model) return real
            val spec = DEMOS.firstOrNull { it.first == model }?.second ?: return real
            val known = TABLE[model]
            val brand = when {
                model.startsWith("SM") -> Brand.QUALCOMM
                model.startsWith("MT") -> Brand.MEDIATEK
                model.contains("XRING") -> Brand.XRING
                else -> Brand.OTHER
            }
            var cpu = 0
            val clusters = spec.map { (n, mhz) -> Cluster((cpu until cpu + n).toList(), mhz * 1000L).also { cpu += n } }
            return SocInfo(
                model, brand, known?.name ?: model, known?.node.orEmpty(), clusters, known?.gpu ?: "GPU",
                if (brand == Brand.QUALCOMM) "Hexagon" else "NPU", "5G", 0, real.ramGb,
            )
        }

        private fun load(ctx: Context): SocInfo {
            val model = prop("ro.soc.model").ifBlank { prop("ro.board.platform") }.uppercase()
            val maker = prop("ro.soc.manufacturer").uppercase()
            val brand = when {
                maker.contains("QTI") || maker.contains("QUALCOMM") || model.startsWith("SM") -> Brand.QUALCOMM
                maker.contains("MEDIATEK") || model.startsWith("MT") -> Brand.MEDIATEK
                model.contains("XRING") -> Brand.XRING
                else -> Brand.OTHER
            }
            val known = TABLE[model]
            val kgslModel = read("/sys/class/kgsl/kgsl-3d0/gpu_model")
            val gpu = known?.gpu
                ?: Regex("Adreno(\\d{3})").find(kgslModel)?.let { "Adreno ${it.groupValues[1]}" }
                ?: if (brand == Brand.MEDIATEK) "Mali GPU" else "GPU"
            val ram = (ctx.getSystemService(ActivityManager::class.java)?.let { am ->
                ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.totalMem
            } ?: 0L)
            return SocInfo(
                model = model.ifBlank { "SoC" },
                brand = brand,
                name = known?.name ?: model.ifBlank { "处理器" },
                node = known?.node.orEmpty(),
                clusters = clusters(),
                gpu = gpu,
                npu = when (brand) { Brand.QUALCOMM -> "Hexagon"; Brand.MEDIATEK -> "APU"; else -> "NPU" },
                modem = if (brand == Brand.QUALCOMM || brand == Brand.MEDIATEK) "5G" else "Modem",
                gpuMaxMhz = read("/sys/class/kgsl/kgsl-3d0/max_clock_mhz").toIntOrNull() ?: 0,
                ramGb = marketSize(ram),
            )
        }

        private fun clusters(): List<Cluster> {
            val dir = File("/sys/devices/system/cpu/cpufreq")
            val list = dir.listFiles { f -> f.name.startsWith("policy") }.orEmpty().mapNotNull { p ->
                val cpus = read("${p.path}/related_cpus").split(' ').mapNotNull { it.toIntOrNull() }
                val max = read("${p.path}/cpuinfo_max_freq").toLongOrNull() ?: return@mapNotNull null
                if (cpus.isEmpty()) null else Cluster(cpus, max)
            }
            if (list.isNotEmpty()) return list.sortedByDescending { it.maxKhz }
            // 读不到 policy 时按核心数退化成一组
            val n = Runtime.getRuntime().availableProcessors()
            return listOf(Cluster((0 until n).toList(), 0))
        }

        /** 把实际容量换算成标称容量：15.4GB 内存 → 16，488GB 存储 → 512 */
        fun marketSize(bytes: Long): Int {
            if (bytes <= 0) return 0
            val gb = bytes / 1e9
            var s = 1
            while (s < gb) s *= 2
            return s
        }

        fun ghz(khz: Long) = if (khz <= 0) "-" else "%.2f".format(khz / 1e6)

        fun read(path: String): String = runCatching { File(path).readText().trim() }.getOrDefault("")

        private fun prop(key: String): String = runCatching {
            Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, key) as String
        }.getOrDefault("")
    }
}

/** 一次实时采样，读不到的项为 null，界面上自动隐藏 */
class SocLive(
    val curKhz: LongArray,
    val cpuTemp: Float?,
    val gpuBusy: Int?,
    val gpuMhz: Int?,
    val ramUsedPct: Int?,
    val storageUsedGb: Int?,
    val storageTotalGb: Int?,
) {
    companion object {
        private var cpuZones: List<String>? = null

        fun sample(ctx: Context, soc: SocInfo): SocLive {
            val n = soc.clusters.maxOf { c -> c.cpus.maxOrNull() ?: 0 } + 1
            val cur = LongArray(n) { i ->
                SocInfo.read("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq").toLongOrNull() ?: 0L
            }
            val temps = zones().mapNotNull { SocInfo.read(it).toFloatOrNull() }.filter { it > 0 }
            val cpuTemp = temps.maxOrNull()?.let { if (it > 1000) it / 1000f else it }
            val busy = SocInfo.read("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage").substringBefore('%').trim().toIntOrNull()
            val gmhz = SocInfo.read("/sys/class/kgsl/kgsl-3d0/clock_mhz").toIntOrNull()
            val ram = ctx.getSystemService(ActivityManager::class.java)?.let { am ->
                val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
                if (mi.totalMem > 0) ((mi.totalMem - mi.availMem) * 100 / mi.totalMem).toInt() else null
            }
            val st = runCatching { StatFs(Environment.getDataDirectory().path) }.getOrNull()
            val total = st?.let { SocInfo.marketSize(it.totalBytes) }
            val used = st?.let { ((it.totalBytes - it.availableBytes) / 1e9).toInt() }
            return SocLive(cur, cpuTemp, busy, gmhz, ram, used, total)
        }

        /** CPU 温度传感器：type 以 cpu- 开头的 thermal zone，只扫描一次 */
        private fun zones(): List<String> = cpuZones ?: run {
            File("/sys/class/thermal").listFiles { f -> f.name.startsWith("thermal_zone") }.orEmpty()
                .filter { SocInfo.read("${it.path}/type").let { t -> t.startsWith("cpu-") && !t.contains("trip") } }
                .map { "${it.path}/temp" }
                .also { cpuZones = it }
        }
    }
}
