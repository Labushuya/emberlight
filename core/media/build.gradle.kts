plugins {
    id("emberlight.android.library")
    id("emberlight.hilt")
}

dependencies {
    // Nur die Laufzeit von Compose für @Immutable (Stabilität im UI-Layer). Keine UI-Bibliotheken, keine Compiler-Plugins.
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)
    // Thumbnails: Fetcher und Mapper für MediaStore-URIs (ADR 0008). Nur die Coil-Kernbibliothek, keine Netzwerk-Module.
    implementation(libs.coil.core)
    // EXIF-Angaben für den Infobereich des Viewers (ADR 0010). Keine Play-Services-Abhängigkeit.
    implementation(libs.androidx.exifinterface)

    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
