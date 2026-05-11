group = "net.aechronis"

plugins {
    id("java")
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
    implementation("net.minestom:minestom:2026.05.11-1.21.11")
    implementation("net.aechronis:nodes:139c100")
    implementation("net.aechronis:combat:2ec1b64")
    implementation("net.aechronis:vanilla:9719b6f")
    implementation("dev.lu15:luckperms-minestom:5.5-SNAPSHOT")
    implementation("org.mariadb.jdbc:mariadb-java-client:3.5.8")
    implementation("com.zaxxer:HikariCP:7.0.2")
    implementation("org.slf4j:slf4j-simple:2.0.17")
    implementation("fr.ghostrider584:axiom-minestom:0.0.4")
    implementation("dev.lu15:spark-minestom:1.10-SNAPSHOT")
    implementation("io.github.4drian3d:signedvelocity-minestom:1.4.1")
    implementation("org.everbuild.blocksandstuff:blocksandstuff-blocks:1.9.1-SNAPSHOT")
    implementation("org.everbuild.blocksandstuff:blocksandstuff-fluids:1.9.1-SNAPSHOT")
}
