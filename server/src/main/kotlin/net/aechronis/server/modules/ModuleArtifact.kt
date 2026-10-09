package net.aechronis.server.modules

import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.ServiceLoader
import java.util.jar.JarFile

internal data class ModuleDefinition(
    override val id: String,
    override val dependencies: Set<String>,
    override val conflicts: Set<String>,
    override val reloadTogether: Set<String>,
    val provider: String,
    override val reloadTogetherInputs: Map<String, Set<String>> = emptyMap(),
) : AechronisModule

/** A private, immutable JAR copy survives deployment replacement and supports fresh rollback loaders. */
internal class ModuleArtifact(
    val directory: Path,
    val jar: Path,
    val sourceName: String,
    val fingerprint: String,
    val definition: ModuleDefinition,
    val codeFingerprint: String = fingerprint,
    val resourcePackFingerprint: String? = null,
) : AutoCloseable {
    override fun close() = deleteModuleTree(directory)

    private val dependencyFingerprints = mutableMapOf<String, String>()

    internal fun dependencyFingerprint(dependency: String): String? {
        val inputs = definition.reloadTogetherInputs[dependency] ?: return null
        return dependencyFingerprints.getOrPut(dependency) {
            val digest = MessageDigest.getInstance("SHA-256")
            JarFile(jar.toFile()).use { archive ->
                val entries =
                    archive
                        .entries()
                        .asSequence()
                        .filterNot { it.isDirectory }
                        .toList()

                fun matches(
                    name: String,
                    input: String,
                ): Boolean =
                    name == input ||
                        (input.endsWith('/') && name.startsWith(input)) ||
                        (input.endsWith(".class") && name.startsWith(input.removeSuffix(".class") + "$"))
                require(inputs.all { input -> entries.any { matches(it.name, input) } }) {
                    "${definition.id}: missing reload configuration input for $dependency"
                }
                entries.filter { entry -> inputs.any { matches(entry.name, it) } }.sortedBy { it.name }.forEach { entry ->
                    digest.update(entry.name.toByteArray(Charsets.UTF_8))
                    digest.update(0.toByte())
                    archive.getInputStream(entry).use { digest.update(it.readAllBytes()) }
                }
            }
            digest.digest().toHexString()
        }
    }

    companion object {
        fun stage(
            sources: List<Path>,
            previous: Collection<ModuleArtifact> = emptyList(),
        ): List<ModuleArtifact> {
            val copies = mutableListOf<Pair<Path, Path>>()
            val knownContent = previous.associateBy { it.fingerprint }
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
                                module.reloadTogetherInputs.mapValues { it.value.toSet() },
                            )
                        require(
                            (
                                definition.dependencies + definition.conflicts + definition.reloadTogether +
                                    definition.reloadTogetherInputs.keys +
                                    definition.id
                            ).all {
                                it.matches(Regex("[a-z0-9][a-z0-9_-]*"))
                            },
                        ) { "Invalid module ID in ${definition.id}" }
                        require(
                            definition.reloadTogether.all {
                                it in definition.dependencies
                            },
                        ) { "${definition.id}: reloadTogether must name direct dependencies" }
                        require(
                            definition.reloadTogetherInputs.all { (dependency, inputs) ->
                                dependency in definition.dependencies && inputs.isNotEmpty() && inputs.none { it.isBlank() }
                            },
                        ) { "${definition.id}: reloadTogetherInputs must name direct dependencies and nonempty JAR inputs" }
                        val digest = MessageDigest.getInstance("SHA-256")
                        Files.newInputStream(jar).use { input ->
                            val buffer = ByteArray(65536)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                digest.update(buffer, 0, count)
                            }
                        }
                        val fingerprint = digest.digest().toHexString()
                        val known = knownContent[fingerprint]
                        val (codeFingerprint, resourcePackFingerprint) =
                            if (known == null) contentFingerprints(jar) else known.codeFingerprint to known.resourcePackFingerprint
                        ModuleArtifact(
                            directory,
                            jar,
                            jar.fileName.toString(),
                            fingerprint,
                            definition,
                            codeFingerprint,
                            resourcePackFingerprint,
                        ).also { artifact -> definition.reloadTogetherInputs.keys.forEach(artifact::dependencyFingerprint) }
                    }
                }
            } catch (error: Throwable) {
                copies.forEach { deleteModuleTree(it.first) }
                throw error
            }
        }

        /** ZIP timestamps/compression do not change code or pack content identity. */
        internal fun contentFingerprints(jar: Path): Pair<String, String?> {
            val code = MessageDigest.getInstance("SHA-256")
            val resources = MessageDigest.getInstance("SHA-256")
            var hasPack = false
            JarFile(jar.toFile()).use { archive ->
                archive.entries().asSequence().filterNot { it.isDirectory }.sortedBy { it.name }.forEach { entry ->
                    val embedded = entry.name.startsWith("embedded-resource-pack/")
                    val digest = if (embedded) resources else code
                    hasPack = hasPack || entry.name == "embedded-resource-pack/pack.mcmeta"
                    digest.update(entry.name.toByteArray(Charsets.UTF_8))
                    digest.update(0.toByte())
                    digest.update(entry.size.toString().toByteArray(Charsets.UTF_8))
                    digest.update(0.toByte())
                    archive.getInputStream(entry).use { input ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            digest.update(buffer, 0, count)
                        }
                    }
                }
            }
            return code.digest().toHexString() to resources.digest().toHexString().takeIf { hasPack }
        }
    }
}

internal fun deleteModuleTree(root: Path) {
    if (!Files.exists(root)) return
    Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
}
