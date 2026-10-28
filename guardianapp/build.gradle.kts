import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.jiarenxia.guardian"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.jiarenxia.guardian"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "1.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    // 正式版签名与家人侠共用同一份 keystore.properties（工程根目录，不入库；
    // 守护侠与家人侠同一把密钥，见 ROADMAP C1）。文件缺失时 release 保持未签名。
    val signingPropsFile = rootProject.file("keystore.properties")
    if (signingPropsFile.exists()) {
        val props = Properties().apply {
            signingPropsFile.inputStream().use { load(it) }
        }
        signingConfigs {
            create("release") {
                storeFile = rootProject.file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // debug 签名随机器而异：换 applicationId 与正式版共存
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 无 keystore.properties 时保持未签名，绝不回落到 debug 签名
            if (signingPropsFile.exists()) signingConfig = signingConfigs.getByName("release")
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

    testOptions {
        // Robolectric JVM 单元测试需要读取资源与清单
        unitTests.isIncludeAndroidResources = true
        // Roborazzi 截图：record 模式直接把 PNG 写到 build/outputs/roborazzi
        unitTests.all { it.systemProperty("roborazzi.test.record", "true") }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // 守护引擎与首页完全来自共享模块；不依赖 :app（无 Room/Hilt/家庭数据）
    implementation(project(":feature:guardian"))
    implementation(project(":core:designsystem"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.compose.ui.test.manifest)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    testImplementation(libs.compose.ui.test.junit4)
}
