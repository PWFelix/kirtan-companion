// Top-level build file. Plugin versions live in gradle/libs.versions.toml;
// `apply false` declares them without adding them to the root project.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
