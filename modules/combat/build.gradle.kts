group = "luna.nodes"
version = System.getenv("GITHUB_SHA")?.take(7) ?: ""

plugins {
    `maven-publish`
    id("org.jetbrains.kotlin.jvm") version "2.3.0"
    id("org.jlleitschuh.gradle.ktlint") version "14.0.1"
}

java.toolchain.languageVersion = JavaLanguageVersion.of(25)

repositories {
    mavenCentral()
    maven("https://repo.hudkit.net/releases")
}

dependencies {
    implementation("net.minestom:minestom:2026.01.08-1.21.11")

    // testing
    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("org.junit.jupiter:junit-jupiter:6.0.2")
    testImplementation("org.slf4j:slf4j-simple:2.0.17") // logging (only used while testing at the moment)
}

tasks.test {
    useJUnitPlatform()

    systemProperty("keepRunning", System.getProperty("keepRunning", "false"))
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/d-z4/combat")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
    }
}
