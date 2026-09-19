import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    kotlin("jvm")
    id("org.jlleitschuh.gradle.ktlint")
}

dependencies {
    compileOnly(project(":server"))
    compileOnly("net.minestom:minestom:2026.09.12-26.2")
    add("moduleImplementation", "com.conceptmc:luckperms-minestom:5.5-SNAPSHOT")
}

tasks.named<ShadowJar>("shadowJar") {
    // Use the server's Adventure version instead of the older bundled classes.
    exclude("net/kyori/adventure/**")
}
