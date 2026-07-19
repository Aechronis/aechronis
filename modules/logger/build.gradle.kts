group = "net.aechronis"
version = System.getenv("GITHUB_SHA")?.take(7) ?: "local"

plugins {
    `maven-publish`
    id("org.jetbrains.kotlin.jvm") version "2.3.20"
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
}

java.toolchain.languageVersion = JavaLanguageVersion.of(25)

repositories {
    mavenCentral()
    maven("https://repo.hypera.dev/snapshots/") // luckperms
    maven {
        url = uri("https://maven.pkg.github.com/Aechronis/aechronis")
        credentials {
            username = project.findProperty("gpr.user") as String? ?: System.getenv("GITHUB_ACTOR")
            password = project.findProperty("gpr.token") as String? ?: System.getenv("GITHUB_TOKEN")
        }
    }
}

dependencies {
    api("net.aechronis:utils:86a747b")
    api("net.minestom:minestom:2026.07.12-26.2")

    // database
    api("org.xerial:sqlite-jdbc:3.53.2.0")
    api("com.zaxxer:HikariCP:7.1.0")

    // testing
    testImplementation("net.aechronis:utils:86a747b")
    testImplementation("org.xerial:sqlite-jdbc:3.53.2.0")
    testImplementation("com.zaxxer:HikariCP:7.1.0")
    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.2")
    testImplementation("org.slf4j:slf4j-simple:2.0.18") // logging (only used while testing at the moment)
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
            url = uri("https://maven.pkg.github.com/Aechronis/logger")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
    }
}
