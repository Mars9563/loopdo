import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

// loopdo_ for the Linux laptop (M10): Compose for desktop on top of client/ (phone's logic) and shared/ (rules).
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
    implementation(project(":client"))
    implementation(compose.desktop.currentOs)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.dbus.java.core)
    implementation(libs.dbus.java.unixsocket)

    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
    testImplementation(compose.uiTest)
}

tasks.test {
    // The real data folder is never touched by tests.
    environment("LOOPDO_DATA_DIR", layout.buildDirectory.dir("test-data").get().asFile.path)
    // Live tests (real server, the throwaway test user) run only with -Ploopdo.live=1.
    systemProperty("loopdo.live", providers.gradleProperty("loopdo.live").orNull ?: "")
    outputs.upToDateWhen { providers.gradleProperty("loopdo.live").orNull == null }
}

compose.desktop {
    application {
        mainClass = "app.loopdo.desktop.MainKt"
        nativeDistributions {
            packageName = "loopdo"
            packageVersion = "1.0.0" // keep in step with DesktopGraph.VERSION
            description = "loopdo_: to-dos, habits and a daily journal"
            linux { iconFile.set(project.file("packaging/loopdo.png")) }
        }
    }
}
