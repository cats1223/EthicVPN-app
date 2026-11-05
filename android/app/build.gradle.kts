plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.ethic.vpn"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ethic.vpn"
        minSdk = 26
        targetSdk = 35
        // CI stamps the build number, the same one the iOS build shows.
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        // CI passes the release tag (v1.2.0 -> 1.2.0); the update check compares it with the latest release.
        versionName = System.getenv("APP_VERSION")?.takeIf { it.isNotBlank() } ?: "1.1.0"
    }

    signingConfigs {
        // CI passes the project's fixed key (debug format), so every APK installs over the
        // previous one; locally Gradle's own debug key is used.
        getByName("debug") {
            System.getenv("ANDROID_KEYSTORE_PATH")?.let(::file)?.takeIf { it.exists() }?.let {
                storeFile = it
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // libXray (gomobile) ships native libs for all ABIs; split APKs keep downloads small.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
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
        compose = true
        buildConfig = true
    }
    sourceSets {
        // Russian IP ranges and sites for split tunneling, built by scripts/update-geodata.py.
        getByName("main").assets.srcDir(rootProject.file("../geodata"))
        // CHANGELOG.md for the "what's new" screen; copied in by copyChangelog below.
        getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/changelog"))
    }
    packaging {
        jniLibs.useLegacyPackaging = true
    }
}

val copyChangelog by tasks.registering(Copy::class) {
    from(rootProject.file("../CHANGELOG.md"))
    into(layout.buildDirectory.dir("generated/changelog"))
}
tasks.named("preBuild") { dependsOn(copyChangelog) }

dependencies {
    // LibXray.aar is produced by scripts/build-libxray.sh (see README).
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    // The home screen globe: MapLibre with free OpenFreeMap vector tiles, no API key. The
    // "-globe" build is the one that draws the globe projection; 13.x releases are flat only.
    implementation("org.maplibre.gl:android-sdk:2026.10.19-globe")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
