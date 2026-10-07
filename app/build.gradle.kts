plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.roborazzi)
}

// Release identity and the update channel's trust anchor come from gradle properties so a release
// can be cut without editing tracked source. Set them in ~/.gradle/gradle.properties or pass -P.
val appVersionCode = (providers.gradleProperty("BARPRO_VERSION_CODE").orNull ?: "1").toInt()
val appVersionName = providers.gradleProperty("BARPRO_VERSION_NAME").orNull ?: "1.0"

// Base64 X.509 public key printed by tools/barpro-sign.sh keygen. Empty means the remote-config
// and self-update channels stay disabled (fail closed) rather than accepting unsigned documents.
val configPublicKey = providers.gradleProperty("BARPRO_CONFIG_PUBLIC_KEY").orNull ?: ""

// Optional pre-seeded onboarding values, so a driver only confirms their phone number.
val defaultEndpoint = providers.gradleProperty("BARPRO_DEFAULT_ENDPOINT").orNull ?: ""
val defaultConfigUrl = providers.gradleProperty("BARPRO_CONFIG_URL").orNull ?: ""
val defaultUpdateUrl = providers.gradleProperty("BARPRO_UPDATE_URL").orNull ?: ""

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "ir.barpro.fleet.smsforwarder"
    minSdk = 24
    targetSdk = 36
    versionCode = appVersionCode
    versionName = appVersionName

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    buildConfigField("String", "CONFIG_PUBLIC_KEY", "\"$configPublicKey\"")
    buildConfigField("String", "DEFAULT_ENDPOINT_URL", "\"$defaultEndpoint\"")
    buildConfigField("String", "DEFAULT_CONFIG_URL", "\"$defaultConfigUrl\"")
    buildConfigField("String", "DEFAULT_UPDATE_URL", "\"$defaultUpdateUrl\"")
  }

  val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"

  signingConfigs {
    create("release") {
      // The signing key is the whole update channel: Android refuses an update signed by a
      // different key, so rotating it would force every driver to uninstall and lose their data.
      // Generate once, back up offline, never replace.
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = System.getenv("KEY_ALIAS") ?: "upload"
      keyPassword = System.getenv("KEY_PASSWORD")
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      val releaseKeystore = file(keystorePath)
      if (releaseKeystore.exists()) {
        signingConfig = signingConfigs.getByName("release")
      } else {
        signingConfig = signingConfigs.getByName("debug")
      }
    }
    // Use Android's generated per-developer debug keystore.
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
}

dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.text.google.fonts)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  implementation(libs.androidx.security.crypto)
  implementation(libs.androidx.work.runtime.ktx)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.okhttp)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.compose)
  testImplementation(libs.roborazzi.junit.rule)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
}
