plugins {
    id("emberlight.android.library")
    id("emberlight.space.isolation")
    id("emberlight.room")
}

dependencies {
    // Domäne und Speicher des Space (ADR 0014). Ohne Ktor, ohne Oberfläche, ohne Galerie.
    implementation(libs.kotlinx.coroutines.core)
    // Ausrichtung nach EXIF bei der Vorschau unter API 28 (ADR 0019, Paket 3.5a-2).
    implementation(libs.androidx.exifinterface)

    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // Gegenprobe der Standortentfernung (ADR 0015): ExifInterface und metadata-extractor müssen nach dem Bereinigen
    // keinen Standort mehr melden. Nur Tests, keine Laufzeitabhängigkeit.
    testImplementation(libs.androidx.exifinterface)
    testImplementation(libs.metadata.extractor)
}
