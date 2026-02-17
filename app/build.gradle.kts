plugins {
    id("com.android.application")
}

android {
    namespace = "be.zvz.sony.launchersearchenhancer"
    compileSdk = 36

    defaultConfig {
        applicationId = namespace
        minSdk = 31
        versionCode = 1
        versionName = "1.0.0"
    }

    lint {
        targetSdk = 36
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    lint {
        checkReleaseBuilds = false
    }

    dependenciesInfo {
        includeInApk = false
    }
}

dependencies {
    compileOnly("androidx.annotation:annotation:1.9.1")
    compileOnly("io.github.libxposed:api")
    compileOnly(project(":libxposed-compat"))
}
