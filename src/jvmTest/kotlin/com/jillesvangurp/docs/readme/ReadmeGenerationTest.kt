package com.jillesvangurp.docs.readme

import com.jillesvangurp.kotlin4example.SourceRepository
import java.io.File
import kotlin.test.Test

// FIXME adjust
const val githubLink = "https://github.com/formation-res/pg-docstore"

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
                    # MyNewKmpProject
        
                """.trimIndent().trimMargin() +
                    "\n\n" +
                    readmeMd.value
            )
    }
}

val readmeMd =
    sourceGitRepository.md {
        includeMdFile("intro.md")

        section("Example") {
            +"""
                This README uses [kotlin4example](https://github.com/jillesvangurp/kotlin4example) so you are all set up to show of your library with working examples.   
            """
                .trimIndent()
            subSection("Hello World") {
                example {
                    // prints hello world
                    println("Hello World!")
                }.let {
                        +"""
                           You can actually grab the output and show it in another code block:
                        """
                            .trimIndent()

                        mdCodeBlock(it.stdOut, type = "text")
                    }
            }
        }
        includeMdFile("outro.md")
    }
