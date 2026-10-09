import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.file.DuplicatesStrategy

plugins {
    kotlin("jvm")
    id("org.jlleitschuh.gradle.ktlint")
    id("com.gradleup.shadow")
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
    grimMinestom("net.aechronis:grim-minestom:2.3.74-minestom.4") {
        isTransitive = false
    }
    implementation("net.minestom:minestom:2026.10.05-26.2")
    // Shared socket types also serve Votifier and PacketEvents. Keep one compatible Netty
    // runtime in core while the protocol engines and all Via state live in their module.
    implementation(platform("io.netty:netty-bom:4.2.18.Final"))
    implementation("io.netty:netty-handler")
    // NuVotifier probes Epoll availability even when it falls back to NIO.
    runtimeOnly("io.netty:netty-transport-classes-epoll")

    implementation("com.h2database:h2:2.5.252")
    runtimeOnly("org.mariadb.jdbc:mariadb-java-client:3.5.10")
    implementation("com.zaxxer:HikariCP:7.1.0")
    implementation("org.slf4j:slf4j-simple:2.0.20")
    implementation("io.github.4drian3d:signedvelocity-minestom:1.4.1")
    implementation("org.everbuild.blocksandstuff:blocksandstuff-blocks:1.10.2-SNAPSHOT")
    implementation("org.everbuild.blocksandstuff:blocksandstuff-fluids:1.10.2-SNAPSHOT")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    // Shared coroutine worker pools must not retain a reloadable module classloader.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
}
