import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.Sync
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService

plugins {
    id("jvm-toolchains")
    kotlin("jvm") version "2.4.10" apply false
    kotlin("plugin.serialization") version "2.4.20" apply false
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
                from(layout.projectDirectory.dir("resource-pack")) {
                    into("embedded-resource-pack")
                }
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

val prepareDevRun = tasks.register<Sync>("prepareDevRun") {
    group = "application"
    description = "Builds and installs the complete server in run/, preserving runtime data."
    from(tasks.named("assembleServerDistribution"))
    into(layout.projectDirectory.dir("run"))
    // Gradle ignores extra files in output directories when checking whether a task is up-to-date.
    // Always sync so manually installed or older versioned module JARs cannot survive a dev launch.
    outputs.upToDateWhen { false }
    preserve {
        include("**/*")
        exclude("aechronis.jar", "modules/*.jar", "modules/.required-modules")
    }
}

tasks.register<Sync>("assembleDevModules") {
    group = "application"
    description = "Stages runtime module JARs for devRun's automatic reload."
    into(layout.buildDirectory.dir("dev-watch/modules"))
    runtimeModuleProjects.forEach { modulePath ->
        from(project(modulePath).tasks.withType<ShadowJar>())
    }
}

val devJavaLauncher = extensions.getByType<JavaToolchainService>().launcherFor {
    languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.register<JavaExec>("devRun") {
    group = "application"
    description = "Starts the server in run/ and automatically rebuilds and reloads changed runtime modules."
    dependsOn(prepareDevRun)
    javaLauncher.set(devJavaLauncher)
    workingDir(layout.projectDirectory.dir("run"))
    classpath = files(layout.projectDirectory.file("run/aechronis.jar"))
    mainClass.set("net.aechronis.server.ServerKt")
    systemProperty("aechronis.dangerously-enable-all-permissions", "true")
    systemProperty("aechronis.dev.projectRoot", layout.projectDirectory.asFile.absolutePath)
    systemProperty("aechronis.dev.modulePaths", runtimeModuleProjects.joinToString(",") { it.removePrefix(":").replace(':', '/') })
    systemProperty("aechronis.dev.buildFiles", allprojects.joinToString(",") { relativePath(it.buildFile) })
    standardInput = System.`in`
}
