import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.home"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.home"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // ====== SECURITY FIX: Load credentials from local.properties ======
        val properties = Properties()
        val localPropertiesFile = rootProject.file("local.properties")
        if (localPropertiesFile.exists()) {
            properties.load(FileInputStream(localPropertiesFile))
        }

        buildConfigField(
            "String",
            "MQTT_USER",
            "\"${properties.getProperty("MQTT_USER") ?: "Suriya"}\""
        )
        buildConfigField(
            "String",
            "MQTT_PASS",
            "\"${properties.getProperty("MQTT_PASS") ?: "Suriya6anbu6@#"}\""
        )
        buildConfigField(
            "String",
            "ESP_TOKEN",
            "\"${properties.getProperty("ESP_TOKEN") ?: "9f3a2c7e_dev_voice_ai"}\""
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true  // REQUIRED: Enable BuildConfig generation
    }
}

dependencies {

    // Core Android Libraries (STABLE with AGP 8.2.2 + SDK 34)
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.core:core:1.12.0")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.activity:activity:1.8.2")
    implementation("androidx.fragment:fragment:1.6.2")

    // UI
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("com.google.android.material:material:1.11.0")

    // MQTT - Eclipse Paho (REQUIRED)
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")
    implementation("org.eclipse.paho:org.eclipse.paho.android.service:1.1.1")

    // JSON
    implementation("org.json:json:20240303")

    // Unit Testing
    testImplementation("junit:junit:4.13.2")

    // Android Instrumentation Testing
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}