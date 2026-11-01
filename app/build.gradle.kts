import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.teacher.shotlab"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.teacher.shotlab"
        minSdk = 29
        targetSdk = 34
        versionCode = 108
        versionName = "1.8.78"

        // 只打包手机实际用的两种 ABI：TFLite 原生库默认带多种，只留手机用的两种
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    // 发布签名：从项目根目录的 keystore.properties（已 gitignore，不入库）读取。
    // 没有该文件时（例如 clone 后未配置）不配置 release 签名，debug 构建不受影响；
    // 需要打 release 包时，复制 keystore.properties.example 为 keystore.properties 并填好即可。
    val keystorePropsFile = rootProject.file("keystore.properties")
    val keystoreProps: Properties? = if (keystorePropsFile.exists()) {
        Properties().apply { keystorePropsFile.inputStream().use { load(it) } }
    } else {
        null
    }

    signingConfigs {
        if (keystoreProps != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile")!!)
                storePassword = keystoreProps.getProperty("storePassword")!!
                keyAlias = keystoreProps.getProperty("keyAlias")!!
                keyPassword = keystoreProps.getProperty("keyPassword")!!
                enableV1Signing = true   // 兼容老安装器
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        release {
            // 代码混淆 + 资源裁剪：类名/方法名会被压成短名，显著提高反编译门槛
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    implementation("androidx.exifinterface:exifinterface:1.3.7")

    // 离线关键词唤醒：TFLite 运行时（含 JNI，约 3-4MB）
    implementation("org.tensorflow:tensorflow-lite:2.16.1")

    // 启动封面（SplashScreen）：自定义圆形图标，替代系统默认方形 launcher 图标
    implementation("androidx.core:core-splashscreen:1.0.1")
}
