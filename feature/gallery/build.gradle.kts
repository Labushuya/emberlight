plugins {
    id("emberlight.android.feature")
}

dependencies {
    // Vorschaubilder (ADR 0008). Coil-Compose für AsyncImage, keine Netzwerk-Module.
    implementation(libs.coil.core)
    implementation(libs.coil.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.compose.material3)
    implementation(project(":core:media"))

    testImplementation(libs.coil.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
