package io.github.labushuya.emberlight.buildlogic

import com.android.build.api.dsl.CommonExtension
import com.android.build.api.dsl.CompileOptions
import com.android.build.api.dsl.Lint
import io.github.takahirom.roborazzi.RoborazziExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

/** Lint-Hinweise zu Versionen. Updates kommen über Dependabot, nicht über CI-Rot. */
private val LINT_VERSION_CHECKS =
    setOf(
        "GradleDependency",
        "NewerVersionAvailable",
        "AndroidGradlePluginVersion",
    )

internal val Project.catalog: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun VersionCatalog.plugin(alias: String) =
    findPlugin(alias).orElseThrow { IllegalStateException("Katalog-Plugin fehlt: $alias") }

internal fun VersionCatalog.library(alias: String) =
    findLibrary(alias).orElseThrow { IllegalStateException("Katalog-Eintrag fehlt: $alias") }

/** Compile-SDK und minSdk. Gilt für Application- und Library-Module gleichermaßen. */
internal fun CommonExtension.configureSdkLevels() {
    compileSdk {
        version = release(EmberlightConfig.COMPILE_SDK)
    }
    defaultConfig.minSdk = EmberlightConfig.MIN_SDK
}

/** Java 17 für Quelltext und Bytecode. */
internal fun CompileOptions.useJava17() {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

/** Lint: Fehler bei Warnungen, Versions-Hinweise deaktiviert, keine Baseline. */
internal fun Lint.applyEmberlightDefaults(checkDependencies: Boolean) {
    abortOnError = true
    warningsAsErrors = true
    this.checkDependencies = checkDependencies
    disable.addAll(LINT_VERSION_CHECKS)
}

internal fun Project.configureUnitTestDependencies() {
    dependencies.add("testImplementation", catalog.library("junit"))
}

/** Kotlin-Compiler: Warnungen sind Fehler, Target ist JVM 17. Funktioniert mit dem integrierten Kotlin von AGP 9. */
internal fun Project.configureKotlinAndroid() {
    tasks.withType<KotlinCompile>().configureEach { task ->
        task.compilerOptions.allWarningsAsErrors.set(true)
        task.compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
    }
}

/** Compose-Compiler-Plugin, Compose-BOM, Compose-Basis und Tooling. Tooling und Test-Manifest nur im Debug-Build. */
internal fun Project.configureCompose(extension: CommonExtension) {
    pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
    extension.buildFeatures.compose = true
    dependencies.add("implementation", dependencies.platform(catalog.library("androidx-compose-bom")))
    // Der Compose-Compiler verlangt die Runtime auf dem Classpath, die Version kommt aus der BOM.
    dependencies.add("implementation", catalog.library("androidx-compose-runtime"))
    dependencies.add("implementation", catalog.library("androidx-compose-ui"))
    dependencies.add("implementation", catalog.library("androidx-compose-ui-tooling-preview"))
    dependencies.add("debugImplementation", catalog.library("androidx-compose-ui-tooling"))
    dependencies.add("debugImplementation", catalog.library("androidx-compose-ui-test-manifest"))
    dependencies.add("testImplementation", catalog.library("androidx-compose-ui-test-junit4"))
    // Compose synchronisiert unter Robolectric über Espresso. Die von ui-test gezogene Version 3.5 scheitert mit SDK 37
    // (InputManager.getInstance fehlt), daher 3.7.0.
    dependencies.add("testImplementation", catalog.library("androidx-test-espresso-core"))
    configureScreenshotTests(extension)
}

/**
 * Screenshot-Tests (Goldens) mit Roborazzi auf dem JVM, Robolectric mit nativer Grafik. Goldens liegen je Modul in
 * `src/test/screenshots` und werden bei jedem Testlauf verifiziert, nicht still neu aufgenommen (siehe ADR 0006).
 */
private fun Project.configureScreenshotTests(extension: CommonExtension) {
    pluginManager.apply(catalog.plugin("roborazzi").get().pluginId)
    extension.testOptions.unitTests.isIncludeAndroidResources = true
    dependencies.add("testImplementation", catalog.library("robolectric"))
    dependencies.add("testImplementation", catalog.library("androidx-test-ext-junit"))
    dependencies.add("testImplementation", catalog.library("roborazzi"))
    dependencies.add("testImplementation", catalog.library("roborazzi-compose"))
    dependencies.add("testImplementation", catalog.library("roborazzi-junit-rule"))
    extensions.configure<RoborazziExtension> {
        outputDir.set(layout.projectDirectory.dir("src/test/screenshots"))
    }
}

/**
 * Kover pro Android-Modul: misst die Debug-Unit-Tests. Aggregation, Ausschlüsse und die Untergrenze
 * stehen im Root-Projekt (siehe ADR 0006).
 */
internal fun Project.configureKover() {
    pluginManager.apply(catalog.plugin("kover").get().pluginId)
}

private val JDK_INTERNAL_OPENS = listOf("--add-opens", "java.base/jdk.internal.access=ALL-UNNAMED")

/**
 * Unit-Tests: JDK 21 als Launcher für alle Test-Tasks. Der Build selbst (Compiler, Lint) bleibt auf JDK 17.
 */
internal fun Project.configureUnitTests() {
    val toolchains = extensions.getByType<JavaToolchainService>()
    val testLauncher =
        toolchains.launcherFor { spec ->
            spec.languageVersion.set(JavaLanguageVersion.of(EmberlightConfig.TEST_JAVA_VERSION))
        }
    tasks.withType<Test>().configureEach { task ->
        task.javaLauncher.set(testLauncher)
        // Robolectric greift unter JDK 17+ auf interne Klassen zu, die Module-System-Öffnung ist dafür nötig.
        task.jvmArgs(JDK_INTERNAL_OPENS)
    }
}
