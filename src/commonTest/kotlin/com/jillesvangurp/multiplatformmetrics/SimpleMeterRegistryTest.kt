package com.jillesvangurp.multiplatformmetrics

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.test.runTest
import com.jillesvangurp.serializationext.DEFAULT_JSON
import kotlinx.serialization.json.*

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
        timer.record(10.milliseconds)
        timer.record(20.milliseconds)

        val point = registry.snapshot().points.first()
        point.type shouldBe "timer"
        point.name shouldBe "latency"
        point.count shouldBe 2
        point.sum shouldBe 30.0
        point.min shouldBe 10.0
        point.max shouldBe 20.0
    }

    @Test
    fun timerShouldRecordKotlinDurations() {
        val registry = SimpleMeterRegistry()
        val timer = registry.timer("latency")
        timer.record(10.milliseconds)

        val point = registry.snapshot().points.first()
        point.count shouldBe 1
        point.sum shouldBe 10.0
    }

    @Test
    fun timerShouldCalculatePercentiles() {
        val registry = SimpleMeterRegistry()
        val timer = registry.timer("latency", config = TimerConfig(percentiles = listOf(0.5, 0.9)))
        listOf(10, 20, 30, 40, 50).forEach { timer.record(it.milliseconds) }
        val points = registry.snapshot().points.filter { it.name == "latency" }
        points.first { it.tags["percentile"] == "0.5" }.value shouldBe 30.0
        points.first { it.tags["percentile"] == "0.9" }.value shouldBe 50.0
    }

    @Test
    fun timerShouldTrackSlaCounts() {
        val registry = SimpleMeterRegistry()
        val timer = registry.timer("latency", config = TimerConfig(sla = listOf(20.milliseconds, 40.milliseconds)))
        timer.record(10.milliseconds)
        timer.record(30.milliseconds)
        timer.record(50.milliseconds)
        val points = registry.snapshot().points.filter { it.tags.containsKey("sla") }
        points.first { it.tags["sla"] == "20" }.count shouldBe 1
        points.first { it.tags["sla"] == "40" }.count shouldBe 2
    }

    @Test
    fun measureShouldRecordMetrics() = runTest {
        val registry = SimpleMeterRegistry()

        registry.measure("op") { }

        try {
            registry.measure("op") { error("boom") }
        } catch (_: Throwable) {
        }

        val snapshot = registry.snapshot()
        snapshot.points.count { it.name == "op.duration" } shouldBe 1
        snapshot.points.first { it.name == "op.duration" }.count shouldBe 2
        snapshot.points.first { it.name == "op.success" }.count shouldBe 1
        snapshot.points.first { it.name == "op.total" }.count shouldBe 2
        val failurePoint = snapshot.points.first { it.name == "op.failure" }
        failurePoint.count shouldBe 1
        failurePoint.tags["exception"] shouldBe "IllegalStateException"
    }

    @Test
    fun measureResultShouldRecordMetrics() = runTest {
        val registry = SimpleMeterRegistry()

        registry.measureResult("res") { Result.success(Unit) }
        registry.measureResult("res") { Result.failure<Unit>(IllegalArgumentException("fail")) }

        val snapshot = registry.snapshot()
        snapshot.points.first { it.name == "res.duration" }.count shouldBe 2
        snapshot.points.first { it.name == "res.success" }.count shouldBe 1
        val failurePoint = snapshot.points.first { it.name == "res.failure" }
        failurePoint.count shouldBe 1
        failurePoint.tags["exception"] shouldBe "IllegalArgumentException"
        snapshot.points.first { it.name == "res.total" }.count shouldBe 2
    }

    @Test
    fun snapshotShouldContainAllMetrics() = runTest {
        val registry = SimpleMeterRegistry()
        registry.counter("c").inc()
        registry.gauge("g").set(1.0)
        registry.timer("t").record(1.milliseconds)
        registry.summary("s").record(1.0)

        val names = registry.snapshot().points.map { it.name }
        names.shouldContainExactly("c", "g", "t", "s")
    }

    @Test
    fun summaryShouldRecordSamples() {
        val registry = SimpleMeterRegistry()
        val summary = registry.summary("payload")
        summary.record(10.0)
        summary.record(5.0)

        val point = registry.snapshot().points.first()
        point.type shouldBe "summary"
        point.name shouldBe "payload"
        point.count shouldBe 2
        point.sum shouldBe 15.0
        point.min shouldBe 5.0
        point.max shouldBe 10.0
    }

    @Test
    fun snapshotShouldExportOpenTelemetryJsonLines() {
        val registry = SimpleMeterRegistry()
        registry.counter("hits", mapOf("route" to "test")).inc(2)
        val line = registry.snapshot().toOpenTelemetryJsonLines().first()
        val json = DEFAULT_JSON.parseToJsonElement(line).jsonObject
        val metric = json["resourceMetrics"]!!.jsonArray[0]
            .jsonObject["scopeMetrics"]!!.jsonArray[0]
            .jsonObject["metrics"]!!.jsonArray[0].jsonObject
        metric["name"]!!.jsonPrimitive.content shouldBe "hits"
        val dp = metric["sum"]!!.jsonObject["dataPoints"]!!.jsonArray[0].jsonObject
        dp["asDouble"]!!.jsonPrimitive.double shouldBe 2.0
        dp["attributes"]!!.jsonArray.first().jsonObject["key"]!!.jsonPrimitive.content shouldBe "route"
    }

    @Test
    fun snapshotShouldExportPrometheusLines() {
        val registry = SimpleMeterRegistry()
        registry.counter("hits", mapOf("route" to "test")).inc(2)
        val line = registry.snapshot().toPrometheusLines().first()
        line shouldBe "hits{route=\"test\"} 2"
    }
}

