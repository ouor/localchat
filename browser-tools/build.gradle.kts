import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Layer 2 (browser-layers 3): the model-callable tools over layer 1.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.ouor.localchat.tools"
    compileSdk = 37

    defaultConfig {
        minSdk = 31
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
    }
}

dependencies {
    api(project(":browser"))

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
