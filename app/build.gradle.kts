plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Optional release signing: only used when a real keystore is present (e.g. decoded
// from a CI secret). Falls back to the debug key so `assembleRelease` always works.
val releaseKeystorePath = System.getenv("KEYSTORE_PATH")
val hasReleaseKeystore = releaseKeystorePath != null && file(releaseKeystorePath).exists()

android {
    namespace = "com.videostream.local"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.videostream.local"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = file(releaseKeystorePath!!)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        viewBinding = true
    }

    testOptions {
        unitTests {
            // MediaHttpServerTest mocks ContentResolver/AssetManager/Uri with Mockito rather
            // than calling them for real, but this keeps any accidental un-mocked Android call
            // from hard-crashing the test with "not mocked" instead of just returning a default.
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.8.4")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
    // Native folder/video browsing grid on the Watch screen (replaces the WebView's HTML page).
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    // Native video playback for WatchActivity's player screen: broader, more consistent
    // container/codec support across OEMs/Android versions than the built-in MediaPlayer/
    // VideoView, plus built-in playlist support (autoplay/shuffle/prev-next come from the
    // player's own media-item queue instead of needing to be hand-rolled).
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")

    implementation("org.nanohttpd:nanohttpd:2.3.1")
    // WebSocket support for pushing /remote state changes instantly instead of polling.
    implementation("org.nanohttpd:nanohttpd-websocket:2.3.1")
    // WebSocket client for WatchActivity's remote-follow mode (server push above).
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.12.0")
    testImplementation("com.squareup.okhttp3:okhttp:4.12.0")
}
