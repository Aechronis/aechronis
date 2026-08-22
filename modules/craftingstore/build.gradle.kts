plugins {
    `java-library`
    kotlin("jvm")
    id("org.jlleitschuh.gradle.ktlint")
    `maven-publish`
}

dependencies {
    api("net.minestom:minestom:2026.07.12-26.2")
    implementation("com.google.code.gson:gson:2.14.0")
    implementation("org.apache.httpcomponents:httpclient:4.5.14")
    implementation("org.apache.commons:commons-lang3:3.18.0")
    implementation("org.apache.commons:commons-text:1.14.0")
    implementation("commons-logging:commons-logging:1.3.5")
    implementation("com.github.thijsa:socket.io-client-java:1.0.3")

    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.18")
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
}
