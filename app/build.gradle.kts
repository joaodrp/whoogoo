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
        // Monotonic per release so a sideloaded APK updates over the previous one. Tolerates a
        // short or non-numeric tag rather than failing configuration after the tag is pushed.
        val parts = version.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        fun part(i: Int) = parts.getOrElse(i) { 0 }
        versionCode = 1 + part(0) * 1_000_000 + part(1) * 1000 + part(2)
    }
    buildFeatures.compose = true
    // Releases are signed with a key held in CI secrets, so every release installs over the
    // previous one. Without it, builds fall back to the local Android debug key, which means a
    // locally built APK will not install over a released one: uninstall first.
    val keystore = System.getenv("WHOOGOO_KEYSTORE")
    val keystorePassword = System.getenv("WHOOGOO_KEYSTORE_PASSWORD")
    if (!keystore.isNullOrBlank() && !keystorePassword.isNullOrBlank()) {
        signingConfigs {
            getByName("debug") {
                storeFile = file(keystore)
                storePassword = keystorePassword
                keyAlias = "whoogoo"
                keyPassword = keystorePassword
            }
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
