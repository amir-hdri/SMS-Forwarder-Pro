import java.io.File
import java.net.URI
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "A private one-use capability must never enter a shared build cache")
abstract class HubProvisioningAssets : DefaultTask() {
  @get:InputFile @get:Optional abstract val profileFile: RegularFileProperty
  @get:Input abstract val checkoutRoot: Property<String>
  @get:Input abstract val trustedEndpoint: Property<String>
  @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

  @TaskAction fun generate() {
    val output = outputDirectory.get().asFile
    // This runs for every Hub build, including public builds after a private build.
    output.deleteRecursively()
    output.mkdirs()
    val source = profileFile.orNull?.asFile ?: return
    require(!project.gradle.startParameter.isBuildCacheEnabled) { "Private Hub builds require --no-build-cache" }
    require(!source.canonicalPath.startsWith(checkoutRoot.get() + File.separator)) {
      "Hub provisioning profile must be an external private file"
    }
    val document = source.readText()
    require(document.length <= 4096)
    val fields = groovy.json.JsonSlurper().parseText(document) as? Map<*, *>
      ?: error("Invalid private Hub profile")
    require(fields.keys == setOf("protocol", "server", "capability", "expires_at"))
    require(fields["protocol"] == "barpro-hub-install-v1")
    require((fields["capability"] as? String)?.matches(Regex("[A-Za-z0-9_-]{43}")) == true)
    val server = URI(fields["server"] as String)
    val trusted = URI(trustedEndpoint.get())
    require(server.scheme == "https" && server.host != null && server.userInfo == null &&
      server.rawQuery == null && server.fragment == null && server.path in listOf("", "/"))
    val serverPort = if (server.port == -1) 443 else server.port
    val trustedPort = if (trusted.port == -1) 443 else trusted.port
    require(server.host == trusted.host && serverPort == trustedPort)
    val expires = (fields["expires_at"] as? Number)?.toLong() ?: error("Missing Hub expiry")
    val now = System.currentTimeMillis() / 1000L
    require(expires > now && expires - now <= 86400L) { "Private Hub capability expired or exceeds 24 hours" }
    File(output, "barpro_hub_install.json").writeText(document)
  }
}

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
val privateHubProfile = providers.gradleProperty("BARPRO_HUB_PROVISIONING_PROFILE_FILE")

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
    buildConfigField("String", "APP_ROLE", "\"COMMON\"")
    buildConfigField("boolean", "HAS_PRIVATE_HUB_PROFILE", "false")
    buildConfigField("String", "DEFAULT_HUB_PHONE_MCI", "\"\"")
    buildConfigField("String", "DEFAULT_HUB_PHONE_IRANCELL", "\"\"")
  }

  flavorDimensions += "role"
  productFlavors {
    create("driver") {
      dimension = "role"
      applicationIdSuffix = ".driver"
      versionNameSuffix = "-driver"
      buildConfigField("String", "APP_ROLE", "\"DRIVER\"")
      val driverConfigUrl = providers.gradleProperty("BARPRO_DRIVER_CONFIG_URL").orNull ?: defaultConfigUrl
      val driverUpdateUrl = providers.gradleProperty("BARPRO_DRIVER_UPDATE_URL").orNull ?: defaultUpdateUrl
      buildConfigField("String", "DEFAULT_CONFIG_URL", "\"$driverConfigUrl\"")
      buildConfigField("String", "DEFAULT_UPDATE_URL", "\"$driverUpdateUrl\"")
      val hubMci = providers.gradleProperty("BARPRO_HUB_PHONE_MCI").orNull ?: ""
      val hubIrancell = providers.gradleProperty("BARPRO_HUB_PHONE_IRANCELL").orNull ?: ""
      buildConfigField("String", "DEFAULT_HUB_PHONE_MCI", "\"$hubMci\"")
      buildConfigField("String", "DEFAULT_HUB_PHONE_IRANCELL", "\"$hubIrancell\"")
    }
    create("hub") {
      dimension = "role"
      applicationIdSuffix = ".hub"
      versionNameSuffix = "-hub"
      buildConfigField("String", "APP_ROLE", "\"HUB\"")
      buildConfigField("boolean", "HAS_PRIVATE_HUB_PROFILE", privateHubProfile.isPresent.toString())
      val hubConfigUrl = providers.gradleProperty("BARPRO_HUB_CONFIG_URL").orNull ?: defaultConfigUrl
      val hubUpdateUrl = providers.gradleProperty("BARPRO_HUB_UPDATE_URL").orNull ?: defaultUpdateUrl
      buildConfigField("String", "DEFAULT_CONFIG_URL", "\"$hubConfigUrl\"")
      buildConfigField("String", "DEFAULT_UPDATE_URL", "\"$hubUpdateUrl\"")
      buildConfigField("String", "DEFAULT_HUB_PHONE_MCI", "\"\"")
      buildConfigField("String", "DEFAULT_HUB_PHONE_IRANCELL", "\"\"")
    }
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

// Operator-facing artifact names, produced by the build itself rather than a manual copy after
// `assemble*`, so the filenames quoted in the deployment SOP are reproducible from the Gradle
// command alone. AGP 9 removed the old `applicationVariants`/`outputFileName` hook, so this reads
// the variant's APK artifact and copies it under the distribution name.
androidComponents {
  onVariants { variant ->
    if (variant.name.startsWith("hub")) {
      val generate = tasks.register<HubProvisioningAssets>("generate${variant.name.replaceFirstChar { it.uppercaseChar() }}ProvisioningAssets") {
        if (privateHubProfile.isPresent) profileFile.set(file(privateHubProfile.get()))
        checkoutRoot.set(rootDir.canonicalPath)
        notCompatibleWithConfigurationCache("Private capability/cache authorization uses the current invocation, never a captured prior flag")
        trustedEndpoint.set(defaultEndpoint.ifBlank { "https://87.107.160.251/api/v1/otp/sms-forwarder" })
        outputDirectory.set(layout.buildDirectory.dir("generated/privateHubAssets/${variant.name}"))
        outputs.upToDateWhen { false }
        outputs.cacheIf { false }
      }
      variant.sources.assets?.addGeneratedSourceDirectory(generate, HubProvisioningAssets::outputDirectory)
    }
    val distributionName = when {
      variant.name.startsWith("driver") -> "Forward-BarPro-Driver"
      variant.name.startsWith("hub") -> "Forward-BarPro-Hub"
      else -> null
    }
    if (distributionName != null) {
      val variantTaskName = variant.name.replaceFirstChar { it.uppercaseChar() }
      val targetFileName = "$distributionName-${variant.name}.apk"
      val apkDirectory = variant.artifacts.get(com.android.build.api.artifact.SingleArtifact.APK)
      val copyTask = tasks.register<Copy>("copy${variantTaskName}DistributionApk") {
        group = "distribution"
        description = "Copies the ${variant.name} APK to outputs/distribution/$targetFileName"
        from(apkDirectory) { include("*.apk") }
        // Deliberately not outputs/apk: that directory is owned by AGP's own APK-listing tasks,
        // and writing into it makes Gradle reject the build for an undeclared task dependency.
        into(layout.buildDirectory.dir("outputs/distribution"))
        rename { targetFileName }
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
      }
      tasks.matching { it.name == "assemble$variantTaskName" }
        .configureEach { finalizedBy(copyTask) }
    }
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
