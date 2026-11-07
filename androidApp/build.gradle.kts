import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

val versionNameProp = providers.gradleProperty("ferrite.versionName").get()
val versionCodeProp = providers.gradleProperty("ferrite.versionCode").get().toInt()

android {
    namespace = "dev.aetherx86.ferrite.app"
    compileSdk { version = release(37) }

    defaultConfig {
        applicationId = "dev.aetherx86.ferrite"
        minSdk = 26
        targetSdk = 37
        versionCode = versionCodeProp
        versionName = versionNameProp
    }

    // Release builds are signed in CI from secrets. Without them, the release build falls back to the debug key,
    // so anyone can still build and install it.
    signingConfigs {
        val keystore = providers.environmentVariable("FERRITE_KEYSTORE").orNull
        if (keystore != null) {
            create("release") {
                storeFile = file(keystore)
                storePassword = providers.environmentVariable("FERRITE_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("FERRITE_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("FERRITE_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(projects.shared)
    implementation(libs.activity.compose)
}
