plugins {
    kotlin("jvm")
    id("org.jlleitschuh.gradle.ktlint")
}

dependencies {
    compileOnly(project(":server"))
    compileOnly("net.minestom:minestom:2026.07.12-26.2")
    compileOnly(project(":modules:utils"))

    testImplementation("com.google.code.gson:gson:2.14.0")
    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.2")
    testImplementation("org.junit.platform:junit-platform-launcher:6.1.2")
    testImplementation("org.slf4j:slf4j-simple:2.0.18")
}
