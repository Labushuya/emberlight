package io.github.labushuya.emberlight.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.register

class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.pluginManager.apply("com.android.application")

        val appVersion = EmberlightVersion.resolve(target.providers.gradleProperty(EmberlightVersion.PROPERTY).orNull)
        val signingValues = ReleaseSigning.read(target.providers)
        val missingSigning = ReleaseSigning.missing(signingValues)
        val requireSigning =
            target.providers
                .gradleProperty(ReleaseSigning.REQUIRE_PROPERTY)
                .orNull
                .toBoolean()
        val signingReady = missingSigning.isEmpty()

        target.extensions.configure<ApplicationExtension> {
            namespace = EmberlightConfig.APPLICATION_ID
            configureSdkLevels()
            compileOptions { useJava17() }
            lint { applyEmberlightDefaults(checkDependencies = true) }

            defaultConfig.applicationId = EmberlightConfig.APPLICATION_ID
            defaultConfig.targetSdk { version = release(EmberlightConfig.TARGET_SDK) }
            defaultConfig.versionCode = appVersion.code
            defaultConfig.versionName = appVersion.name

            if (signingReady) {
                val keystore = target.file(signingValues.getValue(ReleaseSigning.KEYSTORE_FILE))
                if (!keystore.isFile) {
                    throw GradleException(
                        "Die Keystore-Datei aus ${ReleaseSigning.KEYSTORE_FILE} existiert nicht oder ist keine Datei.",
                    )
                }
                val release = signingConfigs.create("release")
                release.storeFile = keystore
                release.storePassword = signingValues.getValue(ReleaseSigning.KEYSTORE_PASSWORD)
                release.keyAlias = signingValues.getValue(ReleaseSigning.KEY_ALIAS)
                release.keyPassword = signingValues.getValue(ReleaseSigning.KEY_PASSWORD)
            }

            val releaseType = buildTypes.getByName("release")
            if (signingReady) {
                releaseType.signingConfig = signingConfigs.getByName("release")
            }
            releaseType.isMinifyEnabled = true
            releaseType.isShrinkResources = true
            releaseType.proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }

        if (!signingReady && missingSigning.size < ReleaseSigning.VARIABLES.size) {
            target.logger.warn(
                "Release-Build wird unsigniert erzeugt, folgende Signing-Variablen fehlen: " +
                    missingSigning.joinToString(", "),
            )
        }
        if (requireSigning && !signingReady) {
            val appPath = target.path
            target.gradle.taskGraph.whenReady { graph ->
                val releaseRequested =
                    graph.allTasks.any { task ->
                        task.project.path == appPath && task.name.contains("Release")
                    }
                if (releaseRequested) {
                    throw GradleException(
                        "Release-Build verlangt Signierung (${ReleaseSigning.REQUIRE_PROPERTY}=true), " +
                            "aber folgende Umgebungsvariablen fehlen: ${missingSigning.joinToString(", ")}",
                    )
                }
            }
        }

        target.configureKotlinAndroid()
        target.configureKover()
        target.configureUnitTests()
        target.configureUnitTestDependencies()

        val checkNoPlayServices =
            target.tasks.register<CheckNoPlayServicesTask>("checkNoPlayServices") {
                group = "verification"
                description = "Bricht ab, wenn der Release-Classpath Google-Play-Services-Abhängigkeiten enthält."
                coordinates.set(
                    target.configurations.named("releaseRuntimeClasspath").map { configuration ->
                        configuration.incoming.resolutionResult.allComponents
                            .mapNotNull { it.moduleVersion }
                            .map { "${it.group}:${it.name}:${it.version}" }
                            .distinct()
                            .sorted()
                    },
                )
                outputs.upToDateWhen { false }
            }
        target.tasks.named("check") { task ->
            task.dependsOn(checkNoPlayServices)
        }
    }
}
