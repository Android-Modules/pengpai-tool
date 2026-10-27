import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.dalong.pengpaitool"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.dalong.pengpaitool"
        minSdk = 31
        targetSdk = 36
        versionCode = 44
        versionName = "3.1.5"
    }

    // 固定签名：模块升级时新版 APK 才能覆盖安装。签名文件不进仓库，没有时用调试签名构建
    val keyProps = Properties().apply {
        rootProject.file("keystore/keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }
    val fixedSigning = if (keyProps.isEmpty) null else signingConfigs.create("hac") {
        storeFile = rootProject.file(keyProps.getProperty("storeFile"))
        storePassword = keyProps.getProperty("storePassword")
        keyAlias = keyProps.getProperty("keyAlias")
        keyPassword = keyProps.getProperty("keyPassword")
    }

    buildTypes {
        release {
            signingConfig = fixedSigning ?: signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            fixedSigning?.let { signingConfig = it }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            merges += "META-INF/xposed/*"
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    compileOnly(libs.libxposed.api)
    implementation(libs.libxposed.service)
}
