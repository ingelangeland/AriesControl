plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "no.ariescontrol"
    compileSdk = 35
    defaultConfig {
        applicationId = "no.ariescontrol"
        minSdk = 29
        targetSdk = 35
        // Nytt versjonsnummer for hvert bygg (brukes av den innebygde oppdateringen)
        val run = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionCode = run
        versionName = "1.0.$run"
    }
    // Fast signeringsnøkkel, så nye versjoner kan installeres oppå de gamle (privat bruk).
    signingConfigs {
        create("aries") {
            storeFile = rootProject.file("aries.keystore")
            storePassword = "aries123"
            keyAlias = "aries"
            keyPassword = "aries123"
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("aries") }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("aries")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}
dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
