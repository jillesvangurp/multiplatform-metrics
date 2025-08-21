package com.jillesvangurp.multiplatformmetrics

/** A gauge representing the last [set] value. */
interface Gauge {
    /** Update the gauge to [value]. */
    fun set(value: Double)
}
