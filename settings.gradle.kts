pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

// Plugin-Versionen müssen hier als Literal stehen: der Version Catalog ist in settings.gradle.kts noch nicht verfügbar.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "emberlight"

include(":app")
include(":core:design")
include(":core:data")
include(":core:media")
include(":feature:gallery")
include(":feature:space")
include(":share:space")
include(":share:server")
include(":share:network")
include(":share:export")
// Messwerkzeug für Meilenstein 4 (Netlab). Eigene App-ID, keine Abhängigkeit zur Haupt-App. Siehe docs/KONZEPT-M4.md Abschnitt 13.
include(":tools:netlab")
