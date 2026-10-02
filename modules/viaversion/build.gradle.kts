plugins {
    `java-library`
    kotlin("jvm")
    id("org.jlleitschuh.gradle.ktlint")
}

dependencies {
    compileOnly(project(":server"))
    compileOnly("net.aechronis:grim-minestom:2.3.74-minestom.4") { isTransitive = false }
    compileOnly("net.minestom:minestom:2026.09.12-26.2")
    add("moduleImplementation", "com.viaversion:viaversion-common:5.11.0")
    add("moduleImplementation", "com.viaversion:viabackwards-common:5.11.0")
    compileOnly(platform("io.netty:netty-bom:4.2.18.Final"))
    compileOnly("io.netty:netty-handler")
    add("moduleImplementation", "com.google.guava:guava:33.7.2-jre")
    compileOnly("org.slf4j:slf4j-api:2.0.19")
}
