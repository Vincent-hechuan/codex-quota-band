import java.util.Properties

plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.plugin.compose")
  id("org.jetbrains.kotlin.plugin.serialization")
}

val localProperties = Properties().apply {
  val localPropertiesFile = rootProject.file("local.properties")
  if (localPropertiesFile.isFile) {
    localPropertiesFile.inputStream().use(::load)
  }
}
val releaseStoreFile = localProperties.getProperty("codexQuotaReleaseStoreFile")
val releaseStorePassword = localProperties.getProperty("codexQuotaReleaseStorePassword")
val releaseKeyAlias = localProperties.getProperty("codexQuotaReleaseKeyAlias")
val releaseKeyPassword = localProperties.getProperty("codexQuotaReleaseKeyPassword")
val hasReleaseSigning =
  listOf(releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword).all { !it.isNullOrBlank() }
val demoFiveHourQuota =
  providers.gradleProperty("codexQuotaDemoFiveHour").orElse("false").map(String::toBoolean).get()
val instrumentationBuildType =
  providers.gradleProperty("codexQuotaInstrumentationBuildType").orElse("debug").get()

android {
  namespace = "com.codex.quota.android"
  compileSdk = 36
  testBuildType = instrumentationBuildType

  defaultConfig {
    applicationId = "com.codex.quota.android"
    minSdk = 26
    targetSdk = 36
    // Keep the install sequence monotonic so 0.6.3 can replace earlier releases
    // without uninstalling user pairing data.
    versionCode = 605
    versionName = "0.6.3"
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    buildConfigField("boolean", "DEMO_FIVE_HOUR_QUOTA", demoFiveHourQuota.toString())
  }

  if (hasReleaseSigning) {
    signingConfigs {
      create("release") {
        storeFile = file(requireNotNull(releaseStoreFile))
        storePassword = requireNotNull(releaseStorePassword)
        keyAlias = requireNotNull(releaseKeyAlias)
        keyPassword = requireNotNull(releaseKeyPassword)
      }
    }
  }

  buildTypes {
    release {
      signingConfig = signingConfigs.findByName("release")
      isMinifyEnabled = true
      proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro",
      )
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  buildFeatures {
    compose = true
    aidl = false
    buildConfig = true
    shaders = false
  }

  packaging {
    resources {
      excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
  }
}

if (
  !hasReleaseSigning &&
    gradle.startParameter.taskNames.any { taskName -> taskName.contains("release", ignoreCase = true) }
) {
  throw GradleException(
    "Release signing is required. Set codexQuotaReleaseStoreFile, codexQuotaReleaseStorePassword, codexQuotaReleaseKeyAlias, and codexQuotaReleaseKeyPassword in ignored android-app/local.properties.",
  )
}

kotlin {
  jvmToolchain(17)
}

dependencies {
  implementation(files("libs/xms-wearable-lib_1.4_release.aar"))

  val composeBom = platform("androidx.compose:compose-bom:2026.03.01")
  implementation(composeBom)

  implementation("androidx.core:core-ktx:1.18.0")
  implementation("androidx.activity:activity-compose:1.13.0")
  implementation("androidx.camera:camera-camera2:1.6.1")
  implementation("androidx.camera:camera-lifecycle:1.6.1")
  implementation("androidx.camera:camera-view:1.6.1")
  implementation("androidx.compose.material3:material3")
  implementation("androidx.compose.material:material-icons-extended")
  implementation("androidx.compose.ui:ui")
  implementation("androidx.compose.ui:ui-tooling-preview")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
  implementation("com.squareup.okhttp3:okhttp:5.4.0")
  implementation("com.google.mlkit:barcode-scanning:17.3.0")
  debugImplementation("androidx.compose.ui:ui-tooling")
  androidTestImplementation(composeBom)
  androidTestImplementation("androidx.compose.ui:ui-test-junit4")
  androidTestImplementation("androidx.test.ext:junit:1.3.0")
  androidTestImplementation("androidx.test:runner:1.7.0")
  testImplementation("junit:junit:4.13.2")
}
