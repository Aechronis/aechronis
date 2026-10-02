plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("org.jlleitschuh.gradle.ktlint")
}

tasks.processResources {
    from("LICENSE") {
        into("META-INF/licenses/votifier")
    }
}

dependencies {
    compileOnly(project(":server"))
    compileOnly(project(":modules:gems"))
    compileOnly(project(":modules:vanilla"))
    compileOnly("net.minestom:minestom:2026.09.12-26.2")
    compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    add("moduleImplementation", "com.github.NuVotifier.NuVotifier:nuvotifier-api:2.7.1") {
        exclude(group = "io.netty")
    }
    add("moduleImplementation", "com.github.NuVotifier.NuVotifier:nuvotifier-common:2.7.1") {
        exclude(group = "io.netty")
    }
    compileOnly("io.netty:netty-handler:4.2.18.Final")
}
