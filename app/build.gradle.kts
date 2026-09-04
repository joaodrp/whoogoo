plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val version = System.getenv("WHOOGOO_VERSION")?.removePrefix("v") ?: "0.0.0-dev"

android {
    namespace = "dev.joaodrp.whoogoo"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.joaodrp.whoogoo"
        minSdk = 34
        targetSdk = 36
        versionName = version
        // Monotonic per release so a sideloaded APK updates over the previous one.
        val (major, minor, patch) = version.substringBefore('-').split('.').map(String::toInt)
        versionCode = 1 + major * 1_000_000 + minor * 1000 + patch
    }
    buildFeatures.compose = true
    // Throwaway key committed on purpose: every release must install over the previous one.
    signingConfigs {
        getByName("debug") {
            storeFile = file("whoogoo.jks")
            storePassword = "whoogoo"
            keyAlias = "whoogoo"
            keyPassword = "whoogoo"
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.03.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.health.connect:connect-client:1.1.0")
    testImplementation("junit:junit:4.13.2")
}
