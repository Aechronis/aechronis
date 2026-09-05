plugins {
    kotlin("jvm")
    id("org.jlleitschuh.gradle.ktlint")
}

dependencies {
    compileOnly(project(":server"))
    compileOnly("net.minestom:minestom:2026.08.16-26.2")
    compileOnly("com.google.code.gson:gson:2.14.0")
    compileOnly("org.everbuild.blocksandstuff:blocksandstuff-blocks:1.10.2-SNAPSHOT")
    compileOnly(project(":modules:combat"))
    compileOnly(project(":modules:utils"))
    compileOnly(project(":modules:vanilla"))
    compileOnly(project(":modules:worldedit"))
}
