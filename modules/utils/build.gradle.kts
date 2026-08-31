plugins {
    kotlin("jvm")
    id("org.jlleitschuh.gradle.ktlint")
}

dependencies {
    compileOnly(project(":server"))
    compileOnly("com.conceptmc:luckperms-minestom:5.5-SNAPSHOT")
    compileOnly("net.minestom:minestom:2026.08.16-26.2")

    testImplementation("com.conceptmc:luckperms-minestom:5.5-SNAPSHOT")
    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
