rootProject.name = "reup-mobile"

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        // Tesseract4Android is published on JitPack and nowhere else.
        //
        // Scoped to that one group rather than opened to everything, because
        // JitPack builds whatever a GitHub tag contains: leaving it wide open
        // means any future typo in a coordinate can resolve to somebody's fork
        // instead of failing.
        maven("https://jitpack.io") {
            content { includeGroup("cz.adaptech.tesseract4android") }
        }
    }
}

include(":shared")
include(":app")