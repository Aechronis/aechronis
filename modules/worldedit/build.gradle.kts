plugins {
    kotlin("jvm")
    id("org.jlleitschuh.gradle.ktlint")
    `maven-publish`
}

dependencies {
    compileOnly(project(":modules:utils"))
    compileOnly("net.minestom:minestom:2026.07.12-26.2")
    api("com.sk89q.worldedit:worldedit-core:7.4.4") {
        exclude(group = "com.google.code.gson", module = "gson")
    }
    api("com.google.guava:guava:33.5.0-jre")
    api("it.unimi.dsi:fastutil:8.5.18")
    compileOnly(kotlin("stdlib"))

    testImplementation(project(":modules:utils"))
    testImplementation("net.minestom:minestom:2026.07.12-26.2")
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
