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

interface Counter { fun inc(delta: Long = 1) }
interface Gauge { fun set(value: Double) }
interface Timer {
    fun <T> record(block: () -> T): T
    fun record(duration: Duration)
    fun record(durationMs: Long) = record(durationMs.milliseconds)
}

interface IMeterRegistry {
    fun counter(name: String, tags: Map<String, String> = emptyMap()): Counter
    fun gauge(name: String, tags: Map<String, String> = emptyMap()): Gauge
    fun timer(name: String, tags: Map<String, String> = emptyMap()): Timer
    fun snapshot(): MetricsSnapshot                    // for JSON to send to server
}

@Serializable
data class MetricPoint(
    val type: String,             // counter|gauge|timer
    val name: String,
    val tags: Map<String,String> = emptyMap(),
    val value: Double? = null,    // for gauge
    val count: Long? = null,      // for counter/timer
    val sumMs: Double? = null,    // timers: total duration
    val minMs: Double? = null,
    val maxMs: Double? = null
)

@Serializable
data class MetricsSnapshot(val points: List<MetricPoint>) {
    fun toJson(pretty: Boolean = false): String =
        if (pretty) DEFAULT_PRETTY_JSON.encodeToString(this)
        else DEFAULT_JSON.encodeToString(this)
}

class SimpleMeterRegistry : IMeterRegistry {
    private val counters = mutableListOf<CounterImpl>()
    private val gauges   = mutableListOf<GaugeImpl>()
    private val timers   = mutableListOf<TimerImpl>()

        override fun counter(name: String, tags: Map<String, String>) =
            counters.find { it.name == name && it.tags == tags } ?: CounterImpl(name, tags).also { counters += it }

        override fun gauge(name: String, tags: Map<String, String>) =
            gauges.find { it.name == name && it.tags == tags } ?: GaugeImpl(name, tags).also { gauges += it }

        override fun timer(name: String, tags: Map<String, String>) =
            timers.find { it.name == name && it.tags == tags } ?: TimerImpl(name, tags).also { timers += it }

    override fun snapshot(): MetricsSnapshot = MetricsSnapshot(
        buildList {
            counters.forEach { add(it.point()) }
            gauges.forEach { add(it.point()) }
            timers.forEach { add(it.point()) }
        }
    )

    class CounterImpl(val name: String, val tags: Map<String,String>) : Counter {
        private val c = atomic(0L)
        override fun inc(delta: Long) { c.getAndAdd(delta) }
        fun point() = MetricPoint("counter", name, tags, count = c.value)
    }

    class GaugeImpl(val name: String, val tags: Map<String,String>) : Gauge {
        private val v = atomic(0.0)
        override fun set(value: Double) { v.value = value }
        fun point() = MetricPoint("gauge", name, tags, value = v.value)
    }

    class TimerImpl(val name: String, val tags: Map<String,String>) : Timer {
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
            count = count.value, sumMs = sumMs.value,
            minMs = if (count.value > 0) minMs.value else 0.0,
            maxMs = maxMs.value
        )
    }
}
