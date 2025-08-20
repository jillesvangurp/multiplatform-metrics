package com.jillesvangurp.multiplatformmetrics

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class SimpleMeterRegistryTest {
    @Test
    fun counterShouldRecordIncrements() {
        val registry = SimpleMeterRegistry()
        val counter = registry.counter("hits", mapOf("route" to "test"))
        counter.inc()
        counter.inc(2)

        val snapshot = registry.snapshot()
        snapshot.points.size shouldBe 1
        val point = snapshot.points.first()
        point.type shouldBe "counter"
        point.name shouldBe "hits"
        point.tags shouldBe mapOf("route" to "test")
        point.count shouldBe 3
    }

    @Test
    fun gaugeShouldRecordLastValue() {
        val registry = SimpleMeterRegistry()
        val gauge = registry.gauge("load")
        gauge.set(1.0)
        gauge.set(2.5)

        val point = registry.snapshot().points.first()
        point.type shouldBe "gauge"
        point.name shouldBe "load"
        point.value shouldBe 2.5
    }

    @Test
    fun timerShouldRecordDurations() {
        val registry = SimpleMeterRegistry()
        val timer = registry.timer("latency")
        timer.record(10)
        timer.record(20)

        val point = registry.snapshot().points.first()
        point.type shouldBe "timer"
        point.name shouldBe "latency"
        point.count shouldBe 2
        point.sumMs shouldBe 30.0
        point.minMs shouldBe 10.0
        point.maxMs shouldBe 20.0
    }

    @Test
    fun snapshotShouldContainAllMetrics() {
        val registry = SimpleMeterRegistry()
        registry.counter("c").inc()
        registry.gauge("g").set(1.0)
        registry.timer("t").record(1)

        val names = registry.snapshot().points.map { it.name }
        names.shouldContainExactly("c", "g", "t")
    }
}

