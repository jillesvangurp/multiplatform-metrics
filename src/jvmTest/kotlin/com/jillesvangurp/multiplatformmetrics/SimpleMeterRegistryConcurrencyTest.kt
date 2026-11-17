package com.jillesvangurp.multiplatformmetrics

import io.kotest.matchers.shouldBe
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds

class SimpleMeterRegistryConcurrencyTest {
    @Test
    fun concurrentRegistrationAndRecordingKeepSnapshotsConsistent() {
        val registry = SimpleMeterRegistry()
        val config = TimerConfig(percentiles = listOf(0.5), sla = listOf(3.milliseconds))
        val workers = 8
        val recordsPerWorker = 500
        val start = CyclicBarrier(workers + 1)
        Executors.newFixedThreadPool(workers + 1).use { pool ->
            val updates = List(workers) {
                pool.submit {
                    start.await(5, TimeUnit.SECONDS)
                    repeat(recordsPerWorker) {
                        registry.counter("hits").inc()
                        registry.timer("latency", config = config).record(2.milliseconds)
                        registry.summary("payload").record(3.0)
                    }
                }
            }
            val snapshots = pool.submit {
                start.await(5, TimeUnit.SECONDS)
                repeat(recordsPerWorker) {
                    val points = registry.snapshot().points
                    val bySeries = points.associateBy { it.name to it.tags }
                    bySeries.size shouldBe points.size
                    bySeries["latency" to emptyMap()]?.let { timer ->
                        timer.sum shouldBe (timer.count!! * 2.0)
                        bySeries.getValue("latency" to mapOf("sla" to "3")).count shouldBe timer.count
                    }
                    bySeries["payload" to emptyMap()]?.let { summary ->
                        summary.sum shouldBe (summary.count!! * 3.0)
                    }
                }
            }
            (updates + snapshots).forEach { it.get(15, TimeUnit.SECONDS) }
        }

        val finalCounts = registry.snapshot().points.filter { it.count != null }
        finalCounts.size shouldBe 4
        finalCounts.forEach { it.count shouldBe (workers.toLong() * recordsPerWorker) }
    }
}
