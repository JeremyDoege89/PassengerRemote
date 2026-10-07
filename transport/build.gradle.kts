plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.passengerremote.transport"
    compileSdk = rootProject.extra["compileSdkVersion"] as Int

    defaultConfig {
        minSdk = rootProject.extra["minSdkVersion"] as Int
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(project(":protocol"))
    implementation(libs.coroutines.android)
    // Nearby Connections: the Phase 2 transport for a passenger's own device.
    implementation("com.google.android.gms:play-services-nearby:19.3.0")
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
