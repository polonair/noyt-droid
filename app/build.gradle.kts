plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.chaquo.python")
}

android {
    namespace = "com.example.noytdroid"
    compileSdk = 35

    fun envOrProp(name: String): String? =
        System.getenv(name) ?: (findProperty(name) as String?)

    val githubRunNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
    val isReleaseBuild = gradle.startParameter.taskNames.any { it.contains("release", ignoreCase = true) }

    val keystorePathValue = envOrProp("KEYSTORE_PATH")
    val keystorePasswordValue = envOrProp("KEYSTORE_PASSWORD")
    val keyAliasValue = envOrProp("KEY_ALIAS")
    val keyPasswordValue = envOrProp("KEY_PASSWORD")

    if (isReleaseBuild && listOf(keystorePathValue, keystorePasswordValue, keyAliasValue, keyPasswordValue).any { it.isNullOrBlank() }) {
        throw GradleException(
            "Release signing variables are missing. Expected KEYSTORE_PATH, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD."
        )
    }

    defaultConfig {
        applicationId = "com.example.noytdroid"
        minSdk = 26
        targetSdk = 35
        versionCode = githubRunNumber ?: 1
        versionName = "1.0"

        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64")
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            if (!keystorePathValue.isNullOrBlank()) {
                storeFile = file(keystorePathValue)
                storePassword = keystorePasswordValue
                keyAlias = keyAliasValue
                keyPassword = keyPasswordValue
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

}

chaquopy {
    defaultConfig {
        version = "3.11"
        pip {
            install("yt-dlp")
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")

    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.github.jamaismagic.ffmpeg:ffmpeg-kit-main-16kb:6.1.7")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
