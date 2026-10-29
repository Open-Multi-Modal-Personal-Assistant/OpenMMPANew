plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    // Apply KSP before ObjectBox so ObjectBox 6.0.0-beta3 uses its KSP processor (no legacy kapt needed)
    alias(libs.plugins.ksp)
    alias(libs.plugins.objectbox)
}

if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

ksp {
    arg("appfunctions:aggregateAppFunctions", "true")
}

android {
    namespace = "ai.wristmind.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "ai.wristmind.app"
        minSdk = 35
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-hackathon"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/INDEX.LIST",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/io.netty.versions.properties",
                "META-INF/*.kotlin_module"
            )
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Kotlinx
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)

    // JetBrains Koog 1.3.0 Orchestrator + SkillsDiscovery
    // Note: koog.agents transitively brings in io.github.oshai:kotlin-logging-android-debug:8.0.01;
    // do not add kotlin-logging-jvm to avoid duplicate class collisions during APK packaging.
    implementation(libs.koog.agents)
    implementation(libs.koog.skills)
    implementation(libs.slf4j.android)

    // Google AI Edge: LiteRT-LM 0.18.0 & LiteRT 2.3.0 CompiledModel
    implementation(libs.litertlm.android)
    implementation(libs.litert)
    implementation(libs.litert.gpu)

    // ObjectBox 6.0.0-beta3 (Admin browser in debug builds, standard runtime in release)
    implementation(libs.objectbox.kotlin)
    debugImplementation(libs.objectbox.android.objectbrowser)
    releaseImplementation(libs.objectbox.android)

    // Android 15 AppSearch (PlatformStorage BM25F) & Jetpack AppFunctions
    implementation(libs.androidx.appsearch)
    implementation(libs.androidx.appsearch.platform.storage)
    implementation(libs.androidx.appsearch.local.storage)
    ksp(libs.androidx.appsearch.compiler)

    implementation(libs.androidx.appfunctions)
    ksp(libs.androidx.appfunctions.compiler)

    // Firebase AI Logic (BoM 35.1.0)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.ai)
    implementation(libs.firebase.config)
    implementation(libs.firebase.appcheck.playintegrity)
    debugImplementation(libs.firebase.appcheck.debug)

    // CameraX, Play Services ML Kit OCR & Location
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.camera.mlkit.vision)
    implementation(libs.play.services.mlkit.text.recognition)
    implementation(libs.play.services.location)

    // OkHttp for Model Gallery Downloads
    implementation(libs.okhttp)

    // Unit Tests
    testImplementation(libs.junit)
    testImplementation(libs.google.truth)
    testImplementation(libs.kotlinx.coroutines.test)
}

configurations.configureEach {
    // ObjectBox debugImplementation swaps objectbox-android with objectbox-android-objectbrowser
    if (name.contains("debug", ignoreCase = true)) {
        exclude(group = "io.objectbox", module = "objectbox-android")
    }
}
