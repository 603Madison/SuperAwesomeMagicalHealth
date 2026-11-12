pluginManagement {
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

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "SuperAwesomeMagicalFitness"

// shared: the API's models, used by the server and the Android app alike.
// server: the self-hosted server and its web app (web/, built into the jar).
// android: the phone app, which signs in to the server and carries data from wearables to it.
// It needs the Android SDK; the server's container image is built without it (SAMF_SERVER_ONLY=1).
include(":shared", ":server")
if (System.getenv("SAMF_SERVER_ONLY") == null) include(":android")
