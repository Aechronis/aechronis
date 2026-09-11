import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.file.DuplicatesStrategy

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

tasks.named<Jar>("jar") {
    archiveClassifier.set("plain")
    destinationDirectory.set(layout.buildDirectory.dir("plain-libs"))
}

val luckPermsBundle = configurations.dependencyScope("luckPermsBundle")
val luckPermsBundleClasspath =
    configurations.resolvable("luckPermsBundleClasspath") {
        extendsFrom(luckPermsBundle.get())
    }
val luckPermsBundleTrees =
    providers.provider {
        luckPermsBundleClasspath.get().files.map(::zipTree)
    }

tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("")
    eachFile {
        if (path.endsWith(".kotlin_module")) {
            duplicatesStrategy = DuplicatesStrategy.INCLUDE
        }
    }
    dependencies {
        exclude(dependency("com.conceptmc:luckperms-minestom:5.5-SNAPSHOT"))
    }
    from(luckPermsBundleTrees) {
        exclude("net/kyori/adventure/**")
    }

    relocate("org.apache.http", "net.aechronis.craftingstore.libs.apache.http")
    relocate("org.apache.commons", "net.aechronis.craftingstore.libs.apache.commons")
    relocate("commons.logging", "net.aechronis.craftingstore.libs.commons.logging")
    relocate("io.socket", "net.aechronis.craftingstore.libs.socket")
    relocate("org.json", "net.aechronis.craftingstore.libs.json")
    relocate("okhttp3", "net.aechronis.craftingstore.libs.okhttp3")
    relocate("okio", "net.aechronis.craftingstore.libs.okio")
}

dependencies {
    implementation("net.minestom:minestom:2026.08.28-26.2")
    implementation(project(":modules:misc"))

    implementation("com.conceptmc:luckperms-minestom:5.5-SNAPSHOT")
    add("luckPermsBundle", "com.conceptmc:luckperms-minestom:5.5-SNAPSHOT")
    implementation("com.h2database:h2:2.5.250")
    runtimeOnly("org.mariadb.jdbc:mariadb-java-client:3.5.10")
    implementation("com.zaxxer:HikariCP:7.1.0")
    implementation("org.slf4j:slf4j-simple:2.0.19")
    implementation("dev.lu15:spark-minestom:1.10-SNAPSHOT")
    implementation("io.github.4drian3d:signedvelocity-minestom:1.5.0")
    implementation("org.everbuild.blocksandstuff:blocksandstuff-blocks:1.10.2-SNAPSHOT")
    implementation("org.everbuild.blocksandstuff:blocksandstuff-fluids:1.10.2-SNAPSHOT")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
}
