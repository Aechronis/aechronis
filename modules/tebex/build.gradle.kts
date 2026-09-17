plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("org.jlleitschuh.gradle.ktlint")
}

dependencies {
    compileOnly(project(":server"))
    compileOnly("net.minestom:minestom:2026.09.12-26.2")
    compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
}
