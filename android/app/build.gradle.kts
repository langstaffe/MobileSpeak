import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val mobileVersion = Properties().apply {
    rootProject.file("../AppVersion.xcconfig").inputStream().use { load(it) }
}.getProperty("MOBILE_SPEAK_VERSION").trim()
check(Regex("[0-9]+\\.[0-9]+\\.[0-9]+").matches(mobileVersion))

android {
    namespace = "dev.mobilespeak.mobilespeak"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "dev.mobilespeak.mobilespeak"
        minSdk = 24
        targetSdk = 36
        versionCode = 4
        versionName = mobileVersion
        ndk { abiFilters += "arm64-v8a" }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        getByName("debug") {
            ndk { abiFilters += "x86_64" }
        }
    }

    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        jniLibs { useLegacyPackaging = true }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.12.01")
    implementation(composeBom)
    implementation("androidx.appcompat:appcompat:1.8.0")
    androidTestImplementation(composeBom)
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
}

listOf("arm64-v8a", "x86_64").forEach { abi ->
    val rustCore = tasks.register<Exec>("rustCore_${abi.replace('-', '_')}") {
        workingDir(rootProject.projectDir.parentFile)
        commandLine("bash", "tool/build_android_core.sh", abi)
        inputs.files(fileTree("../../native/src"), file("../../native/Cargo.toml"), file("../../native/build.rs"), fileTree("../../native/models"), file("../../tool/prepare_audio_runtime.py"), file("../../tool/build_sherpa_fft.py"), file("../../tool/sherpa-onnx-fft.patch"), file("../../native/Cargo.lock"),
            file("../../native/.cargo/config.toml"), file("../../tool/build_android_core.sh"), file("../../tool/android.cmake"))
        outputs.dir("src/main/jniLibs/$abi")
    }
    val prepareTask = if (abi == "arm64-v8a") "preBuild" else "preDebugBuild"
    tasks.matching { it.name == prepareTask }.configureEach { dependsOn(rustCore) }
    // JNI merging reads the shared folder before ABI filtering.
    tasks.matching { it.name.endsWith("JniLibFolders") }.configureEach { mustRunAfter(rustCore) }
}
