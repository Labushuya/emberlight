plugins {
    id("emberlight.android.library")
    id("emberlight.server.isolation")
}

android {
    defaultConfig {
        // Keep-Regeln, die mit dem Server in jede App wandern (R8, ADR 0012). Nur die nötigen dontwarn-Zeilen.
        consumerProguardFiles("consumer-rules.pro")
    }
}

dependencies {
    // Eingebetteter Server (ADR 0012). Nur CIO, keine weiteren Engines. partial-content für Range-Antworten.
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.partial.content)
    // Nur die API. Ohne Logging-Backend fällt SLF4J auf seinen No-op-Logger zurück. Eigene Ausgaben laufen über android.util.Log.
    implementation(libs.slf4j.api)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.core.ktx)

    testImplementation(libs.ktor.server.test.host)
}
