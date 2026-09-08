plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("org.jlleitschuh.gradle.ktlint")
}

dependencies {
    compileOnly(project(":server"))
    compileOnly("net.minestom:minestom:2026.08.28-26.2")
    compileOnly(project(":modules:utils"))
    compileOnly(project(":modules:worldedit"))
    compileOnly(project(":modules:combat"))
    compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
}
