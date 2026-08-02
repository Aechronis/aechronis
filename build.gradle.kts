import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion

plugins {
    kotlin("jvm") version "2.4.10" apply false
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0" apply false
    id("com.gradleup.shadow") version "9.6.1" apply false
}

allprojects {
    group = "net.aechronis"
    version =
        providers
            .environmentVariable("GITHUB_SHA")
            .map { it.take(7) }
            .orElse("local")
            .get()
}

subprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        extensions.configure<JavaPluginExtension> {
            toolchain.languageVersion.set(JavaLanguageVersion.of(25))
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        systemProperty("keepRunning", System.getProperty("keepRunning", "false"))
    }

    pluginManager.withPlugin("maven-publish") {
        pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
            val javaComponent = components["java"]
            val githubUser =
                providers
                    .gradleProperty("gpr.user")
                    .orElse(providers.environmentVariable("GITHUB_ACTOR"))
            val githubToken =
                providers
                    .gradleProperty("gpr.token")
                    .orElse(providers.environmentVariable("GITHUB_TOKEN"))

            extensions.configure<PublishingExtension> {
                publications {
                    create<MavenPublication>("maven") {
                        from(javaComponent)
                    }
                }
                repositories {
                    maven {
                        name = "GitHubPackages"
                        url = uri("https://maven.pkg.github.com/Aechronis/aechronis")
                        credentials {
                            username = githubUser.orNull
                            password = githubToken.orNull
                        }
                    }
                }
            }
        }
    }
}
