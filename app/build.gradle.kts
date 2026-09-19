import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    jacoco
}

// Explicit opt-in for CI: build without loading local signing credentials.
val unsignedRelease = providers.gradleProperty("splitfreeUnsignedRelease")
    .map { value ->
        require(value == "true" || value == "false") { "splitfreeUnsignedRelease must be true or false" }
        value.toBoolean()
    }.getOrElse(false)

android {
    namespace = "com.splitfree"
    compileSdk = 37
    buildToolsVersion = "36.1.0"

    defaultConfig {
        applicationId = "com.splitfree"
        minSdk = 26
        targetSdk = 37
        // Increase the code for each distributed update; preserve the production signing identity.
        versionCode = 5
        versionName = "1.0.1"
    }

    if (!unsignedRelease) {
        signingConfigs {
            create("release") {
                val localProps = rootProject.file("local.properties")
                val props = Properties()
                if (localProps.exists()) localProps.inputStream().use { props.load(it) }
                storeFile = file("../splitfree-release.jks")
                storePassword = props.getProperty("RELEASE_STORE_PASSWORD", "")
                keyAlias = props.getProperty("RELEASE_KEY_ALIAS", "")
                keyPassword = props.getProperty("RELEASE_KEY_PASSWORD", "")
            }
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        warningsAsErrors = false
        checkDependencies = true
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            enableUnitTestCoverage = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (!unsignedRelease) signingConfig = signingConfigs.getByName("release")
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

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // Robolectric fixtures and the 32 MiB import-cap test share one worker; the 512 MB default OOMs.
            it.maxHeapSize = "2g"
            it.systemProperty("REAL_RELAY_TEST", System.getProperty("REAL_RELAY_TEST") ?: "false")
            if (System.getProperty("REAL_RELAY_TEST") != "true") {
                it.exclude("**/*IntegrationTest*")
            }
        }
    }

    packaging {
        resources {
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // Cryptographic primitives and WebSockets for the app-defined Nostr implementation.
    implementation(libs.secp256k1.android)
    implementation(libs.bouncycastle)
    implementation(libs.okhttp)

    // Room (SQLite)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)

    // WorkManager
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    // Nearby Connections chooses Bluetooth, BLE or Wi-Fi transports.
    implementation(libs.play.services.nearby)

    // QR Code
    implementation(libs.zxing.core)
    implementation(libs.play.services.code.scanner)

    // Hilt DI
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    // Serialization
    implementation(libs.kotlinx.serialization.json)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Compression
    implementation(libs.lz4)

    // Core
    implementation(libs.androidx.core.ktx)

    debugImplementation(libs.androidx.compose.ui.tooling)

    // Unit tests (JVM)
    testImplementation(libs.junit)
    testImplementation("com.squareup.okhttp3:mockwebserver3:${libs.versions.okhttp.get()}")
    testImplementation("com.squareup.okhttp3:okhttp-tls:${libs.versions.okhttp.get()}")
    testImplementation(libs.secp256k1.jvm)
    testImplementation(libs.bouncycastle)
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.json)
    testImplementation(libs.mockk)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
