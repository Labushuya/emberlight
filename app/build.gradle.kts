import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.kotlin.dsl.getByType

plugins {
    id("emberlight.android.application")
    id("emberlight.android.application.compose")
    id("emberlight.release.manifest")
    id("emberlight.hilt")
    // Serialisierung für die typsichere Route „Über & Lizenzen“ (ADR 0002, ADR 0020). Die Features tragen es schon selbst.
    id("emberlight.serialization")
    // Lizenzliste „Über & Lizenzen“ (ADR 0020): erzeugt aus den Release-Laufzeitabhängigkeiten eine Ressource.
    id("emberlight.about.libraries")
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
    // Der Debug-Einstieg für Gerätetests (ADR 0014) liegt in src/devtools, ebenfalls nur Debug und Benchmark.
    sourceSets {
        getByName("debug") {
            kotlin.directories.add("src/demo/kotlin")
            res.directories.add("src/demo/res")
            kotlin.directories.add("src/devtools/kotlin")
        }
        getByName("benchmark") {
            kotlin.directories.add("src/demo/kotlin")
            res.directories.add("src/demo/res")
            kotlin.directories.add("src/devtools/kotlin")
        }
    }
}

// Manifest des Debug-Einstiegs (src/devtools/AndroidManifest.xml). Je Source-Set ist nur eine Manifest-Datei vorgesehen,
// der Benchmark-Build hat schon src/benchmark/AndroidManifest.xml. Deshalb wird die Datei je Variante als zusätzliche
// statische Manifest-Datei eingebunden. Der Release-Build bekommt sie nicht.
extensions.getByType<ApplicationAndroidComponentsExtension>().apply {
    onVariants(selector().withBuildType("debug")) { variant ->
        variant.sources.manifests.addStaticManifestFile("src/devtools/AndroidManifest.xml")
    }
    onVariants(selector().withBuildType("benchmark")) { variant ->
        variant.sources.manifests.addStaticManifestFile("src/devtools/AndroidManifest.xml")
    }
}

dependencies {
    implementation(project(":core:design"))
    implementation(project(":feature:gallery"))
    implementation(project(":feature:space"))
    implementation(project(":core:media"))
    // Lokaler Server (ADR 0012). Ohne Aufrufer noch ohne Routen. Die Einbindung macht den Release-Build zur Prüfung von R8.
    implementation(project(":share:server"))
    // Typen des Space für den Debug-Einstieg (src/devtools). Die Abhängigkeit von share:server ist nur implementation.
    implementation(project(":share:space"))

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
    // Lesen der erzeugten Lizenzliste für den Bildschirm „Über & Lizenzen“ (ADR 0020). Nur der Kern, die Oberfläche ist eigene Arbeit.
    implementation(libs.aboutlibraries.core)

    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.hilt.android.testing)
    testImplementation(libs.coil.test)
    kspTest(libs.hilt.compiler)
}
