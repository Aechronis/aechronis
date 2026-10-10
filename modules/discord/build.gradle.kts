import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    alias(libs.plugins.kotlin.jvm)
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
    compileOnly(project(":modules:nodes"))
    compileOnly(project(":modules:utils"))
    compileOnly(libs.luckperms.api)
    add("moduleImplementation", libs.kord)
    add("moduleImplementation", libs.ktor.okhttp)
}
