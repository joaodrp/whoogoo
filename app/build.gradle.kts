plugins {
    id("com.android.application")
}

android {
    namespace = "dev.joaodrp.whoogoo"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.joaodrp.whoogoo"
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "1"
    }
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
    implementation("androidx.health.connect:connect-client:1.1.0")
    implementation("androidx.activity:activity-ktx:1.13.0")
}
