plugins {
    kotlin("jvm")
    id("org.jlleitschuh.gradle.ktlint")
    `maven-publish`
}

dependencies {
    compileOnly(project(":modules:utils"))
    compileOnly("net.minestom:minestom:2026.07.22-26.2")
    compileOnly(project(":modules:vanilla"))
    compileOnly(project(":modules:worldedit"))

    compileOnly("com.h2database:h2:2.4.240")
    compileOnly("com.zaxxer:HikariCP:7.1.0")

    testImplementation(project(":modules:utils"))
    testImplementation(project(":modules:vanilla"))
    testImplementation(project(":modules:worldedit"))
    testImplementation("com.h2database:h2:2.4.240")
    testImplementation("com.zaxxer:HikariCP:7.1.0")
    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.2")
    testImplementation("org.slf4j:slf4j-simple:2.0.18")
}
