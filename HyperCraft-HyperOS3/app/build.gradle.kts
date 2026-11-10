plugins { id("com.android.application") }

android {
    namespace = "io.github.dalong.hyperhome"
    compileSdk = 37
    defaultConfig {
        applicationId = "io.github.dalong.hypercraft"
        minSdk = 35
        targetSdk = 36
        versionCode = 38
        versionName = "0.3.0-hyperos3.1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            // Initial builds use the local development key; production distribution needs a private key.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    packaging {
        resources.merges += "META-INF/xposed/*"
        jniLibs.useLegacyPackaging = false
    }
    lint { disable += "SetTextI18n" }
}
dependencies {
    implementation(project(":streamline"))
    compileOnly("io.github.libxposed:api:102.0.0")
    implementation("io.github.libxposed:service:102.0.0")
    testImplementation("junit:junit:4.13.2")
}
