plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.overdrive.dilinkprobe"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "com.overdrive.dilinkprobe"
        minSdk = 28
        targetSdk = 28
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    buildFeatures {
        viewBinding = true
        aidl = true
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
}
