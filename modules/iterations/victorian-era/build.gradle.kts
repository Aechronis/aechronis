plugins {
    kotlin("jvm")
    id("org.jlleitschuh.gradle.ktlint")
}

base {
    archivesName.set("victorian-era")
}

dependencies {
    compileOnly(project(":server"))
    compileOnly(project(":modules:utils"))
    compileOnly(project(":modules:combat"))
    compileOnly(project(":modules:vanilla"))
    compileOnly(project(":modules:worldedit"))
    compileOnly(project(":modules:nodes"))
    compileOnly(project(":modules:logger"))
    compileOnly(project(":modules:guard"))
    compileOnly(project(":modules:gems"))
    compileOnly("net.minestom:minestom:2026.10.05-26.2")
}
