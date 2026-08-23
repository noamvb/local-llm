plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.serialization)
}

android {
  namespace = "com.noamv.localllm.client"
  compileSdk = 36

  defaultConfig {
    // Match the oldest intended consumer. Runtime resolution fails closed below API 31,
    // because that is the LocalLLM host's own minimum supported Android version.
    minSdk = 24
  }

  sourceSets.named("main") {
    // Keep the copy-ready src/main tree free of a manifest that would overwrite a
    // consuming application's manifest.
    manifest.srcFile("src/build/AndroidManifest.xml")
  }

  buildFeatures {
    aidl = true
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
}

dependencies {
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.kotlinx.serialization.json)

  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
}
