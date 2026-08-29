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
        versionCode = 1788001370
        versionName = "6.20260829.200250"
    }

    val signingStoreFile = System.getenv("SIGNING_STORE_FILE")
    val signingStorePassword = System.getenv("SIGNING_STORE_PASSWORD")
    val signingKeyAlias = System.getenv("SIGNING_KEY_ALIAS")
    val signingKeyPassword = System.getenv("SIGNING_KEY_PASSWORD")

    signingConfigs {
        if (!signingStoreFile.isNullOrBlank() &&
            !signingStorePassword.isNullOrBlank() &&
            !signingKeyAlias.isNullOrBlank() &&
            !signingKeyPassword.isNullOrBlank()) {
            create("fixed") {
                storeFile = file(signingStoreFile)
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
            }
        }
    }

    buildTypes {
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
