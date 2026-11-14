import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val projectProperties = Properties().apply {
    rootProject.file("gradle.properties").inputStream().use { load(it) }
}
val appVersionName = (project.findProperty("VERSION_NAME") as String?)
    ?: System.getenv("VERSION_NAME")
    ?: projectProperties.getProperty("versionName")
    ?: "1.0.0"
val appVersionCode = (project.findProperty("VERSION_CODE") as String?)
    ?.toIntOrNull()
    ?: System.getenv("VERSION_CODE")?.toIntOrNull()
    ?: 1

android {
    namespace = "io.github.midihl.wastickersimporter"
    compileSdk = 35

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "io.github.midihl.wastickersimporter"
        minSdk = 24
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "CONTENT_PROVIDER_AUTHORITY", "\"${applicationId}.stickercontentprovider\"")
        manifestPlaceholders["contentProviderAuthority"] = "${applicationId}.stickercontentprovider"
    }

    signingConfigs {
        create("ciRelease") {
            val keystore = System.getenv("KEYSTORE_FILE")
            if (!keystore.isNullOrBlank()) {
                storeFile = file(keystore)
                storeType = "PKCS12"
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (!System.getenv("KEYSTORE_FILE").isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("ciRelease")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.coordinatorlayout:coordinatorlayout:1.3.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.google.android.material:material:1.12.0")
    testImplementation("junit:junit:4.13.2")
}
