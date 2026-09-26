plugins {
    alias(libs.plugins.android.application) apply false
    // AGP 9.0 起内置 Kotlin 支持，不再需要 org.jetbrains.kotlin.android
    alias(libs.plugins.compose.compiler) apply false
}
