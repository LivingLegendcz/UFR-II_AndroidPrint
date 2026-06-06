plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "cz.ufrii.print"
    compileSdk = 35

    defaultConfig {
        applicationId = "cz.ufrii.print"
        minSdk = 29
        targetSdk = 35
        versionCode = 11
        versionName = "1.0"
    }

    buildTypes {
        debug {
            isDebuggable = true
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
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
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.apache.commons:commons-compress:1.26.2")
}
