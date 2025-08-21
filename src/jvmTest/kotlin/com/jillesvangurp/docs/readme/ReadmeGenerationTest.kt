package com.jillesvangurp.docs.readme

import com.jillesvangurp.kotlin4example.SourceRepository
import com.jillesvangurp.multiplatformmetrics.MicrometerMeterRegistry
import com.jillesvangurp.multiplatformmetrics.SimpleMeterRegistry
import com.jillesvangurp.multiplatformmetrics.TimerConfig
import java.io.File
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds

const val githubLink = "https://github.com/jillesvangurp/multiplatform-metrics"

val sourceGitRepository =
    SourceRepository(
        repoUrl = githubLink,
        sourcePaths = setOf("src/commonMain/kotlin", "src/commonTest/kotlin", "src/jvmTest/kotlin")
    )

class ReadmeGenerationTest {

    @Test
    fun `generate docs`() {
        File(".", "README.md")
            .writeText(
                """
                    # Multiplatform Metrics
        
                """.trimIndent().trimMargin() +
                    "\n\n" +
                    readmeMd.value
            )
    }
}

val readmeMd =
    sourceGitRepository.md {
        includeMdFile("intro.md")
        section("Examples") {
            subSection("Simple meter registry") {
                example {
                    val registry = SimpleMeterRegistry()
                    val counter = registry.counter("hits")
                    counter.inc()
                    println(registry.snapshot().toJson(pretty = true))
                }.let { out ->
                    +"""
                        Produces
                    """.trimIndent()
                    mdCodeBlock(out.stdOut, type = "text")
                }
            }
              subSection("Micrometer on the JVM") {
                  example {
                      val micrometer = io.micrometer.core.instrument.simple.SimpleMeterRegistry()
                      val registry = MicrometerMeterRegistry(micrometer)
                      val gauge = registry.gauge("temp")
                      gauge.set(12.3)
                      println(micrometer.get("temp").gauge().value())
                  }.let { out ->
                      +"""
                          Produces
                      """.trimIndent()
                      mdCodeBlock(out.stdOut, type = "text")
                  }
              }
              subSection("Timer percentiles and SLA") {
                  example {
                      val registry = SimpleMeterRegistry()
                      val timer = registry.timer(
                          "latency",
                          config = TimerConfig(percentiles = listOf(0.5), sla = listOf(50.milliseconds))
                      )
                      listOf(10, 50, 100).forEach { timer.record(it.milliseconds) }
                      println(registry.snapshot().toJson(pretty = true))
                  }.let { out ->
                      +"""
                          Produces
                      """.trimIndent()
                      mdCodeBlock(out.stdOut, type = "text")
                  }
              }
          }
          includeMdFile("outro.md")
      }
