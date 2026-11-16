import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release key lives outside the repo (~/.config/loopdo); without it only debug builds sign.
val releaseProps = Properties().apply {
    val f = File(System.getProperty("user.home"), ".config/loopdo/release.properties")
    if (f.exists()) f.inputStream().use(::load)
}

android {
    namespace = "app.loopdo.android"
    compileSdk { version = release(37) }

    defaultConfig {
        applicationId = "app.loopdo"
        minSdk = 33 // SystemClock.currentNetworkTimeClock()
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }

        // Public client config (not secrets): the publishable key is meant to ship in apps.
        buildConfigField("String", "SUPABASE_URL", "\"https://jcnenvgdlryxlhsnkaiq.supabase.co\"")
        buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", "\"sb_publishable_s1x-441V8Vb2tHHivRNPIQ_x61QCk8b\"")
        buildConfigField("String", "POWERSYNC_URL", "\"https://6af99fc3e5558a7422ba3c92.powersync.journeyapps.com\"")
        buildConfigField("String", "API_URL", "\"https://loopdo.mrsdev.in\"")
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"408208720715-3pr2op2dbtdhijoq1jv9cr0k2dk7m2u2.apps.googleusercontent.com\"")
    }

    signingConfigs {
        if (releaseProps.isNotEmpty()) {
            create("release") {
                storeFile = File(releaseProps.getProperty("storeFile"))
                storePassword = releaseProps.getProperty("storePassword")
                keyAlias = releaseProps.getProperty("keyAlias")
                keyPassword = releaseProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            optimization { enable = true }
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(platform(libs.supabase.bom))
    implementation(libs.supabase.auth)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play)
    implementation(libs.googleid)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.powersync.core)

    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    testImplementation(libs.junit4)
}
