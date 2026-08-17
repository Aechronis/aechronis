plugins {
    kotlin("jvm")
    id("org.jlleitschuh.gradle.ktlint")
    `maven-publish`
}

dependencies {
    implementation("net.minestom:minestom:2026.08.07-26.2")
    implementation("org.everbuild.blocksandstuff:blocksandstuff-blocks:1.10.2-SNAPSHOT")
    implementation(project(":modules:combat"))
    implementation(project(":modules:utils"))

    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.2")
    testImplementation("org.slf4j:slf4j-simple:2.0.18")
}
