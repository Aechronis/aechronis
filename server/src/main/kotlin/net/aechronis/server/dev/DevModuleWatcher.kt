package net.aechronis.server.dev

import net.aechronis.server.modules.ModuleOperationResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import kotlin.io.path.isRegularFile

/** Development-only build worker. Never runs Gradle or waits for reloads on a game thread. */
internal class DevModuleWatcher(
    private val projectRoot: Path,
    modulePaths: List<String>,
    private val moduleDirectory: Path,
    private val reload: (() -> Unit) -> CompletableFuture<ModuleOperationResult>,
    private val quietMillis: Long = 750,
    buildFiles: List<String> = (modulePaths + listOf("server", "modules/misc")).map { "$it/build.gradle.kts" },
) : AutoCloseable {
    private val moduleRoots = modulePaths.flatMap { listOf("$it/src/main", "$it/resource-pack") }
    private val coreRoots =
        listOf(
            "server/src/main",
            "modules/misc/src/main",
            "build.gradle.kts",
            "settings.gradle.kts",
            "gradle.properties",
            "gradle",
            "gradlew",
            "gradlew.bat",
            "buildSrc/src",
            "buildSrc/build.gradle.kts",
            "buildSrc/settings.gradle.kts",
        ) + buildFiles
    private val initialCore = snapshot(coreRoots)
    private val initialModules = snapshot(moduleRoots)
    private val worker =
        Thread
            .ofPlatform()
            .daemon()
            .name("dev-module-watcher")
            .unstarted(::watch)
    private val processLock = Any()

    @Volatile private var closed = false

    private var buildProcess: Process? = null

    fun start() {
        log("Watching module sources and resource packs; successful builds reload modules without restarting the server.")
        worker.start()
    }

    private fun watch() {
        var handled = initialCore + initialModules
        var previous = handled
        while (!closed) {
            try {
                Thread.sleep(quietMillis)
                val core = snapshot(coreRoots)
                val modules = snapshot(moduleRoots)
                val current = core + modules
                if (current != previous) {
                    previous = current
                    continue
                }
                if (current == handled) continue
                handled = current
                if (core != initialCore) {
                    log("Core or build configuration changed. Automatic reloads are paused; revert those changes or manually rerun devRun.")
                    continue
                }
                log("Module inputs changed; building...")
                if (!build()) {
                    if (!closed) log("Build failed; running modules are unchanged. Save another edit to retry.")
                    continue
                }
                if (closed) break
                if (snapshot(coreRoots) + snapshot(moduleRoots) != current) {
                    log("Inputs changed during the build; waiting for a fresh build before installing.")
                    continue
                }
                val result =
                    reload {
                        check(!closed) { "Development watcher has stopped" }
                        check(snapshot(coreRoots) + snapshot(moduleRoots) == current) {
                            "Inputs changed before installation; waiting for another build"
                        }
                        installModules(projectRoot.resolve("build/dev-watch/modules"), moduleDirectory)
                    }.get()
                log(result.message + if (result.affectedIds.isEmpty()) "" else ": ${result.affectedIds.sorted().joinToString()}")
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } catch (error: Exception) {
                if (!closed) log("Automatic reload failed: ${error.message}. Save another edit to retry.")
            }
        }
    }

    private fun build(): Boolean {
        val wrapper =
            if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
                listOf("cmd", "/c", projectRoot.resolve("gradlew.bat").toString())
            } else {
                listOf(projectRoot.resolve("gradlew").toString())
            }
        val process =
            synchronized(processLock) {
                if (closed) return false
                ProcessBuilder(
                    wrapper +
                        listOf(
                            "--console=plain",
                            // The outer devRun JavaExec owns its project cache until the server exits.
                            "--project-cache-dir",
                            projectRoot.resolve("build/dev-watch/gradle-cache").toString(),
                            "assembleDevModules",
                        ),
                ).directory(projectRoot.toFile())
                    .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                    .apply { environment()["JAVA_HOME"] = System.getProperty("java.home") }
                    .start()
                    .also {
                        buildProcess = it
                        it.outputStream.close()
                    }
            }
        try {
            return process.waitFor() == 0
        } finally {
            synchronized(processLock) {
                if (process.isAlive) process.destroy()
                buildProcess = null
            }
        }
    }

    override fun close() {
        synchronized(processLock) {
            closed = true
            buildProcess?.destroy()
        }
        worker.interrupt()
    }

    private fun snapshot(roots: List<String>): Map<Path, String> =
        buildMap {
            roots.forEach { relative ->
                val root = projectRoot.resolve(relative)
                if (Files.exists(root)) {
                    Files.walk(root).use { paths ->
                        paths.filter { it.isRegularFile() }.forEach { path ->
                            val digest = MessageDigest.getInstance("SHA-256")
                            Files.newInputStream(path).use { input ->
                                val buffer = ByteArray(8192)
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    digest.update(buffer, 0, count)
                                }
                            }
                            put(path, digest.digest().toHexString())
                        }
                    }
                }
            }
        }

    companion object {
        fun fromSystemProperties(
            moduleDirectory: Path,
            reload: (() -> Unit) -> CompletableFuture<ModuleOperationResult>,
        ): DevModuleWatcher? {
            val root = System.getProperty("aechronis.dev.projectRoot") ?: return null
            val modules = checkNotNull(System.getProperty("aechronis.dev.modulePaths"))
            val buildFiles = checkNotNull(System.getProperty("aechronis.dev.buildFiles"))
            return DevModuleWatcher(Path.of(root), modules.split(','), moduleDirectory, reload, buildFiles = buildFiles.split(','))
        }

        /** Copy everything before replacing any live JAR; rollback the disk set if installation fails. */
        internal fun installModules(
            source: Path,
            destination: Path,
        ) {
            val jars =
                Files.list(source).use { paths ->
                    paths
                        .filter { it.isRegularFile() && it.toString().endsWith(".jar") }
                        .sorted()
                        .toList()
                }
            require(jars.isNotEmpty()) { "The development build produced no module JARs" }
            val staging = Files.createTempDirectory(destination, ".dev-install-")
            val installed = mutableListOf<Path>()
            try {
                jars.forEach { jar ->
                    val target = destination.resolve(jar.fileName)
                    require(Files.isRegularFile(target)) {
                        "Module layout changed; manually rerun devRun before installing ${jar.fileName}"
                    }
                    Files.copy(jar, staging.resolve(jar.fileName.toString() + ".new"))
                    Files.copy(target, staging.resolve(jar.fileName.toString() + ".old"))
                }
                jars.forEach { jar ->
                    val target = destination.resolve(jar.fileName)
                    if (Files.mismatch(jar, target) != -1L) {
                        Files.move(
                            staging.resolve(jar.fileName.toString() + ".new"),
                            target,
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING,
                        )
                        installed.add(jar)
                    }
                }
            } catch (error: Exception) {
                installed.asReversed().forEach { jar ->
                    runCatching {
                        Files.move(
                            staging.resolve(jar.fileName.toString() + ".old"),
                            destination.resolve(jar.fileName),
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING,
                        )
                    }.onFailure(error::addSuppressed)
                }
                throw error
            } finally {
                Files.list(staging).use { paths -> paths.forEach(Files::deleteIfExists) }
                Files.deleteIfExists(staging)
            }
        }

        private fun log(message: String) {
            println("[devRun] $message")
        }
    }
}
