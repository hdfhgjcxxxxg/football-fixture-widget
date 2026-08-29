plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.footballfixturewidget"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.footballfixturewidget"
        minSdk = 26
        targetSdk = 36
        versionCode = 1900000000
        versionName = "7.0"
    }

    val signingStoreFile = System.getenv("SIGNING_STORE_FILE")
    val signingStorePassword = System.getenv("SIGNING_STORE_PASSWORD")
    val signingKeyAlias = System.getenv("SIGNING_KEY_ALIAS")
    val signingKeyPassword = System.getenv("SIGNING_KEY_PASSWORD")

    signingConfigs {
        if (!signingStoreFile.isNullOrBlank() &&
            !signingStorePassword.isNullOrBlank() &&
            !signingKeyAlias.isNullOrBlank() &&
            !signingKeyPassword.isNullOrBlank()
        ) {
            create("fixed") {
                storeFile = file(signingStoreFile)
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
            }
        }
    }

    buildTypes {
        getByName("debug") {
            if (signingConfigs.names.contains("fixed")) {
                signingConfig = signingConfigs.getByName("fixed")
            }
        }
        getByName("release") {
            isMinifyEnabled = false
            if (signingConfigs.names.contains("fixed")) {
                signingConfig = signingConfigs.getByName("fixed")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
}
