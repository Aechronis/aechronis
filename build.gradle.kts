import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.bundling.Zip
import org.gradle.api.tasks.Sync
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
        if (path.startsWith(":modules:") && path != ":modules:misc") {
            pluginManager.apply("com.gradleup.shadow")

            val moduleApi = configurations.dependencyScope("moduleApi")
            val moduleImplementation = configurations.dependencyScope("moduleImplementation")
            configurations.named("api") {
                extendsFrom(moduleApi.get())
            }
            configurations.named("implementation") {
                extendsFrom(moduleImplementation.get())
            }
            val compileOnly = configurations.named("compileOnly")
            configurations.named("testImplementation") {
                extendsFrom(compileOnly.get())
            }
            val runtimeClasspath = configurations.named("runtimeClasspath")
            val moduleLibrariesClasspath =
                configurations.resolvable("moduleLibrariesClasspath") {
                    extendsFrom(moduleApi.get(), moduleImplementation.get())
                    attributes.addAllLater(runtimeClasspath.get().attributes)
                }

            tasks.named<Jar>("jar") {
                archiveClassifier.set("plain")
                destinationDirectory.set(layout.buildDirectory.dir("plain-libs"))
            }
            val moduleProvider = "META-INF/services/net.aechronis.server.modules.AechronisModule"
            tasks.named<ShadowJar>("shadowJar") {
                archiveClassifier.set("")
                configurations.set(listOf(moduleLibrariesClasspath.get()))
                eachFile {
                    duplicatesStrategy =
                        if (
                            path.endsWith(".kotlin_module") ||
                            (
                                path.startsWith("META-INF/services/") &&
                                    path != moduleProvider
                            )
                        ) {
                            DuplicatesStrategy.INCLUDE
                        } else {
                            DuplicatesStrategy.EXCLUDE
                        }
                }
                mergeServiceFiles {
                    exclude(moduleProvider)
                }
                exclude("META-INF/*.DSA", "META-INF/*.RSA", "META-INF/*.SF")
            }
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        systemProperty("keepRunning", System.getProperty("keepRunning", "false"))
        systemProperty(
            "aechronis.dangerously-enable-all-permissions",
            System.getProperty("aechronis.dangerously-enable-all-permissions", "false"),
        )
    }

}

val runtimeModuleProjects =
    listOf(
        ":modules:utils",
        ":modules:watchdog",
        ":modules:combat",
        ":modules:vanilla",
        ":modules:worldedit",
        ":modules:nodes",
        ":modules:logger",
        ":modules:guard",
        ":modules:gems",
        ":modules:iterations:a-new-millenium",
    )

val assembleServerDistribution =
    tasks.register<Sync>("assembleServerDistribution") {
        group = "distribution"
        description = "Assembles a runnable core JAR and direct modules/*.jar layout."
        dependsOn(":server:shadowJar")
        dependsOn(runtimeModuleProjects.map { "$it:shadowJar" })

        into(layout.buildDirectory.dir("distributions/aechronis"))
        from(project(":server").tasks.withType<ShadowJar>())
        into("modules") {
            runtimeModuleProjects.forEach { modulePath ->
                from(project(modulePath).tasks.withType<ShadowJar>())
            }
            from("server/src/main/distribution/modules/.required-modules")
        }
    }

tasks.register<Zip>("serverDistributionZip") {
    group = "distribution"
    description = "Packages the runnable server distribution."
    dependsOn(assembleServerDistribution)
    archiveFileName.set("aechronis-server.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    from(layout.buildDirectory.dir("distributions/aechronis")) {
        into("aechronis")
    }
}
