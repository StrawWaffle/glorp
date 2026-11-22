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
        debug {
            // Dev builds talk to destiny.gg's test site, so experiments never
            // touch the real chat.
            applicationIdSuffix = ".dev"
            buildConfigField("String", "SITE_ORIGIN", "\"https://www.omniliberal.dev\"")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("String", "SITE_ORIGIN", "\"https://www.destiny.gg\"")
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
