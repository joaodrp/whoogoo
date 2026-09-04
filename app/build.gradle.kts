plugins {
    id("com.android.application")
}

android {
    namespace = "dev.joaodrp.whoopimport"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.joaodrp.whoopimport"
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "1"
    }
}

dependencies {
    implementation("androidx.health.connect:connect-client:1.1.0")
    implementation("androidx.activity:activity-ktx:1.13.0")
}
