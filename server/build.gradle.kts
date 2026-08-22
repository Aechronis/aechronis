import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

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
        attributes["Main-Class"] = "net.aechronis.server.ServerKt"
    }
}

val luckPermsBundle = configurations.create("luckPermsBundle")

tasks.named<ShadowJar>("shadowJar") {
    dependencies {
        exclude(dependency("com.conceptmc:luckperms-minestom:5.5-SNAPSHOT"))
    }
    from(luckPermsBundle.files.map(::zipTree)) {
        exclude("net/kyori/adventure/**")
    }

    relocate("org.apache.http", "net.aechronis.craftingstore.libs.apache.http")
    relocate("org.apache.commons", "net.aechronis.craftingstore.libs.apache.commons")
    relocate("commons.logging", "net.aechronis.craftingstore.libs.commons.logging")
    relocate("io.socket", "net.aechronis.craftingstore.libs.socket")
    relocate("org.json", "net.aechronis.craftingstore.libs.json")
    relocate("okhttp3", "net.aechronis.craftingstore.libs.okhttp3")
    relocate("okio", "net.aechronis.craftingstore.libs.okio")

    from(rootProject.file("resource-pack")) {
        into("embedded-resource-pack")
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
    implementation(project(":modules:guard"))
    implementation(project(":modules:gems"))
    implementation(project(":modules:craftingstore"))
    implementation(project(":modules:worldedit"))

    implementation("com.conceptmc:luckperms-minestom:5.5-SNAPSHOT")
    add("luckPermsBundle", "com.conceptmc:luckperms-minestom:5.5-SNAPSHOT")
    implementation("com.h2database:h2:2.4.240")
    implementation("com.zaxxer:HikariCP:7.1.0")
    implementation("org.slf4j:slf4j-simple:2.0.18")
    implementation("dev.lu15:spark-minestom:1.10-SNAPSHOT")
    implementation("io.github.4drian3d:signedvelocity-minestom:1.4.1")
    implementation("org.everbuild.blocksandstuff:blocksandstuff-blocks:1.10.2-SNAPSHOT")
    implementation("org.everbuild.blocksandstuff:blocksandstuff-fluids:1.10.2-SNAPSHOT")
}
