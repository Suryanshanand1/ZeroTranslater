import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

// Release signing material lives outside version control. See keystore.properties.example.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.zerotranslater"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.zerotranslater"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        // NOTE: no <uses-permission android:name="android.permission.INTERNET" /> is
        // declared here on purpose. The app itself opens zero sockets. INTERNET is merged
        // in transitively by the ML Kit modules, which is the only thing that needs it (to
        // fetch language packs from Play services). See README "Permissions".
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        // Populated only when keystore.properties exists, which is git-ignored.
        // Without it the release build stays UNSIGNED rather than failing, so a
        // fresh clone can still build and run the tests.
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = if (keystorePropertiesFile.exists()) {
                signingConfigs.getByName("release")
            } else {
                null // unsigned; see README -> "Signing"
            }
        }
    }

    // No ABI splits. They are mutually exclusive with App Bundles in AGP
    // (https://issuetracker.google.com/402800800), and the bundle is the artifact
    // that matters: Play performs per-ABI selection at install time, so bundle
    // users download roughly one ABI's worth of native code rather than all four.
    //
    // The consequence is that `assembleRelease` emits one fat universal APK, about
    // 64 MB, which is what anyone sideloading gets. See README -> "APK size".

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            // Required so Robolectric can read the merged AndroidManifest.xml, which is
            // how ProcessTextManifestTest verifies the PROCESS_TEXT intent filter.
            isIncludeAndroidResources = true
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE*",
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)

    // Bridges com.google.android.gms.tasks.Task (ML Kit) to suspend functions.
    // Without this, every ML Kit call would need a blocking Tasks.await() on the
    // main thread, which is exactly what this app must never do.
    implementation(libs.kotlinx.coroutines.play.services)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.mlkit.translate)
    implementation(libs.mlkit.language.id)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
}
