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

    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
