import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Developer-local config; every key is optional so a fresh checkout still builds.
val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun localString(key: String, default: String = ""): String =
    "\"" + (localProps.getProperty(key) ?: default).replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "com.vadymsidorov.yobot"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.vadymsidorov.yobot"
        minSdk = 34
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "OPENROUTER_API_KEY", localString("yobot.openrouter.apiKey"))
        buildConfigField("String", "MODEL", localString("yobot.model", "anthropic/claude-haiku-4.5"))
        buildConfigField("String", "TELEMETRY_HOST", localString("yobot.telemetry.host"))
        buildConfigField("String", "TELEMETRY_PORT", localString("yobot.telemetry.port", "5555"))
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":core"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
