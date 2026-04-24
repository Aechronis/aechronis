group = "net.aechronis"

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.21"
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
    id("com.gradleup.shadow") version "9.4.1"
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
    maven("https://repo.smolder.fr/public/") // axiom minestom component
    maven {
        url = uri("https://maven.pkg.github.com/Aechronis/aechronis")
        credentials {
            username = project.findProperty("gpr.user") as String? ?: System.getenv("GITHUB_ACTOR")
            password = project.findProperty("gpr.token") as String? ?: System.getenv("GITHUB_TOKEN")
        }
    }
}

dependencies {
    implementation("net.minestom:minestom:2026.04.13-1.21.11")
    implementation("net.aechronis:nodes:+")
    implementation("net.aechronis:combat:+")
    implementation("dev.lu15:luckperms-minestom:5.5-SNAPSHOT")
    implementation("com.h2database:h2:2.4.240") // needed for luckperms
    implementation("org.slf4j:slf4j-simple:2.0.17")
    implementation("fr.ghostrider584:axiom-minestom:0.0.4")
}
