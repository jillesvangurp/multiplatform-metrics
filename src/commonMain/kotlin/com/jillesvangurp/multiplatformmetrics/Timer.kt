package com.jillesvangurp.multiplatformmetrics

import kotlin.time.Duration

/** A timer that records durations. */
interface Timer {
    /** Record the execution time of [block] and returns its result. */
    suspend fun <T> record(block: suspend () -> T): T

    /** Add an explicit [duration]. */
    fun record(duration: Duration)
}

/**
 * Configuration for [Timer] percentiles and SLA boundaries.
 *
 * [SimpleMeterRegistry] calculates percentiles over the latest 1,024 observations.
 * Timer totals and SLA counts include all recorded observations.
 */
data class TimerConfig(
    val percentiles: List<Double> = emptyList(),
    val sla: List<Duration> = emptyList()
)
