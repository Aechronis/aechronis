import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    add("moduleImplementation", libs.grim)
}

tasks.named<ShadowJar>("shadowJar") {
    // These classes are supplied by core so native players survive engine replacement.
    exclude("ac/grim/grimac/minestom/**")
}
