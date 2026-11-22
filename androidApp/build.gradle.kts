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
        versionCode = 1
        versionName = "0.1.0"
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
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("String", "SITE_ORIGIN", "\"https://www.destiny.gg\"")
            buildConfigField("boolean", "DEV_IMPORT", "false")
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
