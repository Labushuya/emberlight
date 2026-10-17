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

// Viewer-Goldens laufen getrennt vom Hauptlauf, jede Klasse in einem eigenen Prozess (`forkEvery = 1`, ADR 0010).
// Im Gesamtlauf der Galerie-Tests entstehen leere Aufnahmen, gemeinsam in einem Prozess schlagen Klassen fehl. Die Ursache
// ist nicht geklärt (ADR 0006, Befund), deshalb die Isolation.
val viewerGoldenClasses = "**/viewer/Viewer*ScreenshotTest.class"
// Die Android-Unit-Test-Tasks entstehen erst nach der Auswertung des Moduls.
// Zwei Aufgaben mit gleichem Aufbau: `testViewerGoldens` verifiziert (von `check` und `verifyRoborazziDebug`
// aufgerufen), `recordViewerGoldens` nimmt auf (von `recordRoborazziDebug` aufgerufen, siehe ADR 0006).
afterEvaluate {
    val unitTestTask = tasks.named<Test>("testDebugUnitTest")
    unitTestTask.configure { exclude(viewerGoldenClasses) }

    fun viewerGoldenTask(
        taskName: String,
        taskDescription: String,
        record: Boolean,
    ) = tasks.register<Test>(taskName) {
        group = "verification"
        description = taskDescription
        testClassesDirs = unitTestTask.get().testClassesDirs
        classpath = unitTestTask.get().classpath
        include(viewerGoldenClasses)
        forkEvery = 1
        systemProperties(unitTestTask.get().systemProperties)
        // Roborazzi setzt seine Schalter nur auf testDebugUnitTest. Ohne diese Zeilen liefen die Viewer-Goldens
        // ohne Vergleich durch. Deshalb hier explizit: Verifikation, außer bei der Aufnahme.
        systemProperty("roborazzi.test.record", record.toString())
        systemProperty("roborazzi.test.verify", (!record).toString())
    }

    val verifyViewerGoldens =
        viewerGoldenTask(
            "testViewerGoldens",
            "Viewer-Goldens verifizieren, je Klasse ein eigener Prozess (ADR 0010, ADR 0006).",
            record = false,
        )
    val recordViewerGoldens =
        viewerGoldenTask(
            "recordViewerGoldens",
            "Viewer-Goldens neu aufnehmen, je Klasse ein eigener Prozess. Läuft mit recordRoborazziDebug (ADR 0006).",
            record = true,
        )

    tasks.named("check") { dependsOn(verifyViewerGoldens) }
    tasks.named("verifyRoborazziDebug") { dependsOn(verifyViewerGoldens) }
    tasks.named("recordRoborazziDebug") { dependsOn(recordViewerGoldens) }
}
