// Runs Grayjay source plugins. Parts of this module come from Grayjay
// (FUTO, Source First License 1.1, see LICENSE.md and NOTICE.md); the rest of
// Glorp is under its own licence.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
}

// Grayjay's prelude scripts (js/), embedded as Kotlin strings so every
// platform has them without resource loading.
val generatePrelude by tasks.registering {
    val sources = layout.projectDirectory.dir("js")
    val out = layout.buildDirectory.dir("generated/prelude/kotlin")
    inputs.dir(sources)
    outputs.dir(out)
    doLast {
        val dir = out.get().asFile.resolve("io/github/strawwaffle/glorp/grayjay").apply { mkdirs() }
        val text = buildString {
            appendLine("// Generated from grayjay/js. Do not edit.")
            appendLine("package io.github.strawwaffle.glorp.grayjay")
            appendLine()
            appendLine("internal object Prelude {")
            for (file in sources.asFile.listFiles().orEmpty().filter { it.extension == "js" }.sortedBy { it.name }) {
                val name = file.nameWithoutExtension.uppercase().replace('-', '_')
                // Split into pieces: one JVM string constant can't exceed 64 KB.
                val pieces = file.readText().chunked(16_000).joinToString(",\n        ") { piece ->
                    "\"" + piece.replace("\\", "\\\\").replace("\"", "\\\"").replace("\$", "\\\$")
                        .replace("\r", "").replace("\n", "\\n") + "\""
                }
                appendLine("    val $name: String = listOf(\n        $pieces,\n    ).joinToString(\"\")")
            }
            appendLine("}")
        }
        dir.resolve("Prelude.kt").writeText(text)
    }
}

kotlin {
    android {
        namespace = "io.github.strawwaffle.glorp.grayjay"
        compileSdk = 37
        minSdk = 26
    }
    // Desktop JVM: for tests that run real plugins on a development machine.
    jvm()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain {
            kotlin.srcDir(generatePrelude)
            dependencies {
                implementation(libs.quickjs)
                implementation(libs.ktor.client.core)
                implementation(libs.coroutines.core)
                implementation(libs.serialization.json)
                implementation(libs.okio)
            }
        }
        jvmTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.coroutines.test)
        }
    }
}

// The YouTube probe runs only when asked: ./gradlew :grayjay:jvmTest -Pglorp.probe=<url>
tasks.named<Test>("jvmTest") {
    (findProperty("glorp.probe") as String?)?.let { systemProperty("glorp.probe", it) }
    testLogging { showStandardStreams = true }
    outputs.upToDateWhen { false }
}
