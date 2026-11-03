import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val mockitoAgent = configurations.create("mockitoAgent")

android {
    namespace = "com.aubynsamuel.clipsync"
    compileSdk = 36

    val localProperties = Properties()
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localProperties.load(localPropertiesFile.inputStream())
    }

    defaultConfig {
        applicationId = "com.aubynsamuel.clipsync"
        minSdk = 24
        targetSdk = 36
        versionCode = 91
        versionName = "2.5.13"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        // The security layer logs through android.util.Log; on the JVM that call must be a
        // no-op instead of a "not mocked" exception.
        unitTests.isReturnDefaultValues = true
    }

    signingConfigs {
        // Release signing is supplied by the builder, never by the repository: a signing key
        // that ships with the source lets anyone produce an APK that installs *over* this app
        // (same applicationId, same certificate), which is exactly what an update path is.
        //
        // Resolution order: local.properties (STORE_FILE / STORE_PASSWORD / KEY_ALIAS /
        // KEY_PASSWORD), then the same four values from the environment (CBB_STORE_FILE, ...)
        // so CI can sign without a file on disk. With neither present the release build simply
        // stays unsigned - `assembleRelease` still works, it just cannot be installed as an
        // upgrade.
        fun signingValue(key: String, env: String): String? =
            (localProperties[key] as? String)?.takeIf { it.isNotBlank() }
                ?: System.getenv(env)?.takeIf { it.isNotBlank() }

        val storePath = signingValue("STORE_FILE", "CBB_STORE_FILE")
        create("release") {
            if (storePath != null) {
                val store = rootProject.file(storePath)
                if (store.exists()) {
                    storeFile = store
                    storePassword = signingValue("STORE_PASSWORD", "CBB_STORE_PASSWORD")
                    keyAlias = signingValue("KEY_ALIAS", "CBB_KEY_ALIAS")
                    keyPassword = signingValue("KEY_PASSWORD", "CBB_KEY_PASSWORD")
                }
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (signingConfigs.getByName("release").storeFile != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlin {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
        aidl = true
    }
    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES")
        }
    }
}

dependencies {
    // Testing
    testImplementation(libs.androidx.ui.test.junit4.android)
    testImplementation(libs.robolectric)
    testImplementation(libs.mockito.core)
    mockitoAgent(libs.mockito.core) { isTransitive = false }
    testImplementation(libs.mockito.kotlin)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.work.testing)

    // Shizuku (background clipboard access on strict ROMs)
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:5.1")

    // Work manager
    implementation(libs.androidx.work.runtime.ktx)
    // Document file / SAF helpers
    implementation(libs.androidx.documentfile)
    // Material Icons
    implementation(libs.androidx.material.icons.extended)
    // Navigation Compose
    implementation(libs.androidx.navigation.compose)
    // For AppWidgets support
    implementation(libs.androidx.glance.appwidget)
    // For interop APIs with Material 3
    implementation(libs.androidx.glance.material3)
    // For interop APIs with Material 2
    implementation(libs.androidx.glance.material)
    // Material
    implementation(libs.material)
    // Kotlinx serialization
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.profileinstaller)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
    testImplementation(kotlin("test"))
}

tasks.withType<Test>().configureEach {
    jvmArgs("-javaagent:${mockitoAgent.asPath}")
}
