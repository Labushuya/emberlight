plugins {
    `java-gradle-plugin`
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    implementation(gradleKotlinDsl())
    implementation(libs.android.gradle.plugin)
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.kotlin.compose.gradle.plugin)
    implementation(libs.kotlin.serialization.gradle.plugin)
    implementation(libs.ksp.gradle.plugin)
    implementation(libs.hilt.gradle.plugin)
    implementation(libs.kover.gradle.plugin)
    implementation(libs.roborazzi.gradle.plugin)
    implementation(libs.aboutlibraries.gradle.plugin)

    testImplementation(libs.kotlin.test.junit)
    // Negativtests der Build-Sperre laufen gegen ein Fixture-Projekt (ServerIsolationPluginTest).
    testImplementation(gradleTestKit())
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "emberlight.android.application"
            implementationClass = "io.github.labushuya.emberlight.buildlogic.AndroidApplicationConventionPlugin"
        }
        register("androidApplicationCompose") {
            id = "emberlight.android.application.compose"
            implementationClass = "io.github.labushuya.emberlight.buildlogic.AndroidApplicationComposeConventionPlugin"
        }
        register("androidLibrary") {
            id = "emberlight.android.library"
            implementationClass = "io.github.labushuya.emberlight.buildlogic.AndroidLibraryConventionPlugin"
        }
        register("androidLibraryCompose") {
            id = "emberlight.android.library.compose"
            implementationClass = "io.github.labushuya.emberlight.buildlogic.AndroidLibraryComposeConventionPlugin"
        }
        register("androidFeature") {
            id = "emberlight.android.feature"
            implementationClass = "io.github.labushuya.emberlight.buildlogic.AndroidFeatureConventionPlugin"
        }
        register("hilt") {
            id = "emberlight.hilt"
            implementationClass = "io.github.labushuya.emberlight.buildlogic.HiltConventionPlugin"
        }
        register("serverIsolation") {
            id = "emberlight.server.isolation"
            implementationClass = "io.github.labushuya.emberlight.buildlogic.ServerIsolationConventionPlugin"
        }
        register("spaceIsolation") {
            id = "emberlight.space.isolation"
            implementationClass = "io.github.labushuya.emberlight.buildlogic.SpaceIsolationConventionPlugin"
        }
        register("galleryIsolation") {
            id = "emberlight.gallery.isolation"
            implementationClass = "io.github.labushuya.emberlight.buildlogic.GalleryIsolationConventionPlugin"
        }
        register("room") {
            id = "emberlight.room"
            implementationClass = "io.github.labushuya.emberlight.buildlogic.RoomConventionPlugin"
        }
        register("serialization") {
            id = "emberlight.serialization"
            implementationClass = "io.github.labushuya.emberlight.buildlogic.SerializationConventionPlugin"
        }
        register("releaseManifest") {
            id = "emberlight.release.manifest"
            implementationClass = "io.github.labushuya.emberlight.buildlogic.ReleaseManifestConventionPlugin"
        }
        register("aboutLibraries") {
            id = "emberlight.about.libraries"
            implementationClass = "io.github.labushuya.emberlight.buildlogic.AboutLibrariesConventionPlugin"
        }
    }
}
