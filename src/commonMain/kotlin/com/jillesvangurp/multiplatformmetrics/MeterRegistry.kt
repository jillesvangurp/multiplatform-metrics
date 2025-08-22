// commonMain
@file:OptIn(ExperimentalStdlibApi::class)
package com.jillesvangurp.multiplatformmetrics

import com.jillesvangurp.serializationext.DEFAULT_JSON
import com.jillesvangurp.serializationext.DEFAULT_PRETTY_JSON
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

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

    /** Create or retrieve a [Timer] named [name] with optional [tags] and [config]. */
    fun timer(
        name: String,
        tags: Map<String, String> = emptyMap(),
        config: TimerConfig = TimerConfig()
    ): Timer

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

    /** Export metrics as OpenTelemetry JSON lines. */
    fun toOpenTelemetryJsonLines(): List<String> = points.map { it.toOpenTelemetryJsonLine() }

    /** Export metrics in Prometheus text format. */
    fun toPrometheusLines(): List<String> = points.flatMap { it.toPrometheusLines() }
}

private fun MetricPoint.toOpenTelemetryJsonLine(): String {
    val attributes = buildJsonArray {
        tags.forEach { (k, v) ->
            add(
                buildJsonObject {
                    put("key", k)
                    put("value", buildJsonObject { put("stringValue", v) })
                }
            )
        }
    }

    val dataPoint = when (type) {
        "counter" -> buildJsonObject {
            if (attributes.isNotEmpty()) put("attributes", attributes)
            count?.let { put("asDouble", it.toDouble()) }
            put("startTimeUnixNano", "0")
            put("timeUnixNano", "0")
        }

        "gauge" -> buildJsonObject {
            if (attributes.isNotEmpty()) put("attributes", attributes)
            value?.let { put("asDouble", it) }
            put("startTimeUnixNano", "0")
            put("timeUnixNano", "0")
        }

        else -> buildJsonObject {
            if (attributes.isNotEmpty()) put("attributes", attributes)
            value?.let { put("asDouble", it) }
            count?.let { put("count", it) }
            sum?.let { put("sum", it) }
            min?.let { put("min", it) }
            max?.let { put("max", it) }
            put("startTimeUnixNano", "0")
            put("timeUnixNano", "0")
        }
    }

    val metric = buildJsonObject {
        put("name", name)
        when (type) {
            "counter" -> put(
                "sum",
                buildJsonObject {
                    put("aggregationTemporality", "AGGREGATION_TEMPORALITY_CUMULATIVE")
                    put("isMonotonic", true)
                    put("dataPoints", buildJsonArray { add(dataPoint) })
                }
            )

            "gauge" -> put(
                "gauge",
                buildJsonObject { put("dataPoints", buildJsonArray { add(dataPoint) }) }
            )

            else -> put(
                "summary",
                buildJsonObject { put("dataPoints", buildJsonArray { add(dataPoint) }) }
            )
        }
    }

    val root = buildJsonObject {
        put(
            "resourceMetrics",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put(
                            "scopeMetrics",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("metrics", buildJsonArray { add(metric) })
                                    }
                                )
                            }
                        )
                    }
                )
            }
        )
    }

    return DEFAULT_JSON.encodeToString(JsonObject.serializer(), root)
}

private fun MetricPoint.toPrometheusLines(): List<String> {
    fun sanitize(s: String) = s.replace('.', '_')

    fun formatTags(tags: Map<String, String>): String =
        if (tags.isEmpty()) ""
        else tags.toList().sortedBy { it.first }
            .joinToString(",", prefix = "{", postfix = "}") { (k, v) -> "${sanitize(k)}=\"$v\"" }

    val baseName = sanitize(name)
    val tagString = formatTags(tags)
    val lines = mutableListOf<String>()

    when (type) {
        "counter" -> count?.let { lines += "$baseName$tagString ${it.toString()}" }
        "gauge" -> value?.let { lines += "$baseName$tagString ${it.toString()}" }
        "timer", "summary" -> {
            value?.let { lines += "$baseName$tagString ${it.toString()}" }
            count?.let { lines += "${baseName}_count$tagString ${it.toString()}" }
            sum?.let { lines += "${baseName}_sum$tagString ${it.toString()}" }
            min?.let { lines += "${baseName}_min$tagString ${it.toString()}" }
            max?.let { lines += "${baseName}_max$tagString ${it.toString()}" }
        }
        "long_task_timer" -> {
            count?.let { lines += "${baseName}_count$tagString ${it.toString()}" }
            sum?.let { lines += "${baseName}_sum$tagString ${it.toString()}" }
        }
        else -> value?.let { lines += "$baseName$tagString ${it.toString()}" }
    }

    return lines
}

