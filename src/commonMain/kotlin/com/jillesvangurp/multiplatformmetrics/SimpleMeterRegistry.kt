package com.jillesvangurp.multiplatformmetrics

import kotlin.math.ceil
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.TimeSource
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

private const val PERCENTILE_WINDOW_SIZE = 1_024

/** In-memory implementation of [IMeterRegistry] with thread-safe registration and recording. */
class SimpleMeterRegistry(val timeSource: TimeSource = TimeSource.Monotonic) : IMeterRegistry {
    private val counters = Meters<CounterImpl>()
    private val gauges = Meters<GaugeImpl>()
    private val timers = Meters<TimerImpl>()
    private val summaries = Meters<SummaryImpl>()

    override fun counter(name: String, tags: Map<String, String>): Counter =
        counters.getOrCreate(name, tags, ::CounterImpl)

    override fun gauge(name: String, tags: Map<String, String>): Gauge =
        gauges.getOrCreate(name, tags, ::GaugeImpl)

    override fun timer(
        name: String,
        tags: Map<String, String>,
        config: TimerConfig
    ): Timer = timers.getOrCreate(name, tags) { TimerImpl(it, timeSource, config) }

    override fun summary(name: String, tags: Map<String, String>): DistributionSummary =
        summaries.getOrCreate(name, tags, ::SummaryImpl)

    override fun snapshot(): MetricsSnapshot = MetricsSnapshot(
        buildList {
            counters.registered().forEach { add(it.point()) }
            gauges.registered().forEach { add(it.point()) }
            timers.registered().forEach { addAll(it.points()) }
            summaries.registered().forEach { add(it.point()) }
        }
    )

    private data class MeterKey(val name: String, val tags: Map<String, String>)

    private class Meters<T : Any> {
        private val lock = SynchronizedObject()
        private val meters = LinkedHashMap<MeterKey, T>()

        fun getOrCreate(name: String, tags: Map<String, String>, create: (MeterKey) -> T): T =
            synchronized(lock) {
                meters[MeterKey(name, tags)] ?: run {
                    val key = MeterKey(name, buildMap { putAll(tags) })
                    create(key).also { meters[key] = it }
                }
            }

        fun registered(): List<T> = synchronized(lock) { meters.values.toList() }
    }

    private class CounterImpl(private val key: MeterKey) : Counter {
        private val count = atomic(0L)
        override fun inc(delta: Long) { count.getAndAdd(delta) }
        fun point() = MetricPoint("counter", key.name, key.tags, count = count.value)
    }

    private class GaugeImpl(private val key: MeterKey) : Gauge {
        private val value = atomic(0.0)
        override fun set(value: Double) { this.value.value = value }
        fun point() = MetricPoint("gauge", key.name, key.tags, value = value.value)
    }

    private class TimerImpl(
        private val key: MeterKey,
        private val timeSource: TimeSource,
        config: TimerConfig
    ) : Timer {
        private val lock = SynchronizedObject()
        private val percentiles = config.percentiles.distinct()
        private val sla = config.sla.distinct()
        private val slaCounts = LongArray(sla.size)
        private val recentSamples = if (percentiles.isNotEmpty()) DoubleArray(PERCENTILE_WINDOW_SIZE) else null
        private val totals = Distribution()

        override suspend fun <T> record(block: suspend () -> T): T {
            val mark = timeSource.markNow()
            try {
                return block()
            } finally {
                record(mark.elapsedNow())
            }
        }

        override fun record(duration: Duration) {
            val milliseconds = duration.toDouble(DurationUnit.MILLISECONDS)
            synchronized(lock) {
                recentSamples?.let { it[(totals.count % it.size).toInt()] = milliseconds }
                totals.record(milliseconds)
                sla.forEachIndexed { index, bound ->
                    if (duration <= bound) {
                        slaCounts[index]++
                    }
                }
            }
        }

        fun points(): List<MetricPoint> {
            val (base, samples, counts) = synchronized(lock) {
                val samples = recentSamples?.let { it.copyOf(minOf(totals.count, it.size.toLong()).toInt()) }
                Triple(totals.point("timer", key), samples, slaCounts.copyOf())
            }
            return buildList {
                add(base)
                if (samples != null && samples.isNotEmpty()) {
                    samples.sort()
                    percentiles.forEach { percentile ->
                        val rank = ceil(percentile * samples.size).toInt().coerceIn(1, samples.size) - 1
                        add(
                            MetricPoint(
                                "timer", key.name, key.tags + ("percentile" to percentile.toString()),
                                value = samples[rank]
                            )
                        )
                    }
                }
                sla.forEachIndexed { index, duration ->
                    val boundary = duration.toDouble(DurationUnit.MILLISECONDS).toString().removeSuffix(".0")
                    add(MetricPoint("timer", key.name, key.tags + ("sla" to boundary), count = counts[index]))
                }
            }
        }
    }

    private class SummaryImpl(private val key: MeterKey) : DistributionSummary {
        private val lock = SynchronizedObject()
        private val totals = Distribution()

        override fun record(amount: Double) = synchronized(lock) { totals.record(amount) }
        fun point() = synchronized(lock) { totals.point("summary", key) }
    }

    private class Distribution {
        var count = 0L
            private set
        private var sum = 0.0
        private var min = 0.0
        private var max = 0.0

        fun record(amount: Double) {
            sum += amount
            min = if (count == 0L) amount else minOf(min, amount)
            max = if (count == 0L) amount else maxOf(max, amount)
            count++
        }

        fun point(type: String, key: MeterKey) =
            MetricPoint(type, key.name, key.tags, count = count, sum = sum, min = min, max = max)
    }
}
