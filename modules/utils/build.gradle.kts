group = "net.aechronis"
version = System.getenv("GITHUB_SHA")?.take(7) ?: "local"

plugins {
    `java-library`
    `maven-publish`
    id("org.jetbrains.kotlin.jvm") version "2.3.20"
    id("org.jlleitschuh.gradle.ktlint") version "14.0.1"
}

java.toolchain.languageVersion = JavaLanguageVersion.of(25)

repositories {
    mavenCentral()
    maven("https://repo.hypera.dev/snapshots/") {
        name = "HyperaSnapshots"
        content {
            includeGroup("dev.lu15")
        }
    }
}

dependencies {
    implementation("dev.lu15:luckperms-minestom:5.5-SNAPSHOT")
    api("net.minestom:minestom:2026.07.12-26.2")

    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("org.junit.jupiter:junit-jupiter:6.0.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
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
            url = uri("https://maven.pkg.github.com/Aechronis/utils")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
    }
}
