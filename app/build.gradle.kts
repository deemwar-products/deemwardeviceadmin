plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.deemwar.deviceadmin"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.deemwar.deviceadmin"
        minSdk = 26
        targetSdk = 35
        versionCode = 12
        versionName = "3.1"
    }

    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("KS_PATH") ?: "missing.jks")
            storePassword = System.getenv("KS_PASS")
            keyAlias = "deemwar-deviceadmin"
            keyPassword = System.getenv("KS_PASS")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    testImplementation("junit:junit:4.13.2")
    // org.json is provided by Android at runtime; pull a real copy for JVM tests
    testImplementation("org.json:json:20240303")
}
