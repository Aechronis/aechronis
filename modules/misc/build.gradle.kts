plugins {
    `java-library`
    kotlin("jvm")
    id("org.jlleitschuh.gradle.ktlint")
}

dependencies {
    api("net.minestom:minestom:2026.07.12-26.2")
    implementation("com.google.code.gson:gson:2.14.0")
    api("net.craftingstore:core:2.11.2-SNAPSHOT") {
        isChanging = true
    }
    api("com.github.NuVotifier.NuVotifier:nuvotifier-api:2.7.1")
    api("com.github.NuVotifier.NuVotifier:nuvotifier-common:2.7.1")
    compileOnly("io.netty:netty-handler:4.1.49.Final")

    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.18")
}
