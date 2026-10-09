import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    kotlin("jvm")
    id("org.jlleitschuh.gradle.ktlint")
}

dependencies {
    compileOnly(project(":server"))
    compileOnly("net.minestom:minestom:2026.10.05-26.2")
    add("moduleImplementation", "net.aechronis:grim-minestom:2.3.74-minestom.4")
}

tasks.named<ShadowJar>("shadowJar") {
    // These classes are supplied by core so native players survive engine replacement.
    exclude("ac/grim/grimac/minestom/**")
}
