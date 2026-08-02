plugins {
    kotlin("jvm")
    id("org.jlleitschuh.gradle.ktlint")
    `maven-publish`
}

dependencies {
    implementation("net.minestom:minestom:2026.07.12-26.2")
    compileOnly(project(":modules:utils"))

    testImplementation("com.conceptmc:luckperms-minestom:5.5-SNAPSHOT")
    testImplementation(project(":modules:utils"))
    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.2")
    testImplementation("org.slf4j:slf4j-simple:2.0.18")
}
