plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.ksp)
}

// Release signing is supplied by CI environment variables so no keystore is ever committed.
val releaseKeystorePath = System.getenv("KEYSTORE_PATH")
val releaseStorePassword = System.getenv("STORE_PASSWORD")
val releaseKeyAlias = System.getenv("KEY_ALIAS")
val releaseKeyPassword = System.getenv("KEY_PASSWORD")
val hasReleaseSigning = listOf(
  releaseKeystorePath,
  releaseStorePassword,
  releaseKeyAlias,
  releaseKeyPassword,
).all { !it.isNullOrBlank() }

android {
  namespace = "com.noamv.localllm"
  compileSdk = 36

  defaultConfig {
    applicationId = "com.noamv.localllm"
    // API 31 is the floor: on-device inference needs a modern GPU/NPU stack, and the
    // knownSigner permission flag used to gate the inference service requires API 31.
    minSdk = 31
    targetSdk = 36
    versionCode = 8
    versionName = "0.2.1"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    ksp {
      arg("room.schemaLocation", "$projectDir/schemas")
    }

    ndk {
      // The only real target is an arm64 phone. Shipping a single ABI keeps the APK small
      // because the LiteRT-LM native libraries dominate its size.
      abiFilters += "arm64-v8a"
    }
  }

  signingConfigs {
    if (hasReleaseSigning) {
      create("release") {
        storeFile = file(requireNotNull(releaseKeystorePath))
        storePassword = releaseStorePassword
        keyAlias = releaseKeyAlias
        keyPassword = releaseKeyPassword
      }
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      if (hasReleaseSigning) {
        signingConfig = signingConfigs.getByName("release")
      }
    }
  }

  buildFeatures {
    compose = true
    buildConfig = true
    aidl = true
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  packaging {
    resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    // The native inference libraries are already compressed and must stay page-aligned
    // for Android 15+ 16 KB page size devices.
    jniLibs.useLegacyPackaging = false
  }
}

dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.runtime)
  implementation(libs.androidx.room.ktx)
  ksp(libs.androidx.room.compiler)
  implementation(libs.androidx.work.runtime.ktx)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.okhttp)

  // On-device LLM runtime.
  implementation(libs.litertlm.android)

  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.androidx.room.testing)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.tooling)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
}
