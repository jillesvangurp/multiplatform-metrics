package com.jillesvangurp.multiplatformmetrics

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.TimeSource
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.getAndUpdate

/** In-memory implementation of [IMeterRegistry]. */
class SimpleMeterRegistry(val timeSource: TimeSource = TimeSource.Monotonic) : IMeterRegistry {
    private val counters = mutableListOf<CounterImpl>()
    private val gauges = mutableListOf<GaugeImpl>()
    private val timers = mutableListOf<TimerImpl>()
    private val summaries = mutableListOf<SummaryImpl>()

    override fun counter(name: String, tags: Map<String, String>): Counter =
        counters.find { it.name == name && it.tags == tags } ?: CounterImpl(
            name = name,
            tags = tags
        ).also { counters += it }

    override fun gauge(name: String, tags: Map<String, String>): Gauge =
        gauges.find { it.name == name && it.tags == tags } ?: GaugeImpl(name = name, tags = tags).also { gauges += it }

    override fun timer(
        name: String,
        tags: Map<String, String>,
        config: TimerConfig
    ): Timer =
        timers.find { it.name == name && it.tags == tags } ?: TimerImpl(
            name = name,
            tags = tags,
            timeSource = timeSource,
            config = config
        ).also { timers += it }

    override fun summary(name: String, tags: Map<String, String>): DistributionSummary =
        summaries.find { it.name == name && it.tags == tags } ?: SummaryImpl(name, tags).also { summaries += it }

    override fun snapshot(): MetricsSnapshot = MetricsSnapshot(
        buildList {
            counters.forEach { add(it.point()) }
            gauges.forEach { add(it.point()) }
            timers.forEach { addAll(it.points()) }
            summaries.forEach { add(it.point()) }
        }
    )

    private class CounterImpl(val name: String, val tags: Map<String, String>) : Counter {
        private val c = atomic(0L)
        override fun inc(delta: Long) { c.getAndAdd(delta) }
        fun point() = MetricPoint("counter", name, tags, count = c.value)
    }

    private class GaugeImpl(val name: String, val tags: Map<String, String>) : Gauge {
        private val v = atomic(0.0)
        override fun set(value: Double) { v.value = value }
        fun point() = MetricPoint("gauge", name, tags, value = v.value)
    }

    private class TimerImpl(
        val name: String,
        val tags: Map<String, String>,
        val timeSource: TimeSource,
        config: TimerConfig
    ) : Timer {
        private val percentiles = config.percentiles
        private val slaDurations = config.sla
        private val slaMs = slaDurations.map { it.toDouble(DurationUnit.MILLISECONDS) }
        private val slaCounts = IntArray(slaDurations.size)
        private val measurements = if (percentiles.isNotEmpty()) mutableListOf<Double>() else null

        private val count = atomic(0L)
        private val sumMs = atomic(0.0)
        private val minMs = atomic(Double.POSITIVE_INFINITY)
        private val maxMs = atomic(0.0)

        override suspend fun <T> record(block: suspend () -> T): T {
            val mark = timeSource.markNow()
            try {
                return block()
            } finally {
                record(mark.elapsedNow())
            }
        }

        override fun record(duration: Duration) {
            count.incrementAndGet()
            val d = duration.toDouble(DurationUnit.MILLISECONDS)
            sumMs.getAndUpdate { it + d }
            minMs.getAndUpdate { min(it, d) }
            maxMs.getAndUpdate { max(it, d) }
            measurements?.add(d)
            if (slaMs.isNotEmpty()) {
                slaMs.forEachIndexed { index, bound ->
                    if (d <= bound) {
                        slaCounts[index]++
                    }
                }
            }
        }

        fun points(): List<MetricPoint> {
            val base = MetricPoint(
                "timer", name, tags,
                count = count.value, sum = sumMs.value,
                min = if (count.value > 0) minMs.value else 0.0,
                max = maxMs.value
            )
            val extra = buildList {
                if (measurements != null && measurements.isNotEmpty()) {
                    val sorted = measurements.sorted()
                    val size = sorted.size
                    percentiles.forEach { p ->
                        val rank = ceil(p * size).toInt().coerceIn(1, size) - 1
                        val v = sorted[rank]
                        add(
                            MetricPoint(
                                "timer",
                                name,
                                tags + ("percentile" to p.toString()),
                                value = v
                            )
                        )
                    }
                }
                if (slaDurations.isNotEmpty()) {
                    slaDurations.forEachIndexed { index, d ->
                        add(
                            MetricPoint(
                                "timer",
                                name,
                                tags + ("sla" to d.inWholeMilliseconds.toString()),
                                count = slaCounts[index].toLong()
                            )
                        )
                    }
                }
            }
            return listOf(base) + extra
        }
    }

    private class SummaryImpl(val name: String, val tags: Map<String, String>) : DistributionSummary {
        private val count = atomic(0L)
        private val sum = atomic(0.0)
        private val min = atomic(Double.POSITIVE_INFINITY)
        private val max = atomic(0.0)

        override fun record(amount: Double) {
            count.incrementAndGet()
            sum.getAndUpdate { it + amount }
            min.getAndUpdate { min(it, amount) }
            max.getAndUpdate { max(it, amount) }
        }

        fun point() = MetricPoint(
            "summary", name, tags,
            count = count.value, sum = sum.value,
            min = if (count.value > 0) min.value else 0.0,
            max = max.value
        )
    }
}
