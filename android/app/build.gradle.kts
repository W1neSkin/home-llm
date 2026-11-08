plugins {
    id("com.android.application")
}

android {
    namespace = "app.lamp"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.lamp"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Иначе файл называется app-debug.apk. Для установки нужно обычное имя.
android.applicationVariants.configureEach {
    outputs.configureEach {
        (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName = "LocAI.apk"
    }
}
