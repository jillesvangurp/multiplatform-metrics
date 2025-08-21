package com.jillesvangurp.multiplatformmetrics

/** A monotonically increasing counter. */
interface Counter {
    /** Increment the counter by [delta]. */
    fun inc(delta: Long = 1)
}
