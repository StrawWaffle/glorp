import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "io.github.strawwaffle.glorp"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.strawwaffle.glorp"
        minSdk = 26
        targetSdk = 37
        // Set in gradle.properties (`glorp.version`); scripts/release.sh tags it.
        // 1.2.3 becomes version code 10203, so every release counts up.
        versionName = providers.gradleProperty("glorp.version").get()
        versionCode = versionName!!.split('.').map { it.toInt() }.let { (major, minor, patch) -> major * 10_000 + minor * 100 + patch }
    }

    // The release key lives outside the repo (see AGENTS.md). Every release
    // must be signed with it, or phones refuse the update.
    val releaseKey = File(System.getProperty("user.home"), ".secrets/glorp/release.properties")
    if (releaseKey.exists()) {
        val props = Properties().apply { releaseKey.inputStream().use(::load) }
        signingConfigs.create("release") {
            storeFile = file(props.getProperty("storeFile"))
            storePassword = props.getProperty("storePassword")
            keyAlias = props.getProperty("keyAlias")
            keyPassword = props.getProperty("keyPassword")
        }
    }

    buildTypes {
        // Debug and dev builds talk to destiny.gg's test site, so experiments
        // never touch the real chat, and install beside a release as
        // "Glorp (dev)". Both can take a test login from a file (see
        // MainActivity).
        debug {
            applicationIdSuffix = ".dev"
            buildConfigField("String", "SITE_ORIGIN", "\"https://www.omniliberal.dev\"")
            buildConfigField("boolean", "DEV_IMPORT", "true")
            buildConfigField("boolean", "SELF_UPDATE", "false")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("String", "SITE_ORIGIN", "\"https://www.destiny.gg\"")
            buildConfigField("boolean", "DEV_IMPORT", "false")
            // Only releases update themselves; dev builds come over adb.
            buildConfigField("boolean", "SELF_UPDATE", "true")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
        // What goes on a phone for trying things: optimised like a release,
        // because Compose in a debug build is several times slower and says
        // nothing about how the app really runs.
        create("dev") {
            initWith(getByName("release"))
            applicationIdSuffix = ".dev"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            buildConfigField("String", "SITE_ORIGIN", "\"https://www.omniliberal.dev\"")
            buildConfigField("boolean", "DEV_IMPORT", "true")
            buildConfigField("boolean", "SELF_UPDATE", "false")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.activity.compose)
}
