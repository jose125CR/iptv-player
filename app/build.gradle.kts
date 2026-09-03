import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp") version "1.9.22-1.0.17"
}

// Release signing reads from keystore.properties (gitignored - never committed) so the
// actual key/passwords never end up in source control or CI logs.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) keystorePropertiesFile.inputStream().use { load(it) }
}

/**
 * A credential as a quoted Java string literal for buildConfigField, from keystore.properties
 * or else the environment. Escaped rather than interpolated raw: a stray quote or backslash in
 * a secret would otherwise generate BuildConfig.java that doesn't compile, and the failure
 * would point at generated code rather than at the value that caused it.
 */
fun credentialField(propertyKey: String, envKey: String): String {
    val raw = (keystoreProperties[propertyKey] as? String)?.takeIf { it.isNotBlank() }
        ?: System.getenv(envKey).orEmpty()
    return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

android {
    namespace = "com.lumora"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.lumora"
        minSdk = 25
        targetSdk = 36
        versionCode = 36
        versionName = "1.0.0"

        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
        }

        // Trakt OAuth app credentials. They end up in the APK either way - a device-flow
        // client secret has to travel with the app, and Trakt's own guidance accepts that
        // for native clients - but keeping them out of the repo means a public checkout
        // carries no working key, and rotating one is a rebuild rather than a commit.
        //
        // Two sources, local first: keystore.properties (gitignored, same file the signing
        // config reads) for a developer build, then the environment for CI, which has no
        // such file and gets them from repository secrets instead. Absent from both, they
        // come through blank and the Trakt pane reports the build as unconfigured rather
        // than failing at the first request - so a fork with no secrets still builds.
        //
        // Register an app at https://trakt.tv/oauth/applications, then either add to
        // keystore.properties:
        //   traktClientId=...
        //   traktClientSecret=...
        // or set TRAKT_CLIENT_ID / TRAKT_CLIENT_SECRET in the environment.
        buildConfigField("String", "TRAKT_CLIENT_ID", credentialField("traktClientId", "TRAKT_CLIENT_ID"))
        buildConfigField("String", "TRAKT_CLIENT_SECRET", credentialField("traktClientSecret", "TRAKT_CLIENT_SECRET"))

        // TMDB v3 API keys, comma-separated - TmdbClient tries them in order and falls through
        // to the next on failure, so this stays a list rather than a single key. Same two
        // sources and the same graceful absence as the Trakt pair above: with none configured
        // TmdbClient.hasKey() is false, Discover shows its empty state, and the Series/Films
        // no-provider fallback and the Trakt title resolution quietly do nothing.
        //   tmdbApiKeys=key1,key2,key3
        // or TMDB_API_KEYS in the environment.
        buildConfigField("String", "TMDB_API_KEYS", credentialField("tmdbApiKeys", "TMDB_API_KEYS"))
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            // Distinct applicationId + label so a debug build installs alongside the release
            // "Lumora" on a device instead of replacing it. Manifest provider authorities are
            // already ${applicationId}-derived, so they follow the suffix automatically; the
            // label override lives in src/debug/res/values/strings.xml.
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += setOf("META-INF/LICENSE*", "META-INF/NOTICE*")
        }
    }
}

dependencies {
    // Core Android
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.3")

    // Media3 ExoPlayer - hardware-accelerated video playback
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.4.1")
    implementation("androidx.media3:media3-exoplayer-rtsp:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")

    // UI
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // Room Database
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // WorkManager - background sync
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Networking
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // JSON
    implementation("com.google.code.gson:gson:2.10.1")

    // QR code generation (ZXing)
    implementation("com.google.zxing:core:3.5.3")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Cast
    implementation("androidx.mediarouter:mediarouter:1.7.0")
    implementation("com.google.android.gms:play-services-cast-framework:21.5.0")

    // Media session / browse tree - what Android Auto's media category binds to (auto/).
    implementation("androidx.media3:media3-session:1.4.1")

    // Android Auto (see auto/ - CarAppService).
    implementation("androidx.car.app:app:1.7.0")

    // Android TV
    implementation("androidx.tvprovider:tvprovider:1.1.0")

    // Tests
    testImplementation("junit:junit:4.13.2")
}
