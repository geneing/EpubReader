plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

extensions.configure<com.android.build.gradle.LibraryExtension> {
    namespace = "dev.pockettts.service"
    compileSdk = 35

    defaultConfig {
        minSdk = 31
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension> {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(project(":pockettts-core"))
}
