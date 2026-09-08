plugins {
    `java-library`
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("org.jlleitschuh.gradle.ktlint")
}

dependencies {
    api("net.minestom:minestom:2026.08.28-26.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    api("net.craftingstore:core:2.11.2-SNAPSHOT") {
        isChanging = true
    }
    api("com.github.NuVotifier.NuVotifier:nuvotifier-api:2.7.1")
    api("com.github.NuVotifier.NuVotifier:nuvotifier-common:2.7.1")
    compileOnly("io.netty:netty-handler:4.1.49.Final")
}
