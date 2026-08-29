plugins {
    kotlin("jvm")
    id("org.jlleitschuh.gradle.ktlint")
}

dependencies {
    compileOnly(project(":server"))
    compileOnly("net.minestom:minestom:2026.07.12-26.2")
    compileOnly("com.google.code.gson:gson:2.14.0")
    compileOnly(project(":modules:watchdog"))
    compileOnly(project(":modules:utils"))

    testImplementation("com.conceptmc:luckperms-minestom:5.5-SNAPSHOT")
    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    testImplementation("org.slf4j:slf4j-simple:2.0.18")
}
