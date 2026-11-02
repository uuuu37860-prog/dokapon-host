plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ถ้า build ไม่ผ่านเพราะหาไลบรารีนี้ไม่เจอ ให้ลองเปลี่ยนเลขเวอร์ชันตรงนี้ (เช่น 0.13.0 หรือ 0.12.0)
val libretroDroidVersion = "0.14.0"

android {
    namespace = "com.bom.dokapon"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.bom.dokapon"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // ให้ไฟล์ .so ของอีมูเลเตอร์ถูกแตกไว้ในเครื่อง เพื่อให้ LibretroDroid เปิดได้
    packaging { jniLibs { useLegacyPackaging = true } }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.github.swordfish90:libretrodroid:$libretroDroidVersion")
    implementation("io.getstream:stream-webrtc-android:1.3.9")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")
}
