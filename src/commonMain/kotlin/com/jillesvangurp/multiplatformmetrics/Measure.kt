package com.jillesvangurp.multiplatformmetrics

import kotlin.time.TimeSource

/** Measure [block] and emit metrics prefixed with [prefix]. Returns the result of [block]. */
inline fun <T> IMeterRegistry.measure(
    prefix: String,
    tags: Map<String, String> = emptyMap(),
    block: () -> T
): T {
    val mark = TimeSource.Monotonic.markNow()
    return try {
        val result = block()
        timer("$prefix.duration", tags).record(mark.elapsedNow())
        counter("$prefix.success", tags).inc()
        counter("$prefix.total", tags).inc()
        result
    } catch (e: Throwable) {
        timer("$prefix.duration", tags).record(mark.elapsedNow())
        counter("$prefix.total", tags).inc()
        counter(
            "$prefix.failure",
            tags + ("exception" to (e::class.simpleName ?: "unknown"))
        ).inc()
        throw e
    }
}

/** Measure a [block] returning [Result] and emit metrics prefixed with [prefix]. Returns the [Result]. */
inline fun <T> IMeterRegistry.measureResult(
    prefix: String,
    tags: Map<String, String> = emptyMap(),
    block: () -> Result<T>
): Result<T> {
    val mark = TimeSource.Monotonic.markNow()
    val result = try {
        block()
    } catch (e: Throwable) {
        timer("$prefix.duration", tags).record(mark.elapsedNow())
        counter("$prefix.total", tags).inc()
        counter(
            "$prefix.failure",
            tags + ("exception" to (e::class.simpleName ?: "unknown"))
        ).inc()
        throw e
    }
    timer("$prefix.duration", tags).record(mark.elapsedNow())
    counter("$prefix.total", tags).inc()
    if (result.isSuccess) {
        counter("$prefix.success", tags).inc()
    } else {
        val e = result.exceptionOrNull()
        counter(
            "$prefix.failure",
            tags + ("exception" to (e?.let { it::class.simpleName } ?: "unknown"))
        ).inc()
    }
    return result
}
