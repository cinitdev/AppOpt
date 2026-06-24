plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "top.suto.appopt"
    compileSdk = 36

    defaultConfig {
        applicationId = "top.suto.appopt"
        minSdk = 31
        targetSdk = 36
        versionCode = 171
        versionName = "1.7.1"
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
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
