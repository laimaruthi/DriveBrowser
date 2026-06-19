import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    // AGP 9+ provides built-in Kotlin support, so the kotlin-android plugin is
    // intentionally NOT applied here (doing so is an error since AGP 9.0).
    alias(libs.plugins.android.application)
}

// Signing properties come from environment variables (CI) or local.properties.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) load(f.inputStream())
}
fun signingProp(key: String): String? = System.getenv(key) ?: localProps.getProperty(key)
val releaseStorePath = signingProp("RELEASE_STORE_FILE")
val hasReleaseSigning = releaseStorePath != null && rootProject.file(releaseStorePath).exists()

android {
    namespace = "com.myapp.drivebrowser"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.myapp.drivebrowser"
        minSdk = 35
        targetSdk = 37
        versionCode = 6
        versionName = "1.5"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            if (hasReleaseSigning) {
                storeFile = rootProject.file(releaseStorePath!!)
                storePassword = signingProp("RELEASE_STORE_PASSWORD")
                keyAlias = signingProp("RELEASE_KEY_ALIAS")
                keyPassword = signingProp("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Minification disabled for now to guarantee a working signed APK without
            // device testing; can be enabled once R8 output is verified on a device.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Use the real release key when available, else fall back to debug signing
            // so local `assembleRelease` still produces an installable APK.
            signingConfig = if (hasReleaseSigning)
                signingConfigs.getByName("release")
            else
                signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.webkit)
    implementation(libs.google.material)
    implementation(libs.androidx.car.app)
    implementation(libs.zxing.core)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
