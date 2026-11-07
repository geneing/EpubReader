plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.geneing.epubreader"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.geneing.epubreader"
        minSdk = 36
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Only arm64-v8a has a Google Tensor NPU dispatch shim; keep the native
        // build (pockettts_kv_slice) and packaging to that ABI.
        ndk { abiFilters += setOf("arm64-v8a") }
    }

    buildFeatures {
        compose = true
    }

    packaging {
        jniLibs {
            // The Tensor dispatch shim is dlopen'ed by absolute path out of the
            // installed package's lib/arm64/ directory, so it has to be extracted
            // at install time rather than left compressed in the APK.
            useLegacyPackaging = true
            pickFirsts += setOf(
                "**/libc++_shared.so",
                "**/libtensorflowlite_jni.so",
                "**/libtensorflowlite_gpu_jni.so",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.readium.streamer)
    implementation(libs.readium.navigator)
    implementation(libs.readium.pdfium)
    implementation(libs.readium.tts)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.exoplayer)
    // pockettts-core exposes the pinned LiteRT 2.3.2 runtime, GPU accelerator,
    // and Google Tensor NPU runtime (including the dispatch shim) transitively.
    implementation(project(":pockettts-service"))
    ksp(libs.androidx.room.compiler)
    coreLibraryDesugaring(libs.android.desugar)

    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
