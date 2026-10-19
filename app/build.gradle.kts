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

val customFontAssetDir = layout.buildDirectory.dir("generated/custom-font-assets")

android.sourceSets.getByName("main").assets.srcDir(customFontAssetDir)

tasks.register<Copy>("prepareCustomFontAsset") {
    from(rootProject.file("160ee2f7b959256f6a2e09db2fa9060b.ttf"))
    into(customFontAssetDir)
}

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach {
    dependsOn("prepareCustomFontAsset")
}

tasks.matching { it.name.contains("LintVital") || it.name.contains("lintVital") }.configureEach {
    dependsOn("prepareCustomFontAsset")
}


// Build the Rust protocol layer as a separate Android native library. It is kept
// behind the existing JNI library so Java/Kotlin APIs and the UI remain unchanged.
val rustCoreDir = rootProject.file("rust-core")
val generatedRustJniLibs = layout.buildDirectory.dir("generated/rust-jni-libs")
val rustCoreOutput = generatedRustJniLibs.map {
    it.file("arm64-v8a/libanonero_rust_core.so")
}

android.sourceSets.getByName("main").jniLibs.srcDir(generatedRustJniLibs)

val installRustAndroidTarget = tasks.register<Exec>("installRustAndroidTarget") {
    group = "build setup"
    description = "Install the Rust target used by the Android arm64 wallet core."
    commandLine("rustup", "target", "add", "aarch64-linux-android")
}

val buildRustCore = tasks.register<Exec>("buildRustCore") {
    group = "build"
    description = "Build the Rust Monero wallet core for Android arm64-v8a."
    dependsOn(installRustAndroidTarget)
    inputs.file(rustCoreDir.resolve("Cargo.toml"))
    inputs.dir(rustCoreDir.resolve("src"))
    outputs.file(rustCoreOutput)

    doFirst {
        val osName = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase()
        val hostTag = when {
            osName.contains("mac") && (arch == "aarch64" || arch == "arm64") -> "darwin-arm64"
            osName.contains("mac") -> "darwin-x86_64"
            osName.contains("win") -> "windows-x86_64"
            else -> "linux-x86_64"
        }
        val suffix = if (hostTag.startsWith("windows")) ".cmd" else ""
        val linker = file(
            "${android.sdkDirectory}/ndk/${android.ndkVersion}/toolchains/llvm/prebuilt/$hostTag/bin/aarch64-linux-android26-clang$suffix"
        )
        require(linker.isFile) {
            "Rust Android linker not found: ${linker.absolutePath}. Install NDK ${android.ndkVersion} and Rust 1.89+."
        }
        environment("CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER", linker.absolutePath)
        commandLine(
            "cargo", "build", "--release", "--target", "aarch64-linux-android",
            "--manifest-path", rustCoreDir.resolve("Cargo.toml").absolutePath
        )
    }

    doLast {
        val rustLibrary = rustCoreDir.resolve(
            "target/aarch64-linux-android/release/libanonero_rust_core.so"
        )
        require(rustLibrary.isFile) {
            "Rust core build completed without producing ${rustLibrary.absolutePath}"
        }
        copy {
            from(rustLibrary)
            into(generatedRustJniLibs.get().dir("arm64-v8a"))
        }
    }
}

// Ensure Rust exists before either native compilation or APK native-library packaging.
tasks.matching {
    it.name.startsWith("configureCMake") ||
        it.name.startsWith("buildCMake") ||
        it.name.startsWith("externalNativeBuild") ||
        (it.name.startsWith("merge") && it.name.endsWith("JniLibFolders"))
}.configureEach {
    dependsOn(buildRustCore)
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.compose)
    implementation(libs.androidx.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.runtime.livedata)
    implementation(libs.androidx.compose.animation)

    implementation(libs.androidx.navigation.fragment)
    implementation(libs.androidx.navigation.ui.ktx)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)


    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.datastore.preferences.core.jvm)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.camera.view)

    implementation(platform(libs.koin.bom))
    implementation(libs.koin.core)
    implementation(libs.koin.core.coroutines)
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.navigation)
    implementation(libs.koin.androidx.compose)

    implementation(libs.org.jetbrains.kotlin.android)

    implementation(libs.org.boofcv.boofcv.core)
    implementation(libs.com.google.zxing.core)
    implementation(libs.com.google.accompanist.permissions)

    implementation(libs.com.jakewharton.timber)
    implementation(libs.org.bouncycastle.bcprov)
    implementation(libs.com.sparrowwalle.hummingbird)

    implementation(libs.io.matthewnelson.kmp.tor.runtime)
    implementation(libs.io.matthewnelson.kmp.resource.exec.tor)
    implementation(libs.com.sparrowwallet.hummingbird)
    implementation("io.github.dokar3:sonner:0.3.8")
    implementation(libs.io.github.dokar3.sonner)
    implementation(libs.androidx.compose.material3)
    implementation("dev.chrisbanes.haze:haze:1.6.10")
    implementation("androidx.compose.material:material-icons-extended")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

}
