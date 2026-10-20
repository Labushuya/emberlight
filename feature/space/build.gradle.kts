plugins {
    id("emberlight.android.feature")
}

dependencies {
    // Spaces (Paket 3.2): Domäne, Persistenz und Titelbild-Pipeline liegen in share:space. Keine Galerie-Module.
    implementation(project(":share:space"))
    // Betrieb (Paket 3.3): Dienst, Zustand des Betriebs und WLAN-Prüfung. Der Server ruft die Oberfläche nie auf,
    // die Richtung Oberfläche → Server ist erlaubt (checkServerIsolation).
    implementation(project(":share:server"))
    // Der Hilt-Einstieg in di/ nennt die Datenbankklasse von Room, daher braucht das Modul die Laufzeit selbst.
    implementation(libs.androidx.room.runtime)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.compose.material3)
    // Titelbilder (Paket 3.2): Coil lädt die Datei aus dem Space-Speicher, verkleinert und mit EXIF-Ausrichtung.
    // Keine Netzwerk-Module, keine Galerie-Medien.
    implementation(libs.coil.core)
    implementation(libs.coil.compose)
    // QR-Code des Beitritts (Paket 3.4, ADR 0018): erzeugen und in der Prüfung zurücklesen. Nur ZXing core.
    implementation(libs.zxing.core)

    testImplementation(libs.kotlinx.coroutines.test)
}

// Screenshot-Goldens: Im gemeinsamen Test-Prozess entstehen leere Aufnahmen, die beim Verifizieren gegen sich selbst grün
// wären (ADR 0006, Befund). Je Testklasse ein eigener Prozess, wie in der Galerie (ADR 0010).
tasks.withType<Test>().configureEach {
    forkEvery = 1
}
