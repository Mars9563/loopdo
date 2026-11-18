import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Plain Kotlin/JVM client code shared by the phone (android/) and the laptop (desktop/, M10):
// sync schema + upload, actions, data/models, auth, reminders. No Android here (D1).
java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

dependencies {
    api(project(":shared"))
    api(libs.powersync.core)
    api(platform(libs.supabase.bom))
    api(libs.supabase.auth)
    api(libs.kotlinx.serialization.json)
    api(libs.ktor.client.core)
    // The phone's view models (D2): androidx.lifecycle ViewModel is multiplatform (JVM artifact on the laptop).
    api(libs.androidx.lifecycle.viewmodel)

    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
}
