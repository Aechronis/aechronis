plugins {
    kotlin("jvm")
    id("org.jlleitschuh.gradle.ktlint")
    `maven-publish`
}

dependencies {
    implementation("net.minestom:minestom:2026.07.12-26.2")
    implementation("com.h2database:h2:2.4.240")
    implementation(project(":modules:nodes"))
    implementation(project(":modules:utils"))

    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.2")
}
