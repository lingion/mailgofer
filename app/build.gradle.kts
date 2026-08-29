import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// 个人定制版预填配置 — personal.properties(git-ignored,永不入库)
// 格式: presetHost=api.qdp.qzz.io / presetPort= / presetDomain=mail.qdp.qzz.io / presetToken=xxx
val personalProps = Properties().apply {
    val f = rootProject.file("personal.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun preset(name: String): String = personalProps.getProperty(name) ?: ""

android {
    namespace = "com.lingion.mailgofer"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.lingion.mailgofer"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "1.2.0"

        // 预填配置进 BuildConfig;public 构建时 personal.properties 不存在 → 全为空串
        buildConfigField("String", "PRESET_HOST", "\"${preset("presetHost")}\"")
        buildConfigField("String", "PRESET_PORT", "\"${preset("presetPort")}\"")
        buildConfigField("String", "PRESET_DOMAIN", "\"${preset("presetDomain")}\"")
        buildConfigField("String", "PRESET_TOKEN", "\"${preset("presetToken")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            // 个人版 applicationId 后缀,可与公开版共存
            applicationIdSuffix = ".personal"
            versionNameSuffix = "-personal"
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
    val composeBom = platform("androidx.compose:compose-bom:2024.10.00")
    implementation(composeBom)

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.navigation:navigation-compose:2.8.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.17.0")

    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    testImplementation("junit:junit:4.13.2")
}
