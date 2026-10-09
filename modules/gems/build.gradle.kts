plugins {
    kotlin("jvm")
    id("org.jlleitschuh.gradle.ktlint")
}

dependencies {
    compileOnly(project(":server"))
    compileOnly("net.minestom:minestom:2026.10.05-26.2")
    compileOnly("com.h2database:h2:2.5.252")
    compileOnly(project(":modules:nodes"))
    compileOnly(project(":modules:utils"))
    compileOnly(project(":modules:vanilla"))
}
