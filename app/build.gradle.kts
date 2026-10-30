plugins {
    id("com.android.application")
}

android {
    namespace = "com.c7t.concurrentcamera"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.c7t.concurrentcamera"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
