plugins {
    id("emberlight.android.feature")
}

dependencies {
    // Spaces (Paket 3.2): Domäne, Persistenz und Titelbild-Pipeline liegen in share:space. Keine Galerie-Module.
    implementation(project(":share:space"))
    // Der Hilt-Einstieg in di/ nennt die Datenbankklasse von Room, daher braucht das Modul die Laufzeit selbst.
    implementation(libs.androidx.room.runtime)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.compose.material3)
    // Titelbilder (Paket 3.2): Coil lädt die Datei aus dem Space-Speicher, verkleinert und mit EXIF-Ausrichtung.
    // Keine Netzwerk-Module, keine Galerie-Medien.
    implementation(libs.coil.core)
    implementation(libs.coil.compose)

    testImplementation(libs.kotlinx.coroutines.test)
}
