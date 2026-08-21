import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
}

/**
 * The Google client id, from local.properties — which git already ignores,
 * because it is where the SDK path of whichever machine ran the build lives.
 *
 * WHY IT IS NOT SIMPLY WRITTEN INTO THIS FILE
 *
 * Not because it is a secret. An Android OAuth client has no secret at all: the
 * id is public by design, and what protects the sign-in is the package name and
 * signing certificate Google checks, plus PKCE. It is kept out of the
 * repository so that it stays out of git history and out of the way of secret
 * scanners, and so that anyone who clones this builds against their own id
 * rather than one wired to an account they cannot use.
 *
 * WHY MISSING IS NOT AN ERROR
 *
 * This repository is public. A build that fails for everyone who does not
 * already have the file is a repository nobody can build. Absent reads as null,
 * and the sync screen simply does not offer Drive — the same thing the desktop
 * does when src-tauri/.env is not there.
 *
 * WHY IT IS READ HERE AND NOT BESIDE THE FIELD THAT USES IT
 *
 * Inside `android { defaultConfig { } }` the name `java` already belongs to
 * Gradle's own extension, so `java.util.Properties` stops naming a package and
 * the script will not compile. The error it gives is "Unresolved reference
 * 'util'", which says nothing about any of that. At the top of the file it is
 * an ordinary import.
 */
val googleClientId: String? = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}.getProperty("reupGoogleClientId")

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

        // Read at the top of this file. See googleClientId for why it cannot be
        // read here.
        buildConfigField(
            "String",
            "GOOGLE_CLIENT_ID",
            if (googleClientId.isNullOrBlank()) "null" else "\"$googleClientId\"",
        )
    }

    buildFeatures {
        // Off by default since AGP 8. On for exactly one field.
        buildConfig = true
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
    // AndroidDb and the sync screen use Dispatchers.IO and launch work from a
    // click listener. :shared needs none of this - every function in it is
    // plain `suspend`, which is a language feature and not a library.
    implementation(libs.kotlinx.coroutines.android)
    // kotlinx-datetime arrives through :shared, which declares it as `api`
    // because Instant and TimeZone are part of nextReset()'s signature.
}