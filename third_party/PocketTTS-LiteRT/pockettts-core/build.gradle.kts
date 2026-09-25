plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

extensions.configure<com.android.build.gradle.LibraryExtension> {
    namespace = "dev.pockettts"
    compileSdk = 35

    defaultConfig {
        // 31+ because the Tensor G5 NPU dispatch runtime requires it.
        minSdk = 31
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
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
    // Exposed transitively: every consumer needs the same 2.2.0 runtime the
    // Google Tensor dispatch shim and AOT compiler are pinned to.
    api(libs.litert.api)

    testImplementation("junit:junit:4.13.2")
}
