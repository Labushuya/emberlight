plugins {
    id("emberlight.android.library")
}

dependencies {
    // Schreibseite der Übernahme in die Galerie (Paket 3.7, ADR 0022). Die Domäne liegt in share:space. Dieses Modul
    // kennt nur den Medienspeicher und hängt von keiner Galerie-Oberfläche und keinem Galerie-Modul ab.
    implementation(project(":share:space"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // Die Datenbank des Space liegt in den Tests auf der Platte (Ende-zu-Ende-Prüfung).
    testImplementation(libs.androidx.room.runtime)
}
