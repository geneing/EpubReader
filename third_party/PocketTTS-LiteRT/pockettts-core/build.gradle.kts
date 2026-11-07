plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

extensions.configure<com.android.build.gradle.LibraryExtension> {
    namespace = "dev.pockettts"
    compileSdk = 35

    buildFeatures {
        // PocketTtsConfig gates its timing traces on BuildConfig.DEBUG.
        buildConfig = true
    }

    defaultConfig {
        // 31+ because the Google Tensor NPU dispatch runtime requires it.
        minSdk = 31
        consumerProguardFiles("consumer-rules.pro")
        externalNativeBuild {
            cmake { cppFlags += "-std=c++17" }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    externalNativeBuild {
        // The Google Tensor NPU cache bridge (pockettts_kv_slice) is compiled
        // here; only arm64-v8a has a Tensor dispatch shim, so the app restricts
        // the ABI set.
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    packaging {
        jniLibs {
            pickFirsts += setOf(
                "**/libc++_shared.so",
                "**/libtensorflowlite_jni.so",
                "**/libtensorflowlite_gpu_jni.so",
            )
        }
    }
}

extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension> {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}


dependencies {
    // Exposed transitively: every consumer needs the same 2.3.x runtime the
    // Google Tensor dispatch shim is pinned to. 2.3.0 split the GPU and NPU
    // runtimes out of the core AAR; 2.3.2 is the first dispatch shim that
    // initializes on Pixel 11 / Tensor G6. `litert` pulls in `litert-api`.
    api(libs.litert.runtime)
    api(libs.litert.gpu)
    api(libs.litert.npu.runtime.google.tensor)

    testImplementation("junit:junit:4.13.2")
}
