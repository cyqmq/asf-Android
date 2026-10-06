plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.asfandroid"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.asfandroid"
        minSdk = 26
        targetSdk = 34
        versionCode = 21
        versionName = "1.0.20"

        // 构建脚本注入的配置
        // 注意：AAPT2 会把 assets 中的 .gz 自动解压为 .tar，因此这里用未压缩的 .tar
        buildConfigField("String", "ROOTFS_ASSET", "\"asf/rootfs/asf-rootfs-arm64.tar\"")
        buildConfigField("String", "ASF_IPC_URL", "\"http://127.0.0.1:1242\"")
        buildConfigField("String", "ASF_BINARY", "\"ArchiSteamFarm\"")
        buildConfigField("String", "PROOT_BINARY", "\"libproot.so\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            // 关键：必须把 jniLibs 解压到 nativeLibraryDir，proot 才能被 execve
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.apache.commons:commons-compress:1.26.2")
}