plugins {
    kotlin("jvm")
    id("org.jlleitschuh.gradle.ktlint")
}

dependencies {
    compileOnly(project(":server"))
    compileOnly("com.conceptmc:luckperms-minestom:5.5-SNAPSHOT")
    compileOnly("net.minestom:minestom:2026.08.16-26.2")
}
