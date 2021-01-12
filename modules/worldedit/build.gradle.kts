import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    java
    kotlin("jvm") version "1.4.30-M1"
}

group = "io.github.openminigameserver"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://jitpack.io")
    maven("https://maven.enginehub.org/repo/")
    maven("https://libraries.minecraft.net")
    maven("https://repo.spongepowered.org/maven")
    maven("https://oss.sonatype.org/content/repositories/snapshots/")
    maven("https://dl.bintray.com/kotlin/kotlin-eap")
}

val configurateVersion = "4.0.0"
dependencies {
    compileOnly(minestom("c5d56ae820"))
    testApi(minestom("c5d56ae820"))
    implementation("com.sk89q.worldedit:worldedit-core:7.3.0-SNAPSHOT")
    implementation(kotlin("stdlib-jdk8"))

    implementation("org.spongepowered:configurate-yaml:$configurateVersion") {
        exclude(module = "geantyref")
    }
    implementation("org.spongepowered:configurate-extra-kotlin:$configurateVersion")

}

fun minestom(commit: String): String {
    return "com.github.Minestom:Minestom:$commit"
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}
val compileKotlin: KotlinCompile by tasks
compileKotlin.kotlinOptions {
    jvmTarget = "1.8"
}
val compileTestKotlin: KotlinCompile by tasks
compileTestKotlin.kotlinOptions {
    jvmTarget = "1.8"
}