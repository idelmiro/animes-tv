plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.idelmiro.animestv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.idelmiro.animestv"
        minSdk = 26
        targetSdk = 34
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"

        // TVs usam processadores ARM; deixar de fora x86 deixa o APK bem menor.
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
    }

    signingConfigs {
        create("app") {
            storeFile = file("animestv.keystore")
            storePassword = "animestv"
            keyAlias = "animestv"
            keyPassword = "animestv"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("app")
        }
        debug {
            signingConfig = signingConfigs.getByName("app")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        buildConfig = true
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.tvprovider:tvprovider:1.0.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Player (mpv + libass para as legendas ASS dos fansubs)
    implementation("dev.jdtech.mpv:libmpv:0.4.1")

    // Torrent (libtorrent): download sequencial dentro do app
    val lt4j = "2.1.0-35"
    implementation("org.libtorrent4j:libtorrent4j:$lt4j")
    implementation("org.libtorrent4j:libtorrent4j-android-arm:$lt4j")
    implementation("org.libtorrent4j:libtorrent4j-android-arm64:$lt4j")
}
