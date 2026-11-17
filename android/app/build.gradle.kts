import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.fermframe.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.fermframe.app"
        minSdk = 26          // Android 8.0
        targetSdk = 35
        versionCode = 130      // 1.3.0 - goes up with every release (Android won't install an older number over a newer one)
        versionName = "1.3.0"  // same version number as the Windows app it ships with
    }

    // Release signing. The key lives OUTSIDE the project (never on GitHub); keystore.properties says where.
    // Every update must be signed with the same key, or phones won't install it over the old app.
    val keyProps = Properties().apply {
        val f = rootProject.file("keystore.properties"); if (f.exists()) f.inputStream().use { load(it) }
    }
    signingConfigs {
        if (keyProps.getProperty("storeFile") != null) create("release") {
            storeFile = file(keyProps.getProperty("storeFile"))
            storePassword = keyProps.getProperty("storePassword")
            keyAlias = keyProps.getProperty("keyAlias")
            keyPassword = keyProps.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

// The firmware the app installs: the same files the Windows app embeds (installer/files/*.bin)
val copyFirmware by tasks.registering(Copy::class) {
    from("../../installer/files") { include("fermframe.bin", "fermframe-lite.bin") }
    into(layout.buildDirectory.dir("generated/firmware"))
}
android.sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/firmware"))
tasks.named("preBuild") { dependsOn(copyFirmware) }

dependencies {
    val bom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(bom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
}
