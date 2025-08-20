// commonMain
@file:OptIn(ExperimentalStdlibApi::class)
package com.jillesvangurp.multiplatformmetrics

import com.jillesvangurp.serializationext.DEFAULT_JSON
import com.jillesvangurp.serializationext.DEFAULT_PRETTY_JSON
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.getAndUpdate
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.DurationUnit
import kotlin.time.TimeSource

/** A monotonically increasing counter. */
interface Counter {
    /** Increment the counter by [delta]. */
    fun inc(delta: Long = 1)
}

/** A gauge representing the last [set] value. */
interface Gauge {
    /** Update the gauge to [value]. */
    fun set(value: Double)
}

/** A timer that records durations. */
interface Timer {
    /** Record the execution time of [block] and returns its result. */
    fun <T> record(block: () -> T): T

    /** Add an explicit [duration] in milliseconds. */
    fun record(duration: Duration)

    /** Convenience for recording [durationMs] in milliseconds. */
    fun record(durationMs: Long) = record(durationMs.milliseconds)
}

/**
 * A distribution summary for tracking sampled amounts such as sizes.
 */
interface DistributionSummary {
    /** Record a single [amount] sample. */
    fun record(amount: Double)
}

/** Registry for creating and exporting meters. */
interface IMeterRegistry {
    /** Create or retrieve a [Counter] named [name] with optional [tags]. */
    fun counter(name: String, tags: Map<String, String> = emptyMap()): Counter

    /** Create or retrieve a [Gauge] named [name] with optional [tags]. */
    fun gauge(name: String, tags: Map<String, String> = emptyMap()): Gauge

    /** Create or retrieve a [Timer] named [name] with optional [tags]. */
    fun timer(name: String, tags: Map<String, String> = emptyMap()): Timer

    /** Create or retrieve a [DistributionSummary] named [name] with optional [tags]. */
    fun summary(name: String, tags: Map<String, String> = emptyMap()): DistributionSummary

    /** Export a snapshot of all metrics. */
    fun snapshot(): MetricsSnapshot
}

/** Single exported metric sample. */
@Serializable
data class MetricPoint(
    val type: String,
    val name: String,
    val tags: Map<String, String> = emptyMap(),
    val value: Double? = null,
    val count: Long? = null,
    val sum: Double? = null,
    val min: Double? = null,
    val max: Double? = null
)

/** Collection of [MetricPoint]s. */
@Serializable
data class MetricsSnapshot(val points: List<MetricPoint>) {
    /** Serialize this snapshot to JSON. Returns the JSON string. */
    fun toJson(pretty: Boolean = false): String =
        if (pretty) DEFAULT_PRETTY_JSON.encodeToString(this)
        else DEFAULT_JSON.encodeToString(this)
}

/** In-memory implementation of [IMeterRegistry]. */
class SimpleMeterRegistry : IMeterRegistry {
    private val counters = mutableListOf<CounterImpl>()
    private val gauges = mutableListOf<GaugeImpl>()
    private val timers = mutableListOf<TimerImpl>()
    private val summaries = mutableListOf<SummaryImpl>()

    override fun counter(name: String, tags: Map<String, String>): Counter =
        counters.find { it.name == name && it.tags == tags } ?: CounterImpl(name, tags).also { counters += it }

    override fun gauge(name: String, tags: Map<String, String>): Gauge =
        gauges.find { it.name == name && it.tags == tags } ?: GaugeImpl(name, tags).also { gauges += it }

    override fun timer(name: String, tags: Map<String, String>): Timer =
        timers.find { it.name == name && it.tags == tags } ?: TimerImpl(name, tags).also { timers += it }

    override fun summary(name: String, tags: Map<String, String>): DistributionSummary =
        summaries.find { it.name == name && it.tags == tags } ?: SummaryImpl(name, tags).also { summaries += it }

    override fun snapshot(): MetricsSnapshot = MetricsSnapshot(
        buildList {
            counters.forEach { add(it.point()) }
            gauges.forEach { add(it.point()) }
            timers.forEach { add(it.point()) }
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

    private class TimerImpl(val name: String, val tags: Map<String, String>) : Timer {
        private val count = atomic(0L)
        private val sumMs = atomic(0.0)
        private val minMs = atomic(Double.POSITIVE_INFINITY)
        private val maxMs = atomic(0.0)

        override fun <T> record(block: () -> T): T {
            val mark = TimeSource.Monotonic.markNow()
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
            minMs.getAndUpdate { kotlin.math.min(it, d) }
            maxMs.getAndUpdate { kotlin.math.max(it, d) }
        }

        fun point() = MetricPoint(
            "timer", name, tags,
            count = count.value, sum = sumMs.value,
            min = if (count.value > 0) minMs.value else 0.0,
            max = maxMs.value
        )
    }

    private class SummaryImpl(val name: String, val tags: Map<String, String>) : DistributionSummary {
        private val count = atomic(0L)
        private val sum = atomic(0.0)
        private val min = atomic(Double.POSITIVE_INFINITY)
        private val max = atomic(0.0)

        override fun record(amount: Double) {
            count.incrementAndGet()
            sum.getAndUpdate { it + amount }
            min.getAndUpdate { kotlin.math.min(it, amount) }
            max.getAndUpdate { kotlin.math.max(it, amount) }
        }

        fun point() = MetricPoint(
            "summary", name, tags,
            count = count.value, sum = sum.value,
            min = if (count.value > 0) min.value else 0.0,
            max = max.value
        )
    }
}
