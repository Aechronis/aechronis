version = "0.0.14"

val outputJarName = "nodes"

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.3.0-RC"
    id("org.jlleitschuh.gradle.ktlint") version "14.0.1"
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib")

    compileOnly("com.google.code.gson:gson:2.13.2")

    implementation("org.spigotmc:spigot-api:1.21.11-R0.1-SNAPSHOT")

    implementation("net.minestom:minestom:2025.12.20c-1.21.11")
}
