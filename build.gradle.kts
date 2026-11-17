@file:OptIn(ExperimentalWasmDsl::class)

import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    `maven-publish`
}

val ghUser = "jillesvangurp"
val ghProjectName = "multiplatform-metrics"

repositories {
    mavenCentral()
    maven(url = "https://jitpack.io") {
        content {
            includeGroup("com.github.jillesvangurp")
        }
    }
    maven("https://maven.tryformation.com/releases")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}
kotlin {
    jvm {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_21
        }
    }
    js {
        nodejs {
            testTask {
                useMocha {
                    // javascript is a lot slower than Java, we hit the default timeout of 2000
                    timeout = "60s"
                }
            }
        }
    }
    linuxX64()
    linuxArm64()
    mingwX64()
    macosX64()
    macosArm64()
    iosArm64()
    iosSimulatorArm64()
    iosX64()
    wasmJs {
        browser()
        nodejs()
        d8()
    }

    sourceSets {
        commonMain {
            dependencies {
                api(libs.kotlinx.serialization.json)
                api(libs.serialization.extensions)
                api(libs.atomicfu)
            }
        }

        commonTest {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotest.assertions.core)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
        jvmMain {
            dependencies {
                api(libs.micrometer.core)
            }
        }
        jvmTest {
            dependencies {
                runtimeOnly(libs.logback.classic)
                implementation(libs.kotlin4example)
                implementation(libs.junit.jupiter)
                runtimeOnly(libs.junit.platform.launcher)
            }
        }
    }
}

tasks.named("iosSimulatorArm64Test") {
    // requires IOS simulator and tens of GB of other stuff to be installed
    // so keep it disabled
    enabled = false
}


publishing {
    publications {
        withType<MavenPublication> {
            pom {
                url.set("https://github.com/$ghUser/$ghProjectName")

                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://github.com/$ghUser/$ghProjectName/blob/master/LICENSE")
                    }
                }

                developers {
                    developer {
                        id.set("jillesvangurp")
                        name.set("Jilles van Gurp")
                        email.set("jilles@no-reply.github.com")
                    }
                }

                scm {
                    connection.set("scm:git:git://github.com/$ghUser/$ghProjectName.git")
                    developerConnection.set("scm:git:ssh://github.com:$ghUser/$ghProjectName.git")
                    url.set("https://github.com/$ghUser/$ghProjectName")
                }
            }
        }
    }
    repositories {
        // setup publishing repo for https://maven.tryformation.com/releases
        maven {
            // GOOGLE_APPLICATION_CREDENTIALS env var must be set for this to work
            // public repository is at https://maven.tryformation.com/releases
            url = uri("gcs://mvn-public-tryformation/releases")
            name = "FormationPublic"
        }
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
    // run tests in parallel
    systemProperties["junit.jupiter.execution.parallel.enabled"] = "true"
    // executes test classes concurrently
    systemProperties["junit.jupiter.execution.parallel.mode.default"] = "concurrent"
    // executes tests inside a class concurrently
    systemProperties["junit.jupiter.execution.parallel.mode.classes.default"] = "concurrent"
    systemProperties["junit.jupiter.execution.parallel.config.strategy"] = "dynamic"
    systemProperties["junit.jupiter.execution.parallel.config.executor-service"] = "FORK_JOIN_POOL"
    // random order of test class execution
    systemProperties["junit.jupiter.testclass.order.default"] = "org.junit.jupiter.api.ClassOrderer\$Random"

    testLogging.exceptionFormat = TestExceptionFormat.FULL
    testLogging.events = setOf(
        TestLogEvent.FAILED,
        TestLogEvent.PASSED,
        TestLogEvent.SKIPPED,
        TestLogEvent.STANDARD_ERROR,
        TestLogEvent.STANDARD_OUT
    )
}



