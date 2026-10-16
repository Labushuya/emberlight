plugins {
    id("emberlight.android.application")
    id("emberlight.android.application.compose")
    id("emberlight.hilt")
}

android {
    androidResources {
        // Per-App-Sprache (Android 13+): erzeugt die Locale-Konfiguration aus den values-Ordnern.
        generateLocaleConfig = true
    }
}

dependencies {
    implementation(project(":core:design"))
    implementation(project(":feature:gallery"))
    implementation(project(":feature:space"))
    implementation(project(":core:media"))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.coil.core)
    implementation(libs.coil.video)
    implementation(libs.androidx.compose.material3.adaptive.navigation.suite)

    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.hilt.android.testing)
    testImplementation(libs.coil.test)
    kspTest(libs.hilt.compiler)
}
