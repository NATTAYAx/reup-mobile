plugins {
    alias(libs.plugins.androidApplication)
}

// Still no Compose, no AndroidX, no database — the only dependency is :shared.
// AGP 9 has Kotlin support built in, so there is no Kotlin plugin to apply here.

android {
    namespace = "app.reup"

    compileSdk {
        version = release(37)
    }

    defaultConfig {
        // applicationId is the app's permanent identity on Android. It can be
        // changed freely right now, while the only install in the world is on
        // one phone. After a store release it cannot be changed at all: a new
        // id is a new app, with a new listing, and every existing user has to
        // find it and install it again. This is the last cheap moment.
        applicationId = "app.reup"

        minSdk = 26      // java.time arrives here; below it kotlinx-datetime needs desugaring
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":shared"))
    // kotlinx-datetime arrives through :shared, which declares it as `api`
    // because Instant and TimeZone are part of nextReset()'s signature.
}