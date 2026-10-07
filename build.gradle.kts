plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.compose.compiler) apply false
}

// Every module targets the same platform. minSdk 29 covers foreground-service
// types and leaves MediaSessionManager.getActiveSessions() (API 21) and screen
// pinning (API 21, used by handoff mode) comfortably in range.
extra["compileSdkVersion"] = 37
extra["minSdkVersion"] = 29
extra["targetSdkVersion"] = 37
