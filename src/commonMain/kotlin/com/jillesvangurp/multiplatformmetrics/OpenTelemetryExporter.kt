package com.jillesvangurp.multiplatformmetrics

import com.jillesvangurp.serializationext.DEFAULT_JSON
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class OpenTelemetryExporter(private val epochNanos: Long) {
    fun export(points: List<MetricPoint>): List<String> {
        val (percentiles, regular) = points.partition { it.isTimerPercentile }
        val quantilesByTimer = percentiles.groupBy { it.name to (it.tags - "percentile") }
        val timersWithTotals = regular.filter { it.type == "timer" && it.sum != null }
            .mapTo(HashSet()) { it.name to it.tags }
        return buildList {
            regular.forEach { point ->
                with(point) {
                    when (type) {
                        "counter" -> count?.let { add(counter(name, tags, it)) }
                        "gauge" -> value?.let { add(gauge(name, tags, it)) }
                        "timer", "summary" -> {
                            if (type == "timer" && sum == null && "sla" in tags) {
                                count?.let { add(counter("$name.sla", tags, it)) }
                            } else if (count != null) {
                                val quantiles = if (type == "timer" && sum != null) {
                                    quantilesByTimer[name to tags].orEmpty()
                                } else {
                                    emptyList()
                                }
                                add(summary(point, count, quantiles))
                            }
                        }
                        "long_task_timer" -> {
                            count?.let {
                                val data = dataPoint(tags) { put("asInt", it.toString()) }
                                add(metric("$name.active", "gauge", data))
                            }
                            sum?.let { add(gauge("$name.duration", tags, it, unit = "ms")) }
                        }
                        else -> value?.let { add(gauge(name, tags, it)) }
                    }
                }
            }
            // A manually constructed snapshot can contain percentile samples without timer totals.
            quantilesByTimer.filterKeys { it !in timersWithTotals }.values.flatten().forEach {
                add(gauge("${it.name}.percentile", it.tags, checkNotNull(it.value), unit = "ms"))
            }
        }
    }

    private val MetricPoint.isTimerPercentile: Boolean
        get() = type == "timer" && value != null && count == null && "percentile" in tags

    private fun counter(name: String, tags: Map<String, String>, count: Long): String =
        metric(name, "sum", dataPoint(tags) { put("asInt", count.toString()) }) {
            put("aggregationTemporality", 2)
            put("isMonotonic", true)
        }

    private fun gauge(name: String, tags: Map<String, String>, value: Double, unit: String? = null): String =
        metric(name, "gauge", dataPoint(tags) { putNumber("asDouble", value) }, unit)

    private fun summary(point: MetricPoint, count: Long, quantiles: List<MetricPoint>): String {
        val data = dataPoint(point.tags) {
            put("count", count.toString())
            point.sum?.let { putNumber("sum", it) }
            if (quantiles.isNotEmpty()) {
                val values = quantiles.mapNotNull { sample ->
                    val quantile = sample.tags["percentile"]?.toDoubleOrNull()
                    val value = checkNotNull(sample.value)
                    if (quantile != null && quantile in 0.0..1.0 && value >= 0.0) quantile to value else null
                }.distinctBy { it.first }.sortedBy { it.first }
                if (values.isNotEmpty()) {
                    put("quantileValues", buildJsonArray {
                        values.forEach { (quantile, value) ->
                            add(buildJsonObject {
                                put("quantile", quantile)
                                putNumber("value", value)
                            })
                        }
                    })
                }
            }
        }
        return metric(point.name, "summary", data, unit = if (point.type == "timer") "ms" else null)
    }

    private fun dataPoint(tags: Map<String, String>, fields: JsonObjectBuilder.() -> Unit): JsonObject =
        buildJsonObject {
            put("timeUnixNano", epochNanos.toString())
            if (tags.isNotEmpty()) {
                put("attributes", buildJsonArray {
                    tags.forEach { (key, value) ->
                        add(buildJsonObject {
                            put("key", key)
                            put("value", buildJsonObject { put("stringValue", value) })
                        })
                    }
                })
            }
            fields()
        }

    private fun metric(
        name: String,
        type: String,
        dataPoint: JsonObject,
        unit: String? = null,
        fields: JsonObjectBuilder.() -> Unit = {}
    ): String {
        val metric = buildJsonObject {
            put("name", name)
            unit?.let { put("unit", it) }
            put(type, buildJsonObject {
                fields()
                put("dataPoints", buildJsonArray { add(dataPoint) })
            })
        }

        val scope = buildJsonObject { put("metrics", buildJsonArray { add(metric) }) }
        val resource = buildJsonObject { put("scopeMetrics", buildJsonArray { add(scope) }) }
        val request = buildJsonObject { put("resourceMetrics", buildJsonArray { add(resource) }) }
        return DEFAULT_JSON.encodeToString(JsonObject.serializer(), request)
    }

    private fun JsonObjectBuilder.putNumber(key: String, value: Double) {
        when (value) {
            Double.POSITIVE_INFINITY -> put(key, "Infinity")
            Double.NEGATIVE_INFINITY -> put(key, "-Infinity")
            else -> if (value.isNaN()) put(key, "NaN") else put(key, value)
        }
    }
}
