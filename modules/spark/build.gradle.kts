plugins {
    kotlin("jvm")
    id("org.jlleitschuh.gradle.ktlint")
}

val sparkVersion = "1.10.186-SNAPSHOT"

dependencies {
    compileOnly(project(":server"))
    compileOnly("net.minestom:minestom:2026.09.12-26.2")
    compileOnly("org.slf4j:slf4j-api:2.0.19")
    add("moduleImplementation", "me.lucko:spark-common:$sparkVersion")
    // Upstream Spark expects its platform adapter to provide these libraries
    add("moduleImplementation", "com.google.guava:guava:33.3.1-jre")
    add("moduleImplementation", "com.google.code.gson:gson:2.14.0")
}

tasks.withType<Jar>().configureEach {
    manifest.attributes["Implementation-Version"] = sparkVersion
}
