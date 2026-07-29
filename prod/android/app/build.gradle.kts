plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.yashasvm.holen"
    compileSdk = 37
    compileSdkMinor = 1

    defaultConfig {
        applicationId = "com.yashasvm.holen"
        minSdk = 29
        targetSdk = 36
        versionCode = providers.gradleProperty("holenVersionCode").orElse("9").get().toInt()
        versionName = providers.gradleProperty("holenVersionName").orElse("3.3.1").get()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    flavorDimensions += "abi"
    productFlavors {
        create("arm64") {
            dimension = "abi"
            ndk.abiFilters += "arm64-v8a"
        }
        create("armv7") {
            dimension = "abi"
            ndk.abiFilters += "armeabi-v7a"
        }
        create("universal") {
            dimension = "abi"
            ndk.abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        }
        create("emulator") {
            dimension = "abi"
            ndk.abiFilters += listOf("x86", "x86_64")
        }
    }

    signingConfigs {
        val keystorePath = System.getenv("HOLEN_KEYSTORE_PATH")
        val requireReleaseSigning =
            providers.gradleProperty("holenRequireReleaseSigning").orElse("false").get().toBoolean()
        val signingValues = listOf(
            keystorePath,
            System.getenv("HOLEN_KEYSTORE_PASSWORD"),
            System.getenv("HOLEN_KEY_ALIAS"),
            System.getenv("HOLEN_KEY_PASSWORD"),
        )
        if (requireReleaseSigning && signingValues.any { it.isNullOrBlank() }) {
            error("Release signing is required, but the HOLEN signing configuration is incomplete.")
        }
        if (!keystorePath.isNullOrBlank()) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("HOLEN_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("HOLEN_KEY_ALIAS")
                keyPassword = System.getenv("HOLEN_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {}
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
                ?: if (providers.gradleProperty("holenRequireReleaseSigning")
                        .orElse("false").get().toBoolean()
                ) {
                    error("Release signing is required; refusing to use a debug certificate.")
                } else {
                    signingConfigs.getByName("debug")
                }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs.useLegacyPackaging = true
        resources.merges += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE*",
            "META-INF/NOTICE*",
        )
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { it.useJUnit() }
        }
    }
}

androidComponents {
    beforeVariants { variant ->
        val abi = variant.productFlavors.find { it.first == "abi" }?.second
        variant.enable = when (variant.buildType) {
            "debug" -> abi == "emulator" || abi == "arm64" || abi == "universal"
            "release" -> abi != "emulator"
            else -> true
        }
    }

}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.concurrent.futures)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.test.manifest)

    implementation(libs.coil.compose)
    implementation(libs.coil.network)
    implementation(libs.youtubedl.library)
    implementation(libs.youtubedl.ffmpeg)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.test.junit4)
}
