plugins {
    kotlin("jvm")
    id("org.jlleitschuh.gradle.ktlint")
    id("com.gradleup.shadow")
}

version = ""

base {
    archivesName.set("aechronis")
}

tasks.withType<Jar>().configureEach {
    manifest {
        attributes["Main-Class"] = "net.aechronis.aechronis.AechronisKt"
    }
}

dependencies {
    implementation("net.minestom:minestom:2026.07.12-26.2")
    implementation(project(":modules:utils"))
    implementation(project(":modules:nodes"))
    implementation(project(":modules:combat"))
    implementation(project(":modules:vanilla"))
    implementation(project(":modules:logger"))
    implementation(project(":modules:watchdog"))
    implementation(project(":modules:worldedit"))

    implementation("com.conceptmc:luckperms-minestom:5.5-SNAPSHOT")
    implementation("com.h2database:h2:2.4.240")
    implementation("com.zaxxer:HikariCP:7.0.2")
    implementation("org.slf4j:slf4j-simple:2.0.18")
    implementation("dev.lu15:spark-minestom:1.10-SNAPSHOT")
    implementation("io.github.4drian3d:signedvelocity-minestom:1.4.1")
    implementation("org.everbuild.blocksandstuff:blocksandstuff-blocks:1.10.2-SNAPSHOT")
    implementation("org.everbuild.blocksandstuff:blocksandstuff-fluids:1.10.2-SNAPSHOT")
}
