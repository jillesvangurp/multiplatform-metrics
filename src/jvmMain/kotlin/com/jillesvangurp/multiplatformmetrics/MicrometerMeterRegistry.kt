package com.jillesvangurp.multiplatformmetrics

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
            registry.forEachMeter { meter ->
                val tagsMap = meter.id.tags.associate { it.key to it.value }
                try {
                    when (meter) {
                        is io.micrometer.core.instrument.Counter -> {
                            add(
                                MetricPoint(
                                    "counter",
                                    meter.id.name,
                                    tagsMap,
                                    count = meter.count().toLong()
                                )
                            )
                        }

                        is io.micrometer.core.instrument.FunctionCounter -> {
                            add(
                                MetricPoint(
                                    "counter",
                                    meter.id.name,
                                    tagsMap,
                                    count = meter.count().toLong()
                                )
                            )
                        }

                        is io.micrometer.core.instrument.Gauge -> {
                            add(MetricPoint("gauge", meter.id.name, tagsMap, value = meter.value()))
                        }

                        is io.micrometer.core.instrument.Timer -> {
                            val s = meter.takeSnapshot()
                            add(
                                MetricPoint(
                                    "timer", meter.id.name, tagsMap,
                                    count = s.count(),
                                    sum = s.total(TimeUnit.MILLISECONDS),
                                    max = s.max(TimeUnit.MILLISECONDS)
                                )
                            )
                        }

                        is io.micrometer.core.instrument.FunctionTimer -> {
                            add(
                                MetricPoint(
                                    "timer", meter.id.name, tagsMap,
                                    count = meter.count().toLong(),
                                    sum = meter.totalTime(TimeUnit.MILLISECONDS)
                                    // FunctionTimer doesn't track max; leave it null
                                )
                            )
                        }

                        is io.micrometer.core.instrument.DistributionSummary -> {
                            add(
                                MetricPoint(
                                    "summary", meter.id.name, tagsMap,
                                    count = meter.count(),
                                    sum = meter.totalAmount(),
                                    max = meter.max()
                                )
                            )
                        }

                        is io.micrometer.core.instrument.LongTaskTimer -> {
                            add(
                                MetricPoint(
                                    "long_task_timer", meter.id.name, tagsMap,
                                    count = meter.activeTasks().toLong(),
                                    sum = meter.duration(TimeUnit.MILLISECONDS)
                                )
                            )
                        }

                        else -> {
                           // Skip for now
                        }
                    }
                } catch (_: Throwable) { /* ignore */
                }
            }
        }
        return MetricsSnapshot(points)
    }
}

