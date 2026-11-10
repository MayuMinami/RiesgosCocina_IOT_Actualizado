import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

val iot = Properties().apply {
    val archivo = rootProject.file("iot.properties")
    if (archivo.exists()) archivo.inputStream().use { load(it) }
}

fun iotProp(nombre: String): String = "\"" + iot.getProperty(nombre, "") + "\""

android {
    namespace = "com.valentina.riesgoscocina"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.valentina.riesgoscocina"
        minSdk = 24
        targetSdk = 37
        versionCode = 2
        versionName = "2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "MQTT_URL", iotProp("MQTT_URL"))
        buildConfigField("String", "MQTT_HMAC_KEY", iotProp("MQTT_HMAC_KEY"))
        buildConfigField("String", "MQTT_PREFIJO", iotProp("MQTT_PREFIJO"))
        buildConfigField("String", "SYNC_URL", iotProp("SYNC_URL"))
        buildConfigField(
            "String", "API_URL",
            "\"" + iot.getProperty("API_URL", "https://192.168.1.9/api/") + "\""
        )
        buildConfigField("String", "SYNC_TOKEN", iotProp("SYNC_TOKEN"))
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.volley)
    implementation(libs.paho.mqtt)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
