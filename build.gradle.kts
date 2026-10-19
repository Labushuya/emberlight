import kotlinx.kover.gradle.plugin.dsl.CoverageUnit
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType

plugins {
    base
    alias(libs.plugins.spotless)
    alias(libs.plugins.kover)
}

val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

spotless {
    // Repo speichert LF, unabhängig von core.autocrlf auf Windows.
    lineEndings = com.diffplug.spotless.LineEnding.UNIX

    val ktlintVersion = catalog.findVersion("ktlint").get().requiredVersion
    val composeRulesVersion = catalog.findVersion("ktlint-compose-rules").get().requiredVersion
    val composeRules = "io.nlopez.compose.rules:ktlint:$composeRulesVersion"

    // Feste Muster statt "**": Spotless würde sonst build/-Ordner durchlaufen, während Gradle sie schreibt.
    kotlin {
        target("**/src/**/*.kt")
        ktlint(ktlintVersion).customRuleSets(listOf(composeRules))
        trimTrailingWhitespace()
        endWithNewline()
    }

    kotlinGradle {
        target("*.gradle.kts", "*/*.gradle.kts", "*/*/*.gradle.kts")
        ktlint(ktlintVersion).customRuleSets(listOf(composeRules))
        trimTrailingWhitespace()
        endWithNewline()
    }

    format("misc") {
        target(".gitignore", ".gitattributes", ".editorconfig")
        trimTrailingWhitespace()
        endWithNewline()
    }
}

// Coverage: alle Android-Module fließen ein. Neue Android-Module hier ergänzen, sonst fehlen sie in der Messung.
dependencies {
    kover(project(":app"))
    kover(project(":core:design"))
    kover(project(":core:data"))
    kover(project(":core:media"))
    kover(project(":feature:gallery"))
    kover(project(":feature:space"))
    kover(project(":share:space"))
    kover(project(":share:server"))
    kover(project(":share:network"))
}

kover {
    reports {
        // Nur generierter Code. Fach- und UI-Klassen bleiben in der Messung (siehe ADR 0006).
        filters {
            excludes {
                classes(
                    "*_HiltModules*",
                    "*.Hilt_*",
                    "*_Factory",
                    "*_Factory\$*",
                    "*_MembersInjector",
                    "*_MembersInjector\$*",
                    "*_GeneratedInjector",
                    "dagger.hilt.internal.*",
                    "hilt_aggregated_deps.*",
                    "*ComposableSingletons*",
                    "*.BuildConfig",
                    "*.BuildConfig\$*",
                    "*.R",
                    "*.R\$*",
                    "*\$\$serializer",
                )
            }
        }
        verify {
            rule("Zeilenabdeckung mindestens 70 Prozent") {
                minBound(70, coverageUnits = CoverageUnit.LINE)
            }
        }
    }
}

// Tests der Convention-Plugins laufen aus dem Root heraus mit: ./gradlew check
// Die Coverage-Untergrenze gehört ebenfalls zu check, sonst ist sie nur ein Hinweis.
tasks.named("check") {
    dependsOn(gradle.includedBuild("build-logic").task(":convention:test"))
    dependsOn("koverVerify")
}
