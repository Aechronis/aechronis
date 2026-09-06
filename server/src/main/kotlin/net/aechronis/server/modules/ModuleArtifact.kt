package net.aechronis.server.modules

import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.ServiceLoader

internal data class ModuleDefinition(
    override val id: String,
    override val dependencies: Set<String>,
    override val conflicts: Set<String>,
    override val reloadTogether: Set<String>,
    val provider: String,
) : AechronisModule

/** A private, immutable JAR copy survives deployment replacement and supports fresh rollback loaders. */
internal class ModuleArtifact(
    val directory: Path,
    val jar: Path,
    val sourceName: String,
    val fingerprint: String,
    val definition: ModuleDefinition,
) : AutoCloseable {
    override fun close() = deleteModuleTree(directory)

    companion object {
        fun stage(sources: List<Path>): List<ModuleArtifact> {
            val copies = mutableListOf<Pair<Path, Path>>()
            try {
                sources.forEach { source ->
                    val directory = Files.createTempDirectory("aechronis-module-")
                    val target = directory.resolve(source.fileName)
                    copies += directory to target
                    val before = Files.readAttributes(source, BasicFileAttributes::class.java)
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
                    val after = Files.readAttributes(source, BasicFileAttributes::class.java)
                    require(
                        before.size() == after.size() &&
                            before.lastModifiedTime() == after.lastModifiedTime() &&
                            before.fileKey() == after.fileKey(),
                    ) {
                        "Module JAR changed while being staged: $source"
                    }
                }
                // Providers expose metadata only. Never configure/initialize probe instances.
                val probeLoader =
                    URLClassLoader(
                        copies.map { it.second.toUri().toURL() }.toTypedArray(),
                        AechronisModule::class.java.classLoader,
                    )
                probeLoader.use { probe ->
                    val modules = withContextClassLoader(probe) { ServiceLoader.load(AechronisModule::class.java, probe).toList() }
                    require(modules.map { it.id }.distinct().size == modules.size) { "Duplicate module ids" }
                    return copies.map { (directory, jar) ->
                        val providers =
                            modules.filter {
                                Path.of(
                                    it.javaClass.protectionDomain.codeSource.location
                                        .toURI(),
                                ) == jar
                            }
                        require(
                            providers.size == 1,
                        ) { "Module JAR '${jar.fileName}' must declare exactly one AechronisModule provider (found ${providers.size})" }
                        val module = providers.single()
                        val definition =
                            ModuleDefinition(
                                module.id,
                                module.dependencies.toSet(),
                                module.conflicts.toSet(),
                                module.reloadTogether.toSet(),
                                module.javaClass.name,
                            )
                        require(
                            (definition.dependencies + definition.conflicts + definition.reloadTogether + definition.id).all {
                                it.matches(Regex("[a-z0-9][a-z0-9_-]*"))
                            },
                        ) { "Invalid module ID in ${definition.id}" }
                        require(
                            definition.reloadTogether.all {
                                it in definition.dependencies
                            },
                        ) { "${definition.id}: reloadTogether must name direct dependencies" }
                        val digest = MessageDigest.getInstance("SHA-256")
                        Files.newInputStream(jar).use { input ->
                            val buffer = ByteArray(65536)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                digest.update(buffer, 0, count)
                            }
                        }
                        ModuleArtifact(
                            directory,
                            jar,
                            jar.fileName.toString(),
                            digest.digest().joinToString("") { "%02x".format(it) },
                            definition,
                        )
                    }
                }
            } catch (error: Throwable) {
                copies.forEach { deleteModuleTree(it.first) }
                throw error
            }
        }
    }
}

internal fun deleteModuleTree(root: Path) {
    if (!Files.exists(root)) return
    Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
}
