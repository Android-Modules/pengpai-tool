package com.dalong.pengpaitool.ui

/** 一次采样，字段顺序与 charge.sh record() 一致。 */
class Sample(
    val t: Long, val soc: Int, val ma: Int, val mv: Int, val bt: Int, val bd: Int,
    val sys: Int, val lvl: Int, val rm: Int, val scr: Int, val sc: Int, val act: String,
) {
    val watt get() = ma * mv / 1e6f
}

/** 长时间插电时限制页面持有的曲线点；每桶保留主要指标的峰谷及整个会话的首末点。 */
internal object ChargeSampleBuffer {
    const val MAX_SAMPLES = 8192
    private const val METRICS = 6

    data class State(val samples: List<Sample>, val lastTimestamp: Long, val compacted: Boolean)

    fun append(previous: State?, incoming: List<Sample>, limit: Int = MAX_SAMPLES): State {
        require(limit >= METRICS * 2 + 2)
        var latest = previous?.lastTimestamp ?: 0L
        val fresh = ArrayList<Sample>()
        for (sample in incoming) {
            if (sample.t > latest) {
                fresh.add(sample)
                latest = sample.t
            }
        }
        if (fresh.isEmpty() && previous != null) return previous
        val merged = previous?.samples.orEmpty() + fresh
        return State(
            compact(merged, limit), latest,
            previous?.compacted == true || merged.size > limit,
        )
    }

    private fun metric(sample: Sample, index: Int): Long = when (index) {
        0 -> sample.ma.toLong() * sample.mv
        1 -> sample.ma.toLong()
        2 -> sample.mv.toLong()
        3 -> sample.bt.toLong()
        4 -> sample.bd.toLong()
        else -> sample.soc.toLong()
    }

    private fun compact(samples: List<Sample>, limit: Int): List<Sample> {
        if (samples.size <= limit) return samples
        val buckets = (limit - 2) / (METRICS * 2)
        val result = ArrayList<Sample>(limit)
        result.add(samples.first())
        for (bucket in 0 until buckets) {
            val start = 1 + bucket * (samples.size - 2) / buckets
            val end = 1 + (bucket + 1) * (samples.size - 2) / buckets
            val extrema = IntArray(METRICS * 2) { start }
            for (index in start + 1 until end) {
                for (m in 0 until METRICS) {
                    val value = metric(samples[index], m)
                    if (value < metric(samples[extrema[m * 2]], m)) extrema[m * 2] = index
                    if (value > metric(samples[extrema[m * 2 + 1]], m)) extrema[m * 2 + 1] = index
                }
            }
            extrema.distinct().sorted().forEach { result.add(samples[it]) }
        }
        result.add(samples.last())
        return result
    }
}
