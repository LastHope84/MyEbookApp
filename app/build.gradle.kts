plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}
// Optional offline-translation build. The normal build uses a fail-closed stub;
// CI builds the Foxlet 0.5.0 AAR from pinned open-source source and passes it here.
val foxletAar = providers.gradleProperty("foxletAar").orNull

android {
    namespace = "com.example.myebookapp"
    compileSdk = if (foxletAar != null) 36 else 35
    ndkVersion = "26.1.10909125" // Uses the version pre-installed on GitHub

    val buildNum = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
    defaultConfig {
        applicationId = "com.example.myebookapp"
        minSdk = if (foxletAar != null) 28 else 24; targetSdk = 35
        versionCode = buildNum; versionName = "1.1.1"
        ndk { abiFilters += if (foxletAar != null) listOf("arm64-v8a") else listOf("arm64-v8a", "armeabi-v7a") }
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }
    signingConfigs {
        create("persistentDebug") {
            val ks = file("persistent.keystore")
            if (ks.exists()) { storeFile = ks; storePassword = "android123"; keyAlias = "myebook"; keyPassword = "android123" }
        }
    }
    buildTypes { getByName("debug") { if (file("persistent.keystore").exists()) signingConfig = signingConfigs.getByName("persistentDebug") } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17; isCoreLibraryDesugaringEnabled = true }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    if (foxletAar != null) {
        require(file(foxletAar).isFile) { "-PfoxletAar must point to a source-built Foxlet 0.5.0 AAR" }
        sourceSets.getByName("main").java.srcDir("src/foxlet/java")
    } else {
        sourceSets.getByName("main").java.srcDir("src/nofoxlet/java")
    }
}
dependencies {
    if (foxletAar != null) {
        implementation(files(foxletAar))
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    }
    testImplementation("junit:junit:4.13.2")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    implementation(platform("androidx.compose:compose-bom:2025.01.00"))
    implementation("androidx.compose.ui:ui"); implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.10.0"); implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.fragment:fragment-ktx:1.8.5"); implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    val r = "3.1.2"
    implementation("org.readium.kotlin-toolkit:readium-shared:$r"); implementation("org.readium.kotlin-toolkit:readium-streamer:$r"); implementation("org.readium.kotlin-toolkit:readium-navigator:$r")
    val rm = "2.7.2"
    implementation("androidx.room:room-runtime:$rm"); implementation("androidx.room:room-ktx:$rm"); implementation("androidx.room:room-paging:$rm"); ksp("androidx.room:room-compiler:$rm")
    implementation("androidx.paging:paging-compose:3.3.5"); implementation("io.coil-kt:coil-compose:2.7.0")
}
