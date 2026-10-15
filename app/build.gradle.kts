plugins {
    id("emberlight.android.application")
    id("emberlight.android.application.compose")
    id("emberlight.hilt")
}

dependencies {
    implementation(project(":core:design"))
    implementation(project(":feature:gallery"))
    implementation(project(":feature:space"))
}
