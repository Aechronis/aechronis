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
        maven("https://jitpack.io")
        maven("https://maven.conceptmc.com/releases")
        maven("https://repo.hypera.dev/snapshots/")
        maven("https://repo.lucko.me/")
        maven("https://repo.smolder.fr/public/")
        maven("https://mvn.everbuild.org/public")
        maven("https://maven.enginehub.org/repo/")
        maven("https://maven.pkg.github.com/Error11O/MinecraftPlugin") {
            credentials {
                username =
                    providers.gradleProperty("gpr.user")
                        .orElse(providers.environmentVariable("GITHUB_ACTOR"))
                        .orNull
                password =
                    providers.gradleProperty("gpr.token")
                        .orElse(providers.environmentVariable("GITHUB_TOKEN"))
                        .orNull
            }
        }
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
    ":modules:watchdog",
    ":modules:gems",
    ":modules:misc",
    ":modules:guard",
    ":modules:iterations:template",
)
