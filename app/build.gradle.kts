plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

val liteRtNativeRuntime by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
    isTransitive = false
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
    }

    buildFeatures {
        compose = true
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
    implementation(project(":pockettts-service"))
    add(liteRtNativeRuntime.name, libs.litert.runtime)
    ksp(libs.androidx.room.compiler)
    coreLibraryDesugaring(libs.android.desugar)

    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
}

// LiteRT 2.2.0 publishes `litert` and `litert-api` AARs with the same manifest
// namespace, which AGP 9 rejects. The Pocket library uses the API AAR; extract
// only the pinned runtime .so files from the implementation AAR to avoid merging
// its duplicate manifest while preserving the official runtime/JNI binaries.
val unpackLiteRtNativeRuntime = tasks.register<Copy>("unpackLiteRtNativeRuntime") {
    from({ zipTree(liteRtNativeRuntime.singleFile) }) {
        include("jni/**/*.so")
        eachFile {
            relativePath = org.gradle.api.file.RelativePath(
                true,
                *relativePath.segments.drop(1).toTypedArray(),
            )
        }
        includeEmptyDirs = false
    }
    into(layout.buildDirectory.dir("generated/litert-jni"))
}

android.sourceSets.getByName("main").jniLibs.srcDir(
    layout.buildDirectory.dir("generated/litert-jni").get().asFile,
)
tasks.named("preBuild").configure { dependsOn(unpackLiteRtNativeRuntime) }
