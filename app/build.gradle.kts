plugins {
    id("emberlight.android.application")
    id("emberlight.android.application.compose")
    id("emberlight.hilt")
}

android {
    androidResources {
        // Per-App-Sprache (Android 13+): erzeugt die Locale-Konfiguration aus den values-Ordnern.
        generateLocaleConfig = true
    }

    buildTypes {
        // Mess-Build (ADR 0011): Release-Optimierung (R8, Shrink, Profilierbarkeit), aber mit dem Debug-Schlüssel signiert,
        // damit er ohne Release-Keystore installierbar ist. Nicht debuggbar, Demo-Bibliothek an.
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".benchmark"
            matchingFallbacks += listOf("release")
            isDebuggable = false
        }
    }

    // Die Demo-Bibliothek (ADR 0011) liegt in src/demo. Sie gehört zu Debug und Benchmark, nie zu Release.
    sourceSets {
        getByName("debug") {
            kotlin.directories.add("src/demo/kotlin")
            res.directories.add("src/demo/res")
        }
        getByName("benchmark") {
            kotlin.directories.add("src/demo/kotlin")
            res.directories.add("src/demo/res")
        }
    }
}

dependencies {
    implementation(project(":core:design"))
    implementation(project(":feature:gallery"))
    implementation(project(":feature:space"))
    implementation(project(":core:media"))
    // Spike-Server (ADR 0012) nur in Debug und Benchmark (Mess-Build mit R8). Der Release-Build enthält ihn nicht.
    debugImplementation(project(":share:server"))
    add("benchmarkImplementation", project(":share:server"))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.coil.core)
    implementation(libs.telephoto.zoomable.image)
    implementation(libs.coil.video)
    implementation(libs.androidx.compose.material3.adaptive.navigation.suite)
    // Nur für den Demo-Schalter (src/demo). Ohne Demo-Source-Set wird die Bibliothek nicht gebraucht, sie ist aber klein.
    implementation(libs.androidx.datastore.preferences)

    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.hilt.android.testing)
    testImplementation(libs.coil.test)
    kspTest(libs.hilt.compiler)
}
