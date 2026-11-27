plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// A separate installation for inspecting the factory state without touching the owner's app.
// Use: gradlew.bat assembleDebug -PcleanPreview -Pphone
val cleanPreview = providers.gradleProperty("cleanPreview").isPresent
if (cleanPreview) {
    layout.buildDirectory.set(rootProject.layout.buildDirectory.dir("clean-preview/app"))
}

android {
    namespace = "app.axiko"
    compileSdk = 36

    defaultConfig {
        applicationId = if (cleanPreview) "app.axiko.cleanpreview" else "app.axiko"
        manifestPlaceholders["axikoLabel"] = if (cleanPreview) "Axiko · чистая" else "Axiko"
        minSdk = 30
        // Deliberately below the device's level: restrictions that Android 14+ switches on
        // only for apps targeting it (typed foreground services, read-only dynamic code,
        // tighter background launches) stay off for a personal, sideloaded app.
        targetSdk = 33
        versionCode = 5
        versionName = "0.3.1"
        // The phone needs arm64 only; x86_64 is for the emulator. `-Pphone` leaves it out (about 70 MB less).
        ndk { abiFilters += if (project.hasProperty("phone")) listOf("arm64-v8a") else listOf("arm64-v8a", "x86_64") }
    }

    signingConfigs {
        // Android refuses an update signed with another key, and every machine has a debug key of
        // its own. A machine that builds for the owner's phone gets the key the installed app was
        // signed with as ~/.android/debug-axiko.keystore (standard debug alias and passwords);
        // without that file the machine's own debug key is used, as before.
        getByName("debug") {
            val shared = File(System.getProperty("user.home"), ".android/debug-axiko.keystore")
            if (shared.isFile) storeFile = shared
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        // PRoot ships as native libraries and is run as a program: it must be unpacked on disk.
        jniLibs.useLegacyPackaging = true
        resources.excludes += "META-INF/*.kotlin_module"
        resources.merges += setOf("META-INF/LICENSE*", "META-INF/NOTICE*")
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("org.mozilla:rhino:1.9.2")
    // Generates subclasses at runtime: scripts extend abstract Android classes (BroadcastReceiver…).
    implementation("com.linkedin.dexmaker:dexmaker:2.28.6")
    implementation("androidx.webkit:webkit:1.14.0")
    // Speech recognition runtime; fetched by scripts/fetch-libs.ps1.
    implementation(files("libs/sherpa-onnx-2.0.1.aar"))
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Speech model packs come as .tar.bz2.
    implementation("org.apache.commons:commons-compress:1.21")
    // In stock for the agent's modules, which cannot add dependencies at runtime:
    // mail over IMAP/SMTP and HTML parsing.
    implementation("com.sun.mail:android-mail:1.6.7")
    implementation("com.sun.mail:android-activation:1.6.7")
    implementation("org.jsoup:jsoup:1.17.2")
    // Draws an SVG where no page runs: the button over other apps (sense/BubbleLook.kt).
    implementation("com.caverock:androidsvg-aar:1.4")
    // The rights of ADB without a computer, when the owner runs Shizuku.
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    // Language models on the device itself (Google AI Edge, the LiteRT-LM runtime).
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.19.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
