plugins {
    kotlin("jvm")
    id("org.jlleitschuh.gradle.ktlint")
}

dependencies {
    compileOnly(project(":server"))
    compileOnly(project(":modules:vanilla"))
    compileOnly("net.minestom:minestom:2026.09.12-26.2")
}
