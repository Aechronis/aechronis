version = "0.0.14"

plugins {
    application
    id("org.jetbrains.kotlin.jvm") version "2.3.0"
//    id("org.jlleitschuh.gradle.ktlint") version "14.0.1"
    id("com.gradleup.shadow") version "9.3.1"
}

java.toolchain.languageVersion = JavaLanguageVersion.of(25)

repositories {
    mavenCentral()
    mavenLocal() // spark-minestom
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.lucko.me/") // spark-common
    maven("https://mvn.everbuild.org/public")
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib")

    implementation("org.spigotmc:spigot-api:1.21.11-R0.1-SNAPSHOT")

    implementation("net.minestom:minestom:2025.10.31-1.21.10")

    // the maven repo for this doesnt work atm, so build from source (https://github.com/LooFifteen/spark/commits/feat/minestom/) and publish to maven local
    implementation("dev.lu15:spark-minestom:1.10-SNAPSHOT")

    implementation("org.slf4j:slf4j-simple:2.0.17")

    // blocks and stuff
    implementation("org.everbuild.blocksandstuff:blocksandstuff-blocks:1.9.0-SNAPSHOT")
    implementation("org.everbuild.blocksandstuff:blocksandstuff-common:1.9.0-SNAPSHOT")
    implementation("org.everbuild.blocksandstuff:blocksandstuff-fluids:1.9.0-SNAPSHOT")

    // minestompvp
    implementation("io.github.togar2:MinestomPvP:2025.12.19-1.21.10")
}

// build task only outputs shadow jar
tasks.jar { enabled = false }
tasks.distZip { enabled = false }
tasks.distTar { enabled = false }
tasks.startScripts { enabled = false }
tasks.build { dependsOn(tasks.shadowJar) }

tasks.shadowJar {
    archiveClassifier.set("") // no "-all"
    manifest.attributes("Main-Class" to "luna.nodes.MainKt")
}