plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.mszymanski.orknux.mobile"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.mszymanski.orknux.mobile"
        minSdk = 26
        targetSdk = 37
        versionCode = 2
        versionName = "0.1.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    // From the environment, where CI puts its secrets; without them a release build comes out unsigned.
    val keystore = System.getenv("ORKNUX_ANDROID_KEYSTORE_FILE")
    signingConfigs {
        if (keystore != null) {
            create("release") {
                storeFile = file(keystore)
                storePassword = System.getenv("ORKNUX_ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ORKNUX_ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ORKNUX_ANDROID_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            if (keystore != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    buildFeatures { compose = true }
    testOptions { unitTests.isReturnDefaultValues = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.tooling.preview)
    debugImplementation(libs.compose.tooling)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.runtime)
    implementation(libs.navigation.compose)
    implementation(libs.core.ktx)
    implementation(libs.okhttp)
    implementation(libs.serialization.json)
    implementation(libs.markdown.m3)
    implementation(libs.markdown.coil)
    implementation(libs.coil.compose)
    implementation(libs.coil.okhttp)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.coroutines.test)
}
