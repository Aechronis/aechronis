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
    maven {
        url = uri("https://maven.pkg.github.com/Aechronis/aechronis")
        credentials {
            username = project.findProperty("gpr.user") as String? ?: System.getenv("GITHUB_ACTOR")
            password = project.findProperty("gpr.token") as String? ?: System.getenv("GITHUB_TOKEN")
        }
    }
}

dependencies {
    api("net.aechronis:utils:86a747b")
    api("net.aechronis:logger:4687529")
    api("net.minestom:minestom:2026.07.12-26.2")
    api("com.sk89q.worldedit:worldedit-core:7.4.3") {
        exclude(group = "com.google.code.gson", module = "gson")
    }
    api("com.google.guava:guava:33.5.0-jre")
    api("it.unimi.dsi:fastutil:8.5.18")
    compileOnly(kotlin("stdlib"))

    testImplementation("net.aechronis:logger:4687529")
    testImplementation("net.minestom:minestom:2026.07.12-26.2")
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    systemProperty("keepRunning", System.getProperty("keepRunning", "false"))
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "worldedit"
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
