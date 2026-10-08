plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}
val configuredServerUrl = providers.gradleProperty("zhiyuBackendUrl").orNull.orEmpty().trim()
val usbServerUrl = "http://127.0.0.1:8791"
require(!configuredServerUrl.contains('"') && !configuredServerUrl.contains('\\') && configuredServerUrl.none { it.code < 32 }) {
    "zhiyuBackendUrl contains invalid characters"
}
val buildsRelease = gradle.startParameter.taskNames.any { it.contains("release", ignoreCase = true) }
require(!buildsRelease || configuredServerUrl.isBlank() || configuredServerUrl.startsWith("https://")) {
    "Release builds require an HTTPS zhiyuBackendUrl"
}
android {
    namespace = "com.zhiyu.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.zhiyu.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "0.1.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true; buildConfig = true }
    buildTypes {
        debug {
            buildConfigField("String", "DEFAULT_SERVER_URL", "\"${configuredServerUrl.ifBlank { usbServerUrl }}\"")
            buildConfigField("boolean", "USE_USB_BACKEND", (configuredServerUrl.isBlank() || configuredServerUrl == usbServerUrl).toString())
        }
        release {
            buildConfigField("String", "DEFAULT_SERVER_URL", "\"${configuredServerUrl.takeIf { it.startsWith("https://") }.orEmpty()}\"")
            buildConfigField("boolean", "USE_USB_BACKEND", "false")
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}
dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
