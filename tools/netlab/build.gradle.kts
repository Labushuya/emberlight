// Messwerkzeug emberlight Netlab (Meilenstein 4, Paket 4.0). Wegwerf- bzw. Messcode, getrennt von der Haupt-App.
// Keine Abhängigkeit zu app, core, feature oder share (Prüfung checkNetlabIsolation).
plugins {
    id("emberlight.android.application")
    id("emberlight.android.application.compose")
    id("emberlight.netlab.isolation")
}

android {
    // Eigene Kennung (Vorgabe Paket 4.0). Das Convention-Plugin setzt die Kennung der Haupt-App, deshalb hier überschrieben.
    namespace = "io.github.labushuya.emberlight.netlab"
    defaultConfig {
        applicationId = "io.github.labushuya.emberlight.netlab"
    }
    buildTypes {
        getByName("debug") {
            // Die Debug-Variante trägt die Kennung ohne Suffix, damit sie der Vorgabe entspricht.
            applicationIdSuffix = null
        }
    }
}

// Release-Abgrenzung: Alle Release-Aufgaben dieses Moduls sind abgeschaltet. Root-Aufgaben wie `assembleRelease`
// (Release-Workflow, CI) bauen damit keine Netlab-APK. Die Variante selbst bleibt bestehen, damit die gemeinsame
// Prüfung `checkNoPlayServices` den Release-Klassenpfad weiter auflösen kann. Belegt durch den Lauf ohne Netlab-Tasks.
tasks.configureEach {
    if (name.contains("Release")) {
        enabled = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.coroutines.core)
    // QR-Codes erzeugen (ZXing core) und lesen (ZXing core über CameraX-Bilder). Keine Play-Services-Abhängigkeit.
    implementation(libs.zxing.core)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // kotlin.test-Assertions auf JUnit 4 (gleicher Runner wie die übrigen Module).
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
}
