plugins {
    kotlin("jvm")
    id("org.jlleitschuh.gradle.ktlint")
}

dependencies {
    compileOnly(project(":server"))
    compileOnly(project(":modules:utils"))
    compileOnly(project(":modules:combat"))
    compileOnly("net.minestom:minestom:2026.08.16-26.2")
    compileOnly(project(":modules:vanilla"))
    compileOnly(project(":modules:worldedit"))

    compileOnly("com.h2database:h2:2.4.240")
    compileOnly("com.zaxxer:HikariCP:7.1.0")
}
