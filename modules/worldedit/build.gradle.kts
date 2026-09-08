plugins {
    kotlin("jvm")
    id("org.jlleitschuh.gradle.ktlint")
}

dependencies {
    compileOnly(project(":server"))
    compileOnly(project(":modules:utils"))
    compileOnly("net.minestom:minestom:2026.08.28-26.2")
    add("moduleApi", "com.sk89q.worldedit:worldedit-core:7.4.5")
    compileOnly("com.google.guava:guava:33.7.1-jre")
}
