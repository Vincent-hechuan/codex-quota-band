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

android {
  namespace = "com.codex.quota.android"
  compileSdk = 36

  defaultConfig {
    applicationId = "com.codex.quota.android"
    minSdk = 26
    targetSdk = 36
    // Keep the install sequence monotonic so this 0.6.1 local hotfix can replace the
    // replace a local candidate without uninstalling user pairing data.
    versionCode = 602
    versionName = "0.6.1"
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
  implementation("androidx.compose.material3:material3")
  implementation("androidx.compose.material:material-icons-extended")
  implementation("androidx.compose.ui:ui")
  implementation("androidx.compose.ui:ui-tooling-preview")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
  implementation("com.squareup.okhttp3:okhttp:5.4.0")
  debugImplementation("androidx.compose.ui:ui-tooling")
  testImplementation("junit:junit:4.13.2")
}
