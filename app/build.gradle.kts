import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Properties

plugins {
    id("com.android.application")
}

// 显示给用户的版本号，手动修改
val appVersionName = "1.1"

// 每次构建自动递增 versionCode，便于覆盖安装升级：自 2026-01-01 00:00（北京时间）起经过的分钟数
val autoVersionCode = Duration.between(
    LocalDateTime.of(2026, 1, 1, 0, 0),
    LocalDateTime.now(ZoneId.of("Asia/Shanghai")),
).toMinutes().toInt()

// 签名密钥：debug 和 release 都用 signing/release.keystore 签名，发布后必须一直用它，否则无法覆盖升级
val signingDir = rootProject.file("signing")
val signingProps = Properties().apply {
    signingDir.resolve("keystore.properties").inputStream().use { load(it) }
}

android {
    namespace = "net.vsean.gwm_dvr_download"
    compileSdk = 36

    defaultConfig {
        applicationId = "net.vsean.gwm_dvr_download"
        minSdk = 29 // WifiNetworkSpecifier 和 MediaStore RELATIVE_PATH 都需要 Android 10+
        targetSdk = 36
        versionCode = autoVersionCode
        versionName = "$appVersionName.$autoVersionCode"

        // ML Kit 自带原生库，只保留 64 位 ARM 以减小体积
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        create("release") {
            storeFile = signingDir.resolve(signingProps.getProperty("storeFile"))
            storePassword = signingProps.getProperty("storePassword")
            keyAlias = signingProps.getProperty("keyAlias")
            keyPassword = signingProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            // R8 压缩混淆 + 移除未使用的资源
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    // 模型打包进 APK，不依赖 Google Play 服务
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    testImplementation("junit:junit:4.13.2")
}
