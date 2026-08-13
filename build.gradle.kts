// Root build file. Deliberately almost empty: plugins are applied per module
// via the version catalog in gradle/libs.versions.toml, so nothing is applied
// to every module by accident.
//
// Everything is declared with `apply false`. That registers the plugin and
// pins its version for the whole build without applying it anywhere, which is
// what stops two modules resolving different versions of the same plugin.
plugins {
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidMultiplatformLibrary) apply false
}