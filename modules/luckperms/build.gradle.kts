import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    add("moduleImplementation", libs.luckperms.minestom)
}

tasks.named<ShadowJar>("shadowJar") {
    // Share Adventure with the server, but retain MiniMessage, which it does not provide.
    exclude {
        !it.isDirectory &&
            it.path.startsWith("net/kyori/adventure/") &&
            !it.path.startsWith("net/kyori/adventure/text/minimessage/")
    }
}
