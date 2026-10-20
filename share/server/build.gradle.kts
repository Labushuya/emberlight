plugins {
    id("emberlight.android.library")
    id("emberlight.server.isolation")
    id("emberlight.serialization")
}

android {
    defaultConfig {
        // Keep-Regeln, die mit dem Server in jede App wandern (R8, ADR 0012). Nur die nötigen dontwarn-Zeilen.
        consumerProguardFiles("consumer-rules.pro")
    }
}

dependencies {
    // Space-Domäne und Speicher (ADR 0014). Der Server ruft nur deren öffentliche Typen auf.
    implementation(project(":share:space"))
    // Eingebetteter Server (ADR 0012, ADR 0013). Nur CIO, keine weiteren Engines. partial-content für Range-Antworten.
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.partial.content)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.core.ktx)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    // Schließt die Datenbank in den Tests, die den Index selbst öffnen (Paket 3.3).
    testImplementation(libs.androidx.room.runtime)
}
