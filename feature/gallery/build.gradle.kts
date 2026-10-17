plugins {
    id("emberlight.android.feature")
}

// BuildConfig.DEBUG steuert die Debug-Marken des Viewers (ViewerTrace). Im Release entfällt der Aufruf.
android {
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    // Vorschaubilder (ADR 0008). Coil-Compose für AsyncImage, keine Netzwerk-Module.
    implementation(libs.coil.core)
    implementation(libs.coil.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    // BackHandler des Viewers: Zurück führt ins Raster, auch über die Systemgeste.
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.compose.material3)
    implementation(project(":core:media"))

    // Vollbild-Viewer (ADR 0010). Zoom: Telephoto-Kern, ohne Coil-Modul. Video: Media3 mit PlayerSurface, ohne die
    // Material-3-Steuerung, weil der Viewer eine eigene Leiste im Stil der App zeigt. Kein Cast-Modul.
    implementation(libs.telephoto.zoomable.image)
    implementation(libs.telephoto.sub.sampling.image)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui.compose)

    testImplementation(libs.coil.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.androidx.media3.test.utils.robolectric)
}

// Viewer-Goldens laufen in einem eigenen Prozess, getrennt vom Hauptlauf (ADR 0010). Im Gesamtlauf der Galerie-Tests
// entstanden leere Aufnahmen. Gemeinsam in einem Prozess sind alle vier Klassen stabil. Die Ursache ist nicht geklärt
// (ADR 0006, Befund), deshalb die Isolation.
val viewerGoldenClasses = "**/viewer/Viewer*ScreenshotTest.class"
// Die Android-Unit-Test-Tasks entstehen erst nach der Auswertung des Moduls.
afterEvaluate {
    val unitTestTask = tasks.named<Test>("testDebugUnitTest")
    unitTestTask.configure { exclude(viewerGoldenClasses) }
    val viewerGoldenTest =
        tasks.register<Test>("testViewerGoldens") {
            group = "verification"
            description = "Viewer-Goldens, alle Klassen in einem gemeinsamen eigenen Prozess (ADR 0010)."
            testClassesDirs = unitTestTask.get().testClassesDirs
            classpath = unitTestTask.get().classpath
            include(viewerGoldenClasses)
            systemProperties(unitTestTask.get().systemProperties)
        }
    tasks.named("check") { dependsOn(viewerGoldenTest) }
}
