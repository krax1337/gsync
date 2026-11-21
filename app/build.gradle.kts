import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.krax1337.gsync"
    // Compose 1.13 needs the 37.1 APIs to compile; runtime behaviour stays on targetSdk 36.
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "io.github.krax1337.gsync"
        minSdk = 33
        targetSdk = 36
        versionCode = 4
        versionName = "0.2.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Signed with the local debug key, so a build can be installed and tried. Published
            // releases are built unsigned (-Punsigned) and signed by the release workflow in a
            // separate step: the release key never passes through Gradle or its plugins.
            signingConfig = if (providers.gradleProperty("unsigned").isPresent) null else signingConfigs.getByName("debug")
        }
    }

    // No encrypted dependency list for Google Play inside the APK: nothing in it should be opaque.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.datastore.preferences)
    implementation(libs.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
