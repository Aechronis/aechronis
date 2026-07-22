group = "net.aechronis"

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.3.21"
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
    id("com.gradleup.shadow") version "9.4.1"
}

tasks.withType<Jar> {
    manifest {
        attributes["Main-Class"] = "net.aechronis.aechronis.AechronisKt"
    }
}

java.toolchain.languageVersion = JavaLanguageVersion.of(25)

repositories {
    mavenCentral()
    maven("https://maven.conceptmc.com/releases") // luckperms (minestom)
    maven("https://repo.hypera.dev/snapshots/") // Spark
    maven("https://repo.lucko.me/") // spark-common
    maven("https://repo.smolder.fr/public/") // axiom minestom component
    maven("https://mvn.everbuild.org/public") // blocks and stuff
    maven("https://maven.enginehub.org/repo/") // worldedit

    maven {
        url = uri("https://maven.pkg.github.com/Aechronis/aechronis")
        credentials {
            username = project.findProperty("gpr.user") as String? ?: System.getenv("GITHUB_ACTOR")
            password = project.findProperty("gpr.token") as String? ?: System.getenv("GITHUB_TOKEN")
        }
    }
}

dependencies {
    // minestom
    implementation("net.minestom:minestom:2026.07.12-26.2")
    // utils
    implementation("net.aechronis:utils:86a747b")
    // nodes
    implementation("net.aechronis:nodes:d0a0f34")
    // combat
    implementation("net.aechronis:combat:a7c3f57")
    // vanilla
    implementation("net.aechronis:vanilla:db7afbd")
    // logger
    implementation("net.aechronis:logger:b2ecab0")
    // axiom
    implementation("fr.ghostrider584:axiom-minestom:0.0.4")
    // worldedit
    implementation("net.aechronis:worldedit:59a508e") {
        exclude(group = "com.google.code.gson", module = "gson")
    }
    // luckperms
    implementation("com.conceptmc:luckperms-minestom:5.5-SNAPSHOT")
    implementation("org.mariadb.jdbc:mariadb-java-client:3.5.8")
    implementation("com.h2database:h2:2.4.240")
    implementation("com.zaxxer:HikariCP:7.0.2")
    // simple logs
    implementation("org.slf4j:slf4j-simple:2.0.17")
    // spark
    implementation("dev.lu15:spark-minestom:1.10-SNAPSHOT")
    // signed velocity
    implementation("io.github.4drian3d:signedvelocity-minestom:1.4.1")
    // blocks and stuff
    implementation("org.everbuild.blocksandstuff:blocksandstuff-blocks:1.10.2-SNAPSHOT")
    implementation("org.everbuild.blocksandstuff:blocksandstuff-fluids:1.10.2-SNAPSHOT")
}
