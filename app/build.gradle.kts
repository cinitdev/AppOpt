plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "top.qixia.threads"
    compileSdk = 36

    defaultConfig {
        applicationId = "top.qixia.threads"
        minSdk = 31
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = 202
        versionName = "v2.0.2"
    }

    signingConfigs {
        create("release") {
            storeFile = file("release.jks")
            storePassword = "appopt123"
            keyAlias = "appopt"
            keyPassword = "appopt123"
        }
        getByName("debug") {
            storeFile = file("release.jks")
            storePassword = "appopt123"
            keyAlias = "appopt"
            keyPassword = "appopt123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
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
        compose = true
    }
}

dependencies {
    val composeBom = platform(libs.androidx.compose.bom)

    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    implementation(libs.markwon.core)
    implementation(libs.markwon.ext.strikethrough)
    implementation(libs.markwon.ext.tables)
    implementation(libs.markwon.ext.tasklist)
    implementation(libs.markwon.html)
    implementation(libs.markwon.linkify)
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

// 传感器、单位换算、CPU 差分与计数均由 Rust 负责，不打包 Shell/C 采集资源。
val historyProbeOutput = layout.buildDirectory.dir("generated/historyRustAssets")
val buildHistoryProbe by tasks.registering {
    val source = rootProject.file("native_daemon/history_probe")
    inputs.files(fileTree(source) { include("Cargo.toml", "Cargo.lock", "src/**/*.rs") })
    inputs.files(fileTree(rootProject.file("native_daemon/kernel_info")) { include("Cargo.toml", "src/**/*.rs") })
    outputs.dir(historyProbeOutput)
    doLast {
        val host = if (System.getProperty("os.name").startsWith("Windows")) "windows-x86_64" else if (System.getProperty("os.name").startsWith("Mac")) "darwin-x86_64" else "linux-x86_64"
        val toolchain = android.sdkDirectory.resolve("ndk/28.1.13356709/toolchains/llvm/prebuilt/$host")
        val cargoHome = System.getenv("CARGO_HOME")?.let(::File) ?: File(System.getProperty("user.home"), ".cargo")
        val cargo = cargoHome.resolve("bin/cargo" + if (host.startsWith("windows")) ".exe" else "")
        val targetDir = rootProject.layout.buildDirectory.dir("history-probe-target").get().asFile
        val folder = historyProbeOutput.get().dir("history_rust").asFile.also { it.mkdirs() }
        mapOf("arm64-v8a" to "aarch64-linux-android", "x86_64" to "x86_64-linux-android").forEach { (abi, target) ->
            project.exec {
                environment("CARGO_TARGET_${target.uppercase().replace('-', '_')}_LINKER",
                    toolchain.resolve("bin/${target}31-clang" + if (host.startsWith("windows")) ".cmd" else "").absolutePath)
                commandLine(cargo, "build", "--locked", "--offline", "--release", "--manifest-path", source.resolve("Cargo.toml"), "--target", target, "--target-dir", targetDir)
            }
            targetDir.resolve("$target/release/qixia-history-probe").copyTo(folder.resolve(abi), overwrite = true)
        }
    }
}
android.sourceSets.getByName("main").assets.srcDir(historyProbeOutput)
tasks.named("preBuild").configure { dependsOn(buildHistoryProbe) }
