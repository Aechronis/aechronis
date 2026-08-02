#!/usr/bin/env kotlin

import java.nio.file.Files
import java.nio.file.Path

fun runCommand(vararg command: String): String {
    val process = ProcessBuilder(*command)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    val exitCode = process.waitFor()

    check(exitCode == 0) {
        "${command.joinToString(" ")} failed with exit code $exitCode:\n$output"
    }

    return output
}

val repositoryRoot = Path.of(runCommand("git", "rev-parse", "--show-toplevel").trim())
val gradleFile = repositoryRoot.resolve("build.gradle.kts")
check(Files.isRegularFile(gradleFile)) {
    "Could not find $gradleFile"
}

val dependencyRegex = Regex(
    """(?<prefix>implementation\(\s*"net\.aechronis:(?<artifact>[^":]+):)(?<version>[0-9a-f]{7})(?<suffix>")"""
)
val originalGradle = Files.readString(gradleFile)
val latestCommits = mutableMapOf<String, String>()

fun latestCommit(artifact: String): String = latestCommits.getOrPut(artifact) {
    val remote = runCommand(
        "git",
        "ls-remote",
        "https://github.com/Aechronis/$artifact.git",
        "HEAD",
    )
    val hash = remote.trim().split(Regex("\\s+")).firstOrNull()
    check(hash != null && hash.length >= 7) {
        "Could not read a commit hash for $artifact"
    }
    hash.take(7)
}

val updatedGradle = dependencyRegex.replace(originalGradle) { match ->
    val artifact = match.groups["artifact"]!!.value
    val current = match.groups["version"]!!.value
    val latest = latestCommit(artifact)

    if (current == latest) {
        match.value
    } else {
        println("$artifact: $current -> $latest")
        "${match.groups["prefix"]!!.value}$latest${match.groups["suffix"]!!.value}"
    }
}

if (updatedGradle != originalGradle) {
    Files.writeString(gradleFile, updatedGradle)
    println("build.gradle.kts updated")
} else {
    println("depends up to date")
}
