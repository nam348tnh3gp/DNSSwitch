import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.dnsswitch"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dnsswitch"
        minSdk = 24
        targetSdk = 34
        versionCode = 261003
        versionName = "26.10.3"
    }

    signingConfigs {
        create("release") {
            val propsFile = rootProject.file("keystore.properties")
            val localKs = rootProject.file("keystore/dnsswitch-release.jks")
            when {
                propsFile.exists() -> {
                    val props = Properties()
                    propsFile.inputStream().use { props.load(it) }
                    storeFile = file(props.getProperty("storeFile"))
                    storePassword = props.getProperty("storePassword")
                    keyAlias = props.getProperty("keyAlias")
                    keyPassword = props.getProperty("keyPassword")
                }
                localKs.exists() -> {
                    storeFile = localKs
                    storePassword = "dnsswitch"
                    keyAlias = "dnsswitch"
                    keyPassword = "dnsswitch"
                }
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (
                rootProject.file("keystore.properties").exists() ||
                rootProject.file("keystore/dnsswitch-release.jks").exists()
            ) signingConfigs.getByName("release") else null
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
        buildConfig = false
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.okhttp)
    implementation(files("libs/odohclient.aar"))
    testImplementation(libs.junit)
    testImplementation(libs.json)
}
