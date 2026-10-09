import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    kotlin("jvm")
    id("org.jlleitschuh.gradle.ktlint")
}

// The server owns coroutine dispatchers; this module owns and cancels its jobs.
configurations.named("moduleLibrariesClasspath") {
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
}

tasks.named<ShadowJar>("shadowJar") {
    // LuckPerms bundles an older OkHttp/Okio. Dependency-first module loading must
    // not substitute those classes for the versions required by Ktor.
    relocate("okhttp3", "net.aechronis.discord.internal.okhttp3")
    relocate("okio", "net.aechronis.discord.internal.okio")
}

dependencies {
    compileOnly(project(":server"))
    compileOnly(project(":modules:nodes"))
    compileOnly(project(":modules:utils"))
    compileOnly("net.luckperms:api:5.5")
    compileOnly("net.minestom:minestom:2026.10.05-26.2")
    add("moduleImplementation", "dev.kord:kord-core:0.18.1")
    add("moduleImplementation", "io.ktor:ktor-client-okhttp:3.6.0")
}
