package com.jillesvangurp.multiplatformmetrics

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.microseconds
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
    fun percentilesUseOnlyTheLatestSamplesWhileTotalsStayCumulative() {
        val registry = SimpleMeterRegistry()
        val config = TimerConfig(percentiles = listOf(0.5), sla = listOf(2.milliseconds))
        val timer = registry.timer("latency", config = config)
        repeat(1_024) { timer.record(1.milliseconds) }
        repeat(1_024) { timer.record(10.milliseconds) }

        val (total, percentile, sla) = registry.snapshot().points
        total.count shouldBe 2_048L
        total.sum shouldBe 11_264.0
        percentile.value shouldBe 10.0
        sla.count shouldBe 1_024L
    }

    @Test
    fun registrationCopiesMutableTags() {
        val registry = SimpleMeterRegistry()
        val tags = mutableMapOf("region" to "us", "route" to "old")
        val first = registry.counter("hits", tags)
        first.inc()
        val originalSnapshot = registry.snapshot()
        tags["route"] = "new"
        registry.counter("hits", tags).inc(2)
        registry.counter("hits", mapOf("route" to "old", "region" to "us")).inc(3)

        originalSnapshot.points.single().tags["route"] shouldBe "old"
        registry.snapshot().points.associate { it.tags["route"] to it.count } shouldBe
            mapOf("old" to 4L, "new" to 2L)
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
        registry.counter("hits", mapOf("route" to "test")).inc(9_007_199_254_740_993L)
        val snapshot = registry.snapshot()
        val line = snapshot.toOpenTelemetryJsonLines().single()
        val metric = exportedMetric(line)
        metric["name"]!!.jsonPrimitive.content shouldBe "hits"
        val sum = metric["sum"]!!.jsonObject
        sum["aggregationTemporality"] shouldBe JsonPrimitive(2)
        sum["isMonotonic"] shouldBe JsonPrimitive(true)
        val dp = sum["dataPoints"]!!.jsonArray.single().jsonObject
        dp["asInt"] shouldBe JsonPrimitive("9007199254740993")
        (dp["timeUnixNano"]!!.jsonPrimitive.long > 0) shouldBe true
        dp["attributes"]!!.jsonArray.first().jsonObject["key"]!!.jsonPrimitive.content shouldBe "route"
        snapshot.toOpenTelemetryJsonLines().single() shouldBe line
    }

    @Test
    fun otlpTimerExportsQuantilesWithTheirSeriesAndSlaAsCounters() {
        val registry = SimpleMeterRegistry()
        val config = TimerConfig(
            percentiles = listOf(0.9, 0.5),
            sla = listOf(250.microseconds, 750.microseconds)
        )
        val first = registry.timer("latency", mapOf("route" to "first"), config)
        first.record(250.microseconds)
        first.record(750.microseconds)
        registry.timer("latency", mapOf("route" to "second"), config).record(2.milliseconds)

        val metrics = registry.snapshot().toOpenTelemetryJsonLines().map(::exportedMetric)
        val summaries = metrics.filter { it["name"] == JsonPrimitive("latency") }
        summaries.size shouldBe 2
        summaries.forEach { it["unit"] shouldBe JsonPrimitive("ms") }
        val data = summaries.map { it.getValue("summary").jsonObject.getValue("dataPoints").jsonArray.single().jsonObject }
        data.map { it.getValue("count") } shouldBe listOf(JsonPrimitive("2"), JsonPrimitive("1"))
        data.first()["sum"] shouldBe JsonPrimitive(1.0)
        data.first()["min"] shouldBe null
        data.first()["asDouble"] shouldBe null
        val quantiles = data.map { it.getValue("quantileValues").jsonArray.map { q -> q.jsonObject } }
        quantiles[0].map { it.getValue("quantile").jsonPrimitive.double } shouldBe listOf(0.5, 0.9)
        quantiles[0].map { it.getValue("value").jsonPrimitive.double } shouldBe listOf(0.25, 0.75)
        quantiles[1].map { it.getValue("value").jsonPrimitive.double } shouldBe listOf(2.0, 2.0)

        val sla = metrics.filter { it["name"] == JsonPrimitive("latency.sla") }.map {
            it.getValue("sum").jsonObject.getValue("dataPoints").jsonArray.single().jsonObject
        }
        sla.map { it.getValue("asInt").jsonPrimitive.long } shouldBe listOf(1L, 2L, 0L, 0L)
        sla.map { point ->
            val label = point.getValue("attributes").jsonArray.first { it.jsonObject["key"] == JsonPrimitive("sla") }
            label.jsonObject.getValue("value").jsonObject.getValue("stringValue").jsonPrimitive.content
        } shouldBe listOf("0.25", "0.75", "0.25", "0.75")
        (data + sla).map { it.getValue("timeUnixNano") }.distinct().size shouldBe 1
    }

    @Test
    fun snapshotShouldExportPrometheusLines() {
        val registry = SimpleMeterRegistry()
        val route = "a\"b\\c\nd"
        registry.counter("hits", mapOf("route" to route)).inc(2)
        val line = registry.snapshot().toPrometheusLines().single()
        line shouldBe """hits{route="a\"b\\c\nd"} 2"""
        val quotedLabel = line.substringAfter("route=").substringBeforeLast("}")
        DEFAULT_JSON.parseToJsonElement(quotedLabel).jsonPrimitive.content shouldBe route
    }

    private fun exportedMetric(line: String): JsonObject =
        DEFAULT_JSON.parseToJsonElement(line).jsonObject.getValue("resourceMetrics").jsonArray.single()
            .jsonObject.getValue("scopeMetrics").jsonArray.single()
            .jsonObject.getValue("metrics").jsonArray.single().jsonObject
}

