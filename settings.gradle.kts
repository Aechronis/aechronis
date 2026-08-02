pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        maven("https://maven.conceptmc.com/releases")
        maven("https://repo.hypera.dev/snapshots/")
        maven("https://repo.lucko.me/")
        maven("https://repo.smolder.fr/public/")
        maven("https://mvn.everbuild.org/public")
        maven("https://maven.enginehub.org/repo/")
    }
}

rootProject.name = "aechronis"

include(
    ":server",
    ":modules:utils",
    ":modules:combat",
    ":modules:nodes",
    ":modules:vanilla",
    ":modules:worldedit",
    ":modules:logger",
)
