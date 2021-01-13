import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    idea
    java
    `java-library`
    kotlin("jvm") version "1.4.30-M1"
    id("com.github.johnrengelman.shadow") version "6.1.0"
    maven
}

group = "io.github.openminigameserver"
version = "1.1-SNAPSHOT"

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
    api("com.sk89q.worldedit:worldedit-core:7.3.0-SNAPSHOT")
    compileOnly(kotlin("stdlib-jdk8"))

    implementation("org.spongepowered:configurate-yaml:$configurateVersion") {
        exclude(module = "geantyref")
    }
    implementation("org.spongepowered:configurate-extra-kotlin:$configurateVersion")

}


tasks {
    shadowJar {
        archiveClassifier.set("")
    }

    val templateContext = mapOf("version" to project.version.toString())
    processResources {
        expand(*templateContext.toList().toTypedArray())
    }

    create<Copy>("generateKotlinBuildInfo") {
        inputs.properties(templateContext) // for gradle up-to-date check
        from("src/template/kotlin/")
        into("$buildDir/generated/kotlin/")
        expand(*templateContext.toList().toTypedArray())
    }

    kotlin.sourceSets["main"].kotlin.srcDir("$buildDir/generated/kotlin")
    compileKotlin.get().dependsOn(get("generateKotlinBuildInfo"))

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