plugins {
    id("emberlight.android.library")
    id("emberlight.space.isolation")
    id("emberlight.room")
}

dependencies {
    // Domäne und Speicher des Space (ADR 0014). Ohne Ktor, ohne Oberfläche, ohne Galerie.
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
