plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.jetbrainsKotlinAndroid)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "io.anonero"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.anonero"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "1.0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        ndk {
            //noinspection ChromeOsAbiSupport
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
        packaging {
            jniLibs.useLegacyPackaging = true
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            // CI builds get a unique application ID so every APK can be installed side-by-side.
            val buildNumber = project.findProperty("buildNumber")?.toString()
            if (!buildNumber.isNullOrBlank()) {
                applicationIdSuffix = ".build$buildNumber"
            }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
        create("stageNet") {
            isMinifyEnabled = false
            isDebuggable = true
            applicationIdSuffix = ".stagenet"
            versionNameSuffix = "-stagenet"
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path = file("CMakeLists.txt")
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }

    }

    val customFontAssetDir = layout.buildDirectory.dir("generated/custom-font-assets")

    sourceSets.getByName("main").assets.srcDir(customFontAssetDir)

    tasks.register<Copy>("prepareCustomFontAsset") {
        from(rootProject.file("160ee2f7b959256f6a2e09db2fa9060b.ttf"))
        into(customFontAssetDir)
    }

    tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach {
        dependsOn("prepareCustomFontAsset")
    }

    flavorDimensions += "anon_mode"

    productFlavors {
        create("anon") {
            applicationIdSuffix = ".anon"
            resValue("string", "app_name", "ANON中文")
            dimension = "anon_mode"
            buildConfigField("String", "FLAVOR", "\"anon\"")
            buildConfigField("boolean", "VIEW_ONLY", "false")
        }
        create("nero") {
            applicationIdSuffix = ".nero"
            resValue("string", "app_name", "NERO")
            dimension = "anon_mode"
            buildConfigField("String", "FLAVOR", "\"nero\"")
            buildConfigField("boolean", "VIEW_ONLY", "true")
        }

    }
    buildToolsVersion = "36.0.0"
    ndkVersion = "29.0.13599879"
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)