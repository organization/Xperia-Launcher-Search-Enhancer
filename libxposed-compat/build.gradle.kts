plugins {
    id("com.android.library")
}

android {
    namespace = "io.github.libxposed"
    defaultConfig {
        minSdk = 24
        compileSdk = 36
    }

    lint {
        targetSdk = 36
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}
