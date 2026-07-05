group = "net.aechronis"
version = System.getenv("GITHUB_SHA")?.take(7) ?: "local"

plugins {
    `maven-publish`
    id("org.jetbrains.kotlin.jvm") version "2.3.20"
    id("org.jlleitschuh.gradle.ktlint") version "14.0.1"
}

java.toolchain.languageVersion = JavaLanguageVersion.of(25)

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

tasks.test {
    useJUnitPlatform()
    systemProperty("keepRunning", System.getProperty("keepRunning", "false"))
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/Aechronis/worldedit")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
    }
}
