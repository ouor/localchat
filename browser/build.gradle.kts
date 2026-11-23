import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Layer 1 (browser-layers 2): tabs, events, sleep and wake, downloads, page scripts. Depends on no other module.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.ouor.localchat.browser"
    compileSdk = 37

    defaultConfig {
        minSdk = 31
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    testFixtures { enable = true }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.webkit)
    api(libs.coroutines.android)
    api(libs.serialization.json)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testFixturesImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.okhttp.mockwebserver)
}
