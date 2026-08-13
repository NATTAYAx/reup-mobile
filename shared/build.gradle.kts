plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.androidMultiplatformLibrary)
}

kotlin {
    // ── JVM ──────────────────────────────────────────────────────────────────
    // Not a shipping target. It exists so the vector suite runs in a second on
    // a laptop instead of a minute on a phone, and so CI can prove the logic
    // without an emulator.
    jvm {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            }
        }
        testRuns["test"].executionTask.configure {
            useJUnitPlatform()

            // Without this, a green build is ambiguous: "BUILD SUCCESSFUL" is
            // also what you get when the test framework finds zero tests and
            // runs none of them. Printing the count turns silence into a fact.
            testLogging {
                events("passed", "skipped", "failed")
                showStandardStreams = true
            }
            addTestListener(object : TestListener {
                override fun beforeSuite(suite: TestDescriptor) {}
                override fun beforeTest(test: TestDescriptor) {}
                override fun afterTest(test: TestDescriptor, result: TestResult) {}
                override fun afterSuite(suite: TestDescriptor, result: TestResult) {
                    if (suite.parent == null) {
                        logger.lifecycle(
                            "\n${result.testCount} tests: " +
                                    "${result.successfulTestCount} passed, " +
                                    "${result.failedTestCount} failed, " +
                                    "${result.skippedTestCount} skipped"
                        )
                        if (result.testCount == 0L) {
                            throw GradleException(
                                "No tests ran. A build that passes without running anything is worse " +
                                        "than one that fails, because it looks like proof."
                            )
                        }
                    }
                }
            })
        }
    }

    // ── Android ──────────────────────────────────────────────────────────────
    // This is the AGP 9 way and it is genuinely confusing, so: the block is
    // called `android` but it sits INSIDE `kotlin {}`, not at the top level,
    // and it comes from com.android.kotlin.multiplatform.library rather than
    // com.android.library. Google split the KMP case out into its own plugin
    // because the old one leaned on APIs being removed in AGP 10.
    //
    // If this errors with "unresolved reference: android", rename it to
    // `androidLibrary` — that was its name before AGP 8.12 and it still works,
    // just deprecated.
    android {
        namespace = "app.reup.core"
        compileSdk = 37
        minSdk = 26

        // Must match the app module's compileOptions (Java 11). A library
        // compiled to newer bytecode than its consumer fails at link time with
        // a message about class file versions that says nothing about the
        // actual cause.
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }

    // ── iOS ──────────────────────────────────────────────────────────────────
    // Declared from day one even though a Windows machine cannot build them,
    // and that is the point. Gradle skips them locally without complaint, and
    // the macOS runner in CI does not — so the day an Android import lands in
    // commonMain, the build goes red that same day rather than a year later
    // with eight thousand lines wrapped around it.
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // `api`, not `implementation`: Instant and TimeZone appear in the
            // signature of nextReset(), so anything calling it needs them on
            // its own classpath. Hiding a type that is part of the public API
            // means every consumer has to redeclare the same dependency.
            api(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        jvmTest.dependencies {
            implementation(libs.kotlin.test.junit5)
            // Only the vector test parses JSON. It used to sit in commonMain,
            // which meant shipping a serialization runtime to Android and iOS
            // to support a file neither of them reads.
            implementation(libs.kotlinx.serialization.json)
            runtimeOnly(libs.junit.platform.launcher)
        }
    }
}