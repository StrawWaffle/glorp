import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "io.github.strawwaffle.glorp"
    compileSdk = 37

    // Native libraries (QuickJS, libavif) compressed in the APK: one APK holds
    // every processor's copy, and installing unpacks only the one in use.
    packaging { jniLibs { useLegacyPackaging = true } }

    defaultConfig {
        applicationId = "io.github.strawwaffle.glorp"
        minSdk = 26
        targetSdk = 37
        // Set in gradle.properties (`glorp.version`); scripts/release.sh tags it.
        // 1.2.3 becomes version code 10203, so every release counts up.
        versionName = providers.gradleProperty("glorp.version").get()
        // 0.1.23-test.2 → 12302, 0.1.23 → 12399: a test version installs over
        // the release before it, and its own release installs over it.
        versionCode = versionName!!.let { name ->
            val (major, minor, patch) = name.substringBefore('-').split('.').map { it.toInt() }
            val test = name.substringAfter("-test.", "").toIntOrNull()?.also { require(it in 1..98) } ?: 99
            ((major * 100 + minor) * 100 + patch) * 100 + test
        }
        // A test version is "Glorp Probe" on the phone, so it's plain which one
        // is installed; its release turns it back into Glorp.
        manifestPlaceholders["appLabel"] = if ("-test." in versionName!!) "Glorp Probe" else "Glorp"
        // The mentions service (server/), for mention notifications without the
        // site's login; empty leaves them to the site's own list (cookies only).
        buildConfigField("String", "MENTIONS_URL", "\"${providers.gradleProperty("glorp.mentionsUrl").orElse("").get()}\"")
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
            manifestPlaceholders["appLabel"] = "Glorp (dev)"
            buildConfigField("String", "SITE_ORIGIN", "\"https://www.omniliberal.dev\"")
            buildConfigField("boolean", "DEV_IMPORT", "true")
            buildConfigField("boolean", "SELF_UPDATE", "false")
            // The mentions service watches destiny.gg, not the test site.
            buildConfigField("String", "MENTIONS_URL", "\"\"")
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
            manifestPlaceholders["appLabel"] = "Glorp (dev)"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            buildConfigField("String", "SITE_ORIGIN", "\"https://www.omniliberal.dev\"")
            buildConfigField("boolean", "DEV_IMPORT", "true")
            buildConfigField("boolean", "SELF_UPDATE", "false")
            buildConfigField("String", "MENTIONS_URL", "\"\"")
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
