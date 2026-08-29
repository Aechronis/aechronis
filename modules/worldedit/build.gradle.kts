plugins {
    kotlin("jvm")
    id("org.jlleitschuh.gradle.ktlint")
}

dependencies {
    compileOnly(project(":server"))
    compileOnly(project(":modules:utils"))
    compileOnly("net.minestom:minestom:2026.07.12-26.2")
    add("moduleApi", "com.sk89q.worldedit:worldedit-core:7.4.4") {
        exclude(group = "com.google.code.gson", module = "gson")
        exclude(group = "com.google.guava", module = "guava")
        exclude(group = "it.unimi.dsi", module = "fastutil")
    }
    compileOnly("com.google.guava:guava:33.6.0-jre")
    compileOnly("it.unimi.dsi:fastutil:8.5.18")

    testImplementation("org.junit.jupiter:junit-jupiter:5.12.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
