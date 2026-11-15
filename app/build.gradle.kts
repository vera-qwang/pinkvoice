plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.vera.pinkvoice"
    compileSdk = 36
    buildToolsVersion = "36.1.0"

    defaultConfig {
        applicationId = "com.vera.pinkvoice"
        minSdk = 26
        targetSdk = 36
        // 版本号可以由 CI 用 -PversionCode= / -PversionName= 覆盖
        versionCode = providers.gradleProperty("versionCode").orNull?.toIntOrNull() ?: 1
        versionName = providers.gradleProperty("versionName").orNull ?: "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // 工程内自带 debug keystore，避免依赖 ~/.android（沙箱/CI 环境常不可写）
        create("localDebug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }

        // 发布签名。
        //
        // CI 通过环境变量注入正式密钥：
        //   PINKVOICE_KEYSTORE_PATH      keystore 文件路径
        //   PINKVOICE_KEYSTORE_PASSWORD  密钥库口令
        //   PINKVOICE_KEY_ALIAS          密钥别名
        //   PINKVOICE_KEY_PASSWORD       密钥口令
        //
        // 没配这些变量时退回工程内的 debug keystore——这样 Release workflow
        // 不会因为"没配密钥"直接失败，用户也总能下到一个能装的 APK。
        // （用正式密钥时记得把 release.keystore 加进 .gitignore）
        create("release") {
            val path = providers.environmentVariable("PINKVOICE_KEYSTORE_PATH").orNull
            if (path != null && file(path).exists()) {
                storeFile = file(path)
                storePassword = providers.environmentVariable("PINKVOICE_KEYSTORE_PASSWORD").orNull ?: "android"
                keyAlias = providers.environmentVariable("PINKVOICE_KEY_ALIAS").orNull ?: "androiddebugkey"
                keyPassword = providers.environmentVariable("PINKVOICE_KEY_PASSWORD").orNull ?: "android"
            } else {
                storeFile = rootProject.file("debug.keystore")
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("localDebug")
        }
        release {
            signingConfig = signingConfigs.getByName("release")
            // 先不开 R8：Compose + R8 的规则容易踩坑，
            // 而 Release APK 的体积本来就不敏感。
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
}
