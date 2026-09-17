plugins {
    kotlin("jvm")
    id("org.jlleitschuh.gradle.ktlint")
}

dependencies {
    compileOnly(project(":server"))
    compileOnly("net.minestom:minestom:2026.09.12-26.2")
    compileOnly("com.h2database:h2:2.5.250")
    compileOnly(project(":modules:nodes"))
    compileOnly(project(":modules:utils"))
    compileOnly(project(":modules:vanilla"))
}
