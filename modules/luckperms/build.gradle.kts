import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    kotlin("jvm")
    id("org.jlleitschuh.gradle.ktlint")
}

dependencies {
    compileOnly(project(":server"))
    compileOnly("net.minestom:minestom:2026.10.05-26.2")
    add("moduleImplementation", "net.aechronis:luckperms-minestom:5.5.87-minestom.1")
}

tasks.named<ShadowJar>("shadowJar") {
    // Share Adventure with the server, but retain MiniMessage, which it does not provide.
    exclude {
        !it.isDirectory &&
            it.path.startsWith("net/kyori/adventure/") &&
            !it.path.startsWith("net/kyori/adventure/text/minimessage/")
    }
}
