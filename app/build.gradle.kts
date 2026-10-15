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
    testOptions.unitTests.isIncludeAndroidResources = true
}

dependencies {
    implementation(project(":core:design"))
    implementation(project(":feature:gallery"))
    implementation(project(":feature:space"))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.compose.material3.adaptive.navigation.suite)

    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.hilt.android.testing)
    kspTest(libs.hilt.compiler)
}
