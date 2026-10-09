plugins {
    kotlin("jvm")
    id("org.jlleitschuh.gradle.ktlint")
}

dependencies {
    compileOnly(project(":server"))
    compileOnly(project(":modules:combat"))
    compileOnly("net.minestom:minestom:2026.10.05-26.2")
}
