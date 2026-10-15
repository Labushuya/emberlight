import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType

plugins {
    base
    alias(libs.plugins.spotless)
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

// Tests der Convention-Plugins laufen aus dem Root heraus mit: ./gradlew check
tasks.named("check") {
    dependsOn(gradle.includedBuild("build-logic").task(":convention:test"))
}
