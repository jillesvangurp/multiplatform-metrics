package com.jillesvangurp.multiplatformmetrics

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** A timer that records durations. */
interface Timer {
    /** Record the execution time of [block] and returns its result. */
    fun <T> record(block: () -> T): T

    /** Add an explicit [duration]. */
    fun record(duration: Duration)
}
