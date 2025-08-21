package com.jillesvangurp.multiplatformmetrics

import io.micrometer.core.instrument.Meter
import io.micrometer.core.instrument.MeterRegistry
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.toJavaDuration
import kotlinx.atomicfu.atomic

/** [IMeterRegistry] backed by Micrometer on the JVM. */
class MicrometerMeterRegistry(private val registry: MeterRegistry) : IMeterRegistry {
    private fun tagArray(tags: Map<String, String>): Array<String> =
        tags.flatMap { listOf(it.key, it.value) }.toTypedArray()

    override fun counter(name: String, tags: Map<String, String>): Counter {
        val c = registry.counter(name, *tagArray(tags))
        return object : Counter {
            override fun inc(delta: Long) {
                c.increment(delta.toDouble())
            }
        }
    }

    override fun gauge(name: String, tags: Map<String, String>): Gauge {
        val ref = atomic(0.0)
        io.micrometer.core.instrument.Gauge.builder(name, ref) { it.value }
            .tags(*tagArray(tags))
            .register(registry)
        return object : Gauge {
            override fun set(value: Double) {
                ref.value = value
            }
        }
    }

    override fun timer(
        name: String,
        tags: Map<String, String>,
        config: TimerConfig
    ): Timer {
        val builder = io.micrometer.core.instrument.Timer.builder(name).tags(*tagArray(tags))
        if (config.percentiles.isNotEmpty()) {
            builder.publishPercentiles(*config.percentiles.toDoubleArray())
        }
        if (config.sla.isNotEmpty()) {
            builder.sla(*config.sla.map { it.toJavaDuration() }.toTypedArray())
        }
        val t = builder.register(registry)
        return object : Timer {
            override suspend fun <T> record(block: suspend () -> T): T {
                val start = System.nanoTime()
                try {
                    return block()
                } finally {
                    val d = System.nanoTime() - start
                    t.record(d, TimeUnit.NANOSECONDS)
                }
            }

            override fun record(duration: Duration) {
                t.record(duration.inWholeNanoseconds, TimeUnit.NANOSECONDS)
            }
        }
    }

    override fun summary(name: String, tags: Map<String, String>): DistributionSummary {
        val s = registry.summary(name, *tagArray(tags))
        return object : DistributionSummary {
            override fun record(amount: Double) {
                s.record(amount)
            }
        }
    }

    override fun snapshot(): MetricsSnapshot {
        val points = buildList {
            registry.forEachMeter { m ->
                val tagsMap = m.id.tags.associate { it.key to it.value }
                when (m.id.type) {
                    Meter.Type.COUNTER -> {
                        val c = registry.get(m.id.name).tags(m.id.tags).counter()
                        add(MetricPoint("counter", m.id.name, tagsMap, count = c.count().toLong()))
                    }
                    Meter.Type.GAUGE -> {
                        val g = registry.get(m.id.name).tags(m.id.tags).gauge()
                        add(MetricPoint("gauge", m.id.name, tagsMap, value = g.value()))
                    }
                    Meter.Type.TIMER -> {
                        val timer = registry.get(m.id.name).tags(m.id.tags).timer()
                        val snap = timer.takeSnapshot()
                        add(
                            MetricPoint(
                                "timer", m.id.name, tagsMap,
                                count = snap.count(),
                                sum = snap.total(TimeUnit.MILLISECONDS),
                                max = snap.max(TimeUnit.MILLISECONDS)
                            )
                        )
                    }
                    Meter.Type.DISTRIBUTION_SUMMARY -> {
                        val summary = registry.get(m.id.name).tags(m.id.tags).summary()
                        add(
                            MetricPoint(
                                "summary", m.id.name, tagsMap,
                                count = summary.count(),
                                sum = summary.totalAmount(),
                                max = summary.max()
                            )
                        )
                    }
                    else -> {}
                }
            }
        }
        return MetricsSnapshot(points)
    }
}

