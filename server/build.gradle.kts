group = "net.aechronis"

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.20"
    id("org.jlleitschuh.gradle.ktlint") version "14.0.1"
    id("com.gradleup.shadow") version "9.2.2"
}

tasks.withType<Jar> {
    manifest {
        attributes["Main-Class"] = "net.aechronis.aechronis.MainKt"
    }
}

java.toolchain.languageVersion = JavaLanguageVersion.of(25)

repositories {
    mavenCentral()
    maven("https://repo.hypera.dev/snapshots/") // luckperms (minestom) & Spark
    maven {
        url = uri("https://maven.pkg.github.com/Aechronis/aechronis")
        credentials {
            username = project.findProperty("gpr.user") as String? ?: System.getenv("GITHUB_ACTOR")
            password = project.findProperty("gpr.token") as String? ?: System.getenv("GITHUB_TOKEN")
        }
    }
}

dependencies {
    implementation("net.minestom:minestom:2026.01.08-1.21.11")
    implementation("net.aechronis:nodes:90fddf7")
    implementation("net.aechronis:combat:8e47ed9")
    implementation("dev.lu15:luckperms-minestom:5.5-SNAPSHOT")
    implementation("com.h2database:h2:2.4.240") // needed for luckperms
    implementation("org.slf4j:slf4j-simple:2.0.17")
}
