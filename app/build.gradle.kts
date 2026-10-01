import java.util.Properties

plugins {
    id("com.android.application") version "8.6.0"
    id("org.jetbrains.kotlin.android") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.isFile) {
        keystorePropertiesFile.inputStream().use(::load)
    }
}
val releaseTaskRequested = gradle.startParameter.taskNames.any {
    it.contains("release", ignoreCase = true)
}

// -PbardSideBySide=true builds "Bard Lock" (com.stan.libbylight.lock), which installs next to
// an existing Bard without replacing it or its Libby session.
val sideBySide = providers.gradleProperty("bardSideBySide").orNull == "true"

if (releaseTaskRequested && !keystorePropertiesFile.isFile) {
    throw GradleException(
        "Release signing requires the ignored keystore.properties file. See RELEASE.md.",
    )
}

android {
    namespace = "com.stan.libbylight"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.stan.libbylight"
        minSdk = 33
        targetSdk = 34
        versionCode = 7
        versionName = "0.1.0-alpha5-lockscreen2"
        if (sideBySide) {
            applicationIdSuffix = ".lock"
            versionNameSuffix = "-sidebyside"
            resValue("string", "app_name", "Bard Lock")
        } else {
            resValue("string", "app_name", "Bard")
        }
    }

    signingConfigs {
        if (keystorePropertiesFile.isFile) {
            create("release") {
                storeFile = file(requireNotNull(keystoreProperties.getProperty("storeFile")))
                storePassword = requireNotNull(keystoreProperties.getProperty("storePassword"))
                keyAlias = requireNotNull(keystoreProperties.getProperty("keyAlias"))
                keyPassword = requireNotNull(keystoreProperties.getProperty("keyPassword"))
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            isShrinkResources = false
            if (keystorePropertiesFile.isFile) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    sourceSets["main"].kotlin.srcDirs("src/main/kotlin")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    packaging {
        resources.excludes.add("/META-INF/{AL2.0,LGPL2.1}")
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.runtime:runtime")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.webkit:webkit:1.9.0")
    implementation("androidx.media:media:1.7.0")
    implementation("com.github.lightphone:light-keyboard:v0.0.13")

    testImplementation("junit:junit:4.13.2")
}
