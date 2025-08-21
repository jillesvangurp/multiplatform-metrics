// commonMain
@file:OptIn(ExperimentalStdlibApi::class)
package com.jillesvangurp.multiplatformmetrics

import com.jillesvangurp.serializationext.DEFAULT_JSON
import com.jillesvangurp.serializationext.DEFAULT_PRETTY_JSON
import kotlinx.serialization.Serializable

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
}

