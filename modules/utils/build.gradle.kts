plugins {
    `java-library`
    kotlin("jvm")
    id("org.jlleitschuh.gradle.ktlint")
    `maven-publish`
}

dependencies {
    compileOnly("com.conceptmc:luckperms-minestom:5.5-SNAPSHOT")
    api("net.minestom:minestom:2026.07.12-26.2")

    testImplementation("com.conceptmc:luckperms-minestom:5.5-SNAPSHOT")
    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
