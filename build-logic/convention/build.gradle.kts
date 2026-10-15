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
    implementation(libs.ksp.gradle.plugin)
    implementation(libs.hilt.gradle.plugin)

    testImplementation(libs.kotlin.test.junit)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "emberlight.android.application"
            implementationClass = "de.labushuya.emberlight.buildlogic.AndroidApplicationConventionPlugin"
        }
        register("androidApplicationCompose") {
            id = "emberlight.android.application.compose"
            implementationClass = "de.labushuya.emberlight.buildlogic.AndroidApplicationComposeConventionPlugin"
        }
        register("androidLibrary") {
            id = "emberlight.android.library"
            implementationClass = "de.labushuya.emberlight.buildlogic.AndroidLibraryConventionPlugin"
        }
        register("androidLibraryCompose") {
            id = "emberlight.android.library.compose"
            implementationClass = "de.labushuya.emberlight.buildlogic.AndroidLibraryComposeConventionPlugin"
        }
        register("androidFeature") {
            id = "emberlight.android.feature"
            implementationClass = "de.labushuya.emberlight.buildlogic.AndroidFeatureConventionPlugin"
        }
        register("hilt") {
            id = "emberlight.hilt"
            implementationClass = "de.labushuya.emberlight.buildlogic.HiltConventionPlugin"
        }
    }
}
