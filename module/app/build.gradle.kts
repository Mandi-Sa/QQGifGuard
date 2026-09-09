plugins {
    id("com.android.application")
}

android {
    namespace = "com.pjz.qqgifguard"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.pjz.qqgifguard"
        minSdk = 26
        targetSdk = 34
        versionCode = 9
        versionName = "0.2.3"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    compileOnly("de.robv.android.xposed:api:82")
}
