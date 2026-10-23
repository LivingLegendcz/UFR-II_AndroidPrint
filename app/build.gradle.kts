import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ---------------------------------------------------------------------------
// Release signing config (S1)
// Keystore credentials are read from local.properties (gitignored) or env vars.
// The keystore file itself MUST live OUTSIDE the repo.
// Required keys (local.properties) / env vars:
//   RELEASE_STORE_FILE, RELEASE_STORE_PASSWORD, RELEASE_KEY_ALIAS, RELEASE_KEY_PASSWORD
// Generate a keystore once with, e.g.:
//   keytool -genkeypair -v -keystore ~/canonprint-release.jks -alias canonprint \
//           -keyalg RSA -keysize 4096 -validity 10000
// ---------------------------------------------------------------------------
val keystoreProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) FileInputStream(f).use { load(it) }
}
fun releaseProp(name: String): String? =
    keystoreProps.getProperty(name) ?: System.getenv(name)
val hasReleaseKeystore = releaseProp("RELEASE_STORE_FILE") != null

android {
    namespace = "cz.ufrii.print"
    compileSdk = 35

    defaultConfig {
        applicationId = "cz.ufrii.print"
        minSdk = 29
        targetSdk = 35
        versionCode = 17
        versionName = "1.3.0"
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = file(releaseProp("RELEASE_STORE_FILE")!!)
                storePassword = releaseProp("RELEASE_STORE_PASSWORD")
                keyAlias = releaseProp("RELEASE_KEY_ALIAS")
                keyPassword = releaseProp("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Only wire up signing when a keystore is configured; otherwise the
            // release build stays unsigned (developer must configure credentials).
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    packaging {
        jniLibs {
            // CRITICAL: extract libproot.so to nativeLibraryDir at install time.
            // Without this, .so stays compressed in APK and cannot be exec'd.
            useLegacyPackaging = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // D1: Jetpack Compose UI (docs/design). Compiler 1.5.15 ↔ Kotlin 1.9.25.
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.15"
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("org.apache.commons:commons-compress:1.26.2")

    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.navigation:navigation-compose:2.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    // Printer setup sharing: QR generation (zxing) and scanning via Google's code scanner
    // (no camera permission; runs in Play services).
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.ui:ui-tooling-preview")

    testImplementation("junit:junit:4.13.2")
}
