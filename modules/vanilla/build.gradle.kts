plugins {
    kotlin("jvm")
    id("org.jlleitschuh.gradle.ktlint")
    `maven-publish`
}

dependencies {
    api("net.minestom:minestom:2026.07.12-26.2")
    api("com.google.code.gson:gson:2.14.0")
    implementation("com.cronutils:cron-utils:9.2.1")
    implementation("com.modernmt.text:profanity-filter:1.0.1")
    compileOnly("org.everbuild.blocksandstuff:blocksandstuff-blocks:1.10.2-SNAPSHOT")
    compileOnly("org.everbuild.blocksandstuff:blocksandstuff-common:1.10.2-SNAPSHOT")
    compileOnly(project(":modules:utils"))
    compileOnly(project(":modules:combat"))

    testImplementation("com.conceptmc:luckperms-minestom:5.5-SNAPSHOT")
    testImplementation("com.google.code.gson:gson:2.14.0")
    testImplementation(project(":modules:utils"))
    testImplementation(project(":modules:combat"))
    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.2")
    testImplementation("org.junit.platform:junit-platform-launcher:6.1.2")
    testImplementation("org.slf4j:slf4j-simple:2.0.18")
}
