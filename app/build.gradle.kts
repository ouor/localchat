import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.room)
    alias(libs.plugins.aboutlibraries)
}

// Release signing secrets stay out of git (signing/SIGNING.md); without them the release APK is unsigned.
val ksFile = rootProject.file("signing/keystore.properties")
val ksProps = Properties().apply { ksFile.takeIf { it.exists() }?.inputStream()?.use { load(it) } }

android {
    namespace = "com.ouor.localchat"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.ouor.localchat"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("signing/release.p12")
            storeType = "PKCS12"
            storePassword = ksProps.getProperty("storePassword")
            keyAlias = "release"
            keyPassword = ksProps.getProperty("storePassword")
        }
    }

    buildTypes {
        release {
            if (ksFile.exists()) signingConfig = signingConfigs.getByName("release")
            // R8 shrink/optimize + resource shrinking (needs android.r8.gradual.support); keep rules in src/main/keepRules.
            optimization {
                enable = true
            }
            ndk { abiFilters += "arm64-v8a" } // distributed builds ship arm64 only
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures {
        compose = true
    }
    // FakeOpenAiServer is shared by JVM tests and instrumented tests (architecture 11).
    sourceSets {
        getByName("test").kotlin.directories += "src/testShared/java"
        getByName("androidTest").kotlin.directories += "src/testShared/java"
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.material3.adaptive)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.service)
    implementation(libs.lifecycle.viewmodel.navigation3)
    implementation(libs.navigation3.runtime)
    implementation(libs.navigation3.ui)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.lifecycle.viewmodel.compose)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.datastore)
    implementation(libs.webkit)
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.markdown.m3)
    implementation(libs.markdown.code)
    implementation(libs.androidsvg)
    implementation(libs.pdfbox.android)
    implementation(libs.commons.compress)
    implementation(libs.latex.renderer)
    implementation(libs.latex.fonts.katex)
    implementation(libs.aboutlibraries.compose)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.room.testing)
}

aboutLibraries { collect { configPath = file("config") } } // non-Gradle bundles (Mermaid asset)
