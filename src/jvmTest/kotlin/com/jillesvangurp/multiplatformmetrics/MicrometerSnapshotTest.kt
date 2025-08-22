package com.jillesvangurp.multiplatformmetrics

import io.kotest.matchers.shouldBe
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import io.micrometer.core.instrument.FunctionCounter
import io.micrometer.core.instrument.FunctionTimer
import io.micrometer.core.instrument.LongTaskTimer
import io.micrometer.core.instrument.simple.SimpleMeterRegistry

class MicrometerSnapshotTest {
    @Test
    fun snapshotShouldHandleAllMeters() {
        val micrometer = SimpleMeterRegistry()
        val registry = MicrometerMeterRegistry(micrometer)

        registry.counter("counter").inc()
        registry.gauge("gauge").set(2.0)
        registry.timer("timer").record(10.milliseconds)
        registry.summary("summary").record(3.0)

        val fcValue = AtomicLong(0)
        FunctionCounter.builder("function.counter", fcValue) { it.get().toDouble() }
            .register(micrometer)
        fcValue.incrementAndGet()

        data class FT(var count: Long = 0, var total: Double = 0.0)
        val ftState = FT()
        FunctionTimer.builder("function.timer", ftState, { it.count }, { it.total }, TimeUnit.MILLISECONDS)
            .register(micrometer)
        ftState.count = 1
        ftState.total = 5.0

        val longTaskTimer = LongTaskTimer.builder("long.task.timer").register(micrometer)
        val sample = longTaskTimer.start()
        Thread.sleep(10)
        val snapshot = registry.snapshot()
        sample.stop()

        val expected = listOf(
            "counter",
            "gauge",
            "timer",
            "summary",
            "function.counter",
            "function.timer",
            "long.task.timer"
        )
        val names = snapshot.points.map { it.name }
        expected.forEach { names.contains(it) shouldBe true }

        snapshot.points.first { it.name == "counter" }.count shouldBe 1
        snapshot.points.first { it.name == "gauge" }.value shouldBe 2.0
        snapshot.points.first { it.name == "timer" }.count shouldBe 1
        snapshot.points.first { it.name == "summary" }.count shouldBe 1
        snapshot.points.first { it.name == "function.counter" }.count shouldBe 1
        snapshot.points.first { it.name == "function.timer" }.count shouldBe 1
        snapshot.points.first { it.name == "long.task.timer" }.count shouldBe 1

        val otLines = snapshot.toOpenTelemetryJsonLines()
        expected.forEach { n -> otLines.any { "\"name\":\"$n\"" in it } shouldBe true }

        val promLines = snapshot.toPrometheusLines()
        fun sanitize(s: String) = s.replace('.', '_')
        expected.forEach { n -> promLines.any { it.startsWith(sanitize(n)) } shouldBe true }
    }
}

