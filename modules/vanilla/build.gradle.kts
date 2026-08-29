plugins {
    kotlin("jvm")
    id("org.jlleitschuh.gradle.ktlint")
}

dependencies {
    compileOnly(project(":server"))
    compileOnly("net.minestom:minestom:2026.07.12-26.2")
    compileOnly("com.google.code.gson:gson:2.14.0")
    add("moduleImplementation", "com.cronutils:cron-utils:9.2.1") {
        exclude(group = "org.slf4j", module = "slf4j-api")
    }
    add("moduleImplementation", "com.modernmt.text:profanity-filter:1.0.1")
    compileOnly("org.everbuild.blocksandstuff:blocksandstuff-blocks:1.10.2-SNAPSHOT")
    compileOnly("org.everbuild.blocksandstuff:blocksandstuff-common:1.10.2-SNAPSHOT")
    compileOnly(project(":modules:utils"))
    compileOnly(project(":modules:combat"))

    testImplementation("com.conceptmc:luckperms-minestom:5.5-SNAPSHOT")
    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    testImplementation("org.junit.platform:junit-platform-launcher:6.1.2")
    testImplementation("org.slf4j:slf4j-simple:2.0.18")
}
