plugins {
    kotlin("jvm")
    id("org.jlleitschuh.gradle.ktlint")
    `maven-publish`
}

dependencies {
    api("net.minestom:minestom:2026.07.12-26.2")
    compileOnly("org.everbuild.blocksandstuff:blocksandstuff-blocks:1.10.2-SNAPSHOT")
    compileOnly(project(":modules:combat"))
    compileOnly(project(":modules:utils"))

    testImplementation("org.everbuild.blocksandstuff:blocksandstuff-blocks:1.10.2-SNAPSHOT")
    testImplementation(project(":modules:combat"))
    testImplementation(project(":modules:utils"))
    testImplementation("com.conceptmc:luckperms-minestom:5.5-SNAPSHOT")
    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.2")
    testImplementation("org.slf4j:slf4j-simple:2.0.18")
}
