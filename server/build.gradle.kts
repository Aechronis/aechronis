import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.file.DuplicatesStrategy

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.shadow)
}

version = ""

// Compile against the single Minestom library, but keep only its stable hooks in core.
// The adapter and engine dependencies belong to the reloadable Grim module.
val grimMinestom =
    configurations.create("grimMinestom") {
        isCanBeConsumed = false
        isTransitive = false
    }
configurations.compileOnly {
    extendsFrom(grimMinestom)
}

base {
    archivesName.set("aechronis")
}

tasks.withType<Jar>().configureEach {
    manifest {
        attributes["Main-Class"] = "net.aechronis.server.ServerKt"
    }
}

tasks.named<Jar>("jar") {
    archiveClassifier.set("plain")
    destinationDirectory.set(layout.buildDirectory.dir("plain-libs"))
}

tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("")
    dependsOn(grimMinestom)
    from({ grimMinestom.map { zipTree(it) } }) {
        include("ac/grim/grimac/minestom/**")
    }
    eachFile {
        if (path.endsWith(".kotlin_module")) {
            duplicatesStrategy = DuplicatesStrategy.INCLUDE
        }
    }
}

dependencies {
    grimMinestom(libs.grim) {
        isTransitive = false
    }
    implementation(libs.minestom)
    // Shared socket types also serve Votifier and PacketEvents. Keep one compatible Netty
    // runtime in core while the protocol engines and all Via state live in their module.
    implementation(platform(libs.netty.bom))
    implementation(libs.netty.handler)
    // NuVotifier probes Epoll availability even when it falls back to NIO.
    runtimeOnly(libs.netty.epoll)

    implementation(libs.h2)
    runtimeOnly(libs.mariadb)
    implementation(libs.hikari)
    implementation(libs.slf4j.simple)
    implementation(libs.signedvelocity)
    implementation(libs.blocksandstuff.blocks)
    implementation(libs.blocksandstuff.fluids)
    implementation(libs.kotlinx.serialization.json)
    // Shared coroutine worker pools must not retain a reloadable module classloader.
    implementation(libs.kotlinx.coroutines.core)
}
