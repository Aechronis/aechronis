group = "net.aechronis"
version = System.getenv("GITHUB_SHA")?.take(7) ?: "local"

plugins {
    `maven-publish`
    id("org.jetbrains.kotlin.jvm") version "2.4.10"
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
    maven {
        url = uri("https://maven.pkg.github.com/Aechronis/vanilla")
        credentials {
            username = project.findProperty("gpr.user") as String? ?: System.getenv("GITHUB_ACTOR")
            password = project.findProperty("gpr.token") as String? ?: System.getenv("GITHUB_TOKEN")
        }
    }
}

dependencies {
    compileOnly("net.aechronis:utils:86a747b")
    compileOnly("net.minestom:minestom:2026.07.12-26.2")
    compileOnly("net.aechronis:vanilla:dc271de")

    // database
    compileOnly("com.h2database:h2:2.4.240")
    compileOnly("com.zaxxer:HikariCP:7.1.0")

    // testing
    testImplementation("net.aechronis:utils:86a747b")
    testImplementation("net.aechronis:vanilla:dc271de")
    testImplementation("com.h2database:h2:2.4.240")
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
