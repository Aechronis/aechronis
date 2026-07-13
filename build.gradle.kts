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
    maven("https://repo.hypera.dev/snapshots/") // luckperms (minestom) & Spark
    maven("https://repo.lucko.me/") // spark-common
    maven("https://repo.smolder.fr/public/") // axiom minestom component
    maven("https://mvn.everbuild.org/public") // blocks and stuff
    maven {
        url = uri("https://maven.pkg.github.com/Aechronis/aechronis")
        credentials {
            username = project.findProperty("gpr.user") as String? ?: System.getenv("GITHUB_ACTOR")
            password = project.findProperty("gpr.token") as String? ?: System.getenv("GITHUB_TOKEN")
        }
    }
}

dependencies {
    implementation("net.minestom:minestom:2026.04.13-1.21.11")
    implementation("net.aechronis:nodes:4d702e2")
    implementation("net.aechronis:combat:48326f9")
    implementation("net.aechronis:vanilla:ad0180e")
    implementation("dev.lu15:luckperms-minestom:5.5-SNAPSHOT")
    implementation("org.mariadb.jdbc:mariadb-java-client:3.5.8")
    implementation("com.h2database:h2:2.2.220")
    implementation("com.zaxxer:HikariCP:7.0.2")
    implementation("org.slf4j:slf4j-simple:2.0.17")
    implementation("fr.ghostrider584:axiom-minestom:0.0.3")
    implementation("dev.lu15:spark-minestom:1.10-SNAPSHOT")
    implementation("io.github.4drian3d:signedvelocity-minestom:1.4.1")
    implementation("org.everbuild.blocksandstuff:blocksandstuff-blocks:1.9.1-SNAPSHOT")
    implementation("org.everbuild.blocksandstuff:blocksandstuff-fluids:1.9.1-SNAPSHOT")
}
