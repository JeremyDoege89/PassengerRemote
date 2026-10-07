plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.passengerremote.host"
    compileSdk = rootProject.extra["compileSdkVersion"] as Int

    defaultConfig {
        minSdk = rootProject.extra["minSdkVersion"] as Int
    }

    buildFeatures {
        aidl = true
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
    api(project(":transport"))
    // Optional privilege escalation. compileOnly would not work: the Shizuku
    // provider and user-service binding are used at runtime.
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation(libs.core.ktx)
    implementation(libs.coroutines.android)
    api(libs.lifecycle.service)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
