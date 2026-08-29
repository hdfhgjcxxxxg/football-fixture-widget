plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.footballfixturewidget"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.example.footballfixturewidget"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "3.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
