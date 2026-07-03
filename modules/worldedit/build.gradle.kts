plugins {
    idea
    java
    `java-library`
    `maven-publish`
    kotlin("jvm") version "2.3.20"
    id("org.jlleitschuh.gradle.ktlint") version "14.0.1"
}

group = "io.github.openminigameserver"
version = "2.0.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://maven.enginehub.org/repo/")
}

dependencies {
    compileOnly("net.minestom:minestom:2026.03.25-1.21.11")
    testImplementation("net.minestom:minestom:2026.03.25-1.21.11")
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    api("com.sk89q.worldedit:worldedit-core:7.4.3")
    api("com.google.guava:guava:33.5.0-jre")
    api("com.google.code.gson:gson:2.13.2")
    api("it.unimi.dsi:fastutil:8.5.18")
    compileOnly(kotlin("stdlib"))
}

kotlin {
    jvmToolchain(25)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
    withSourcesJar()
}

tasks {
    test {
        useJUnitPlatform()
        systemProperty("keepRunning", System.getProperty("keepRunning", "false"))
        maxHeapSize = "4g"
    }

    val templateContext = mapOf("version" to project.version.toString())
    processResources {
        expand(*templateContext.toList().toTypedArray())
    }

    register<Copy>("generateKotlinBuildInfo") {
        inputs.properties(templateContext)
        from("src/template/kotlin/")
        into(layout.buildDirectory.dir("generated/kotlin/"))
        expand(*templateContext.toList().toTypedArray())
    }

    kotlin.sourceSets["main"].kotlin.srcDir(layout.buildDirectory.dir("generated/kotlin"))
    compileKotlin.get().dependsOn(get("generateKotlinBuildInfo"))
    named("sourcesJar").get().dependsOn(get("generateKotlinBuildInfo"))
    withType<org.jlleitschuh.gradle.ktlint.tasks.BaseKtLintCheckTask>().configureEach {
        dependsOn(get("generateKotlinBuildInfo"))
    }
}

publishing {
    publications {
        create<MavenPublication>("gpr") {
            from(components["java"])
            groupId = "io.github.openminigameserver"
            artifactId = "MinestomWorldEdit"
        }
    }
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/OpenMinigameServer/MinestomWorldEdit")
            credentials {
                username = System.getenv("GITHUB_ACTOR") ?: findProperty("gpr.user") as String?
                password = System.getenv("GITHUB_TOKEN") ?: findProperty("gpr.key") as String?
            }
        }
    }
}
