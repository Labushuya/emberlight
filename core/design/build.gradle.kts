plugins {
    id("emberlight.android.library")
    id("emberlight.android.library.compose")
}

dependencies {
    // Theme und Komponenten gehören zur öffentlichen API von core:design, Consumer brauchen BOM und Material 3.
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.material3)
}
