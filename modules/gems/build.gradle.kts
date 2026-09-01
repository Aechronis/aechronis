plugins {
    kotlin("jvm")
    id("org.jlleitschuh.gradle.ktlint")
}

dependencies {
    compileOnly(project(":server"))
    compileOnly("net.minestom:minestom:2026.08.16-26.2")
    compileOnly("com.h2database:h2:2.4.240")
    compileOnly(project(":modules:nodes"))
    compileOnly(project(":modules:utils"))
    compileOnly(project(":modules:vanilla"))

    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.2")
}
