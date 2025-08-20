package com.jillesvangurp.docs.readme

import com.jillesvangurp.kotlin4example.SourceRepository
import com.jillesvangurp.multiplatformmetrics.MicrometerMeterRegistry
import com.jillesvangurp.multiplatformmetrics.SimpleMeterRegistry
import java.io.File
import kotlin.test.Test

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
                }
            }
            subSection("Micrometer on the JVM") {
                example {
                    val micrometer = io.micrometer.core.instrument.simple.SimpleMeterRegistry()
                    val registry = MicrometerMeterRegistry(micrometer)
                    val gauge = registry.gauge("temp")
                    gauge.set(12.3)
                    println(micrometer.get("temp").gauge().value())
                }
            }
        }
        includeMdFile("outro.md")
    }
