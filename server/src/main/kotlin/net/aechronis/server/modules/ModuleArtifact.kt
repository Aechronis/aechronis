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
            // Reused artifacts remain owned by the manager, including if this stage fails.
            val copies = mutableListOf<Path>()
            val reused = mutableMapOf<Path, ModuleArtifact>()
            val knownSources = previous.associateBy { it.sourceName }
            val knownContent = previous.associateBy { it.fingerprint }
            try {
                val jars =
                    sources.map { source ->
                        val before = Files.readAttributes(source, BasicFileAttributes::class.java)
                        val known = knownSources[source.fileName.toString()]
                        if (known != null && fingerprint(source) == known.fingerprint) {
                            requireStableSource(source, before)
                            reused[known.jar] = known
                            known.jar
                        } else {
                            val directory = Files.createTempDirectory("aechronis-module-")
                            copies.add(directory)
                            val target = directory.resolve(source.fileName)
                            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
                            requireStableSource(source, before)
                            target
                        }
                    }
                if (copies.isEmpty()) {
                    return jars.map(reused::getValue).also { requireUniqueIds(it) }
                }
                // Providers expose metadata only. Never configure/initialize probe instances.
                // Inspect the full generation so a changed JAR cannot shadow an unchanged provider.
                val probeLoader =
                    URLClassLoader(
                        jars.map { it.toUri().toURL() }.toTypedArray(),
                        AechronisModule::class.java.classLoader,
                    )
                return probeLoader.use { probe ->
                    val providersByJar =
                        withContextClassLoader(probe) {
                            ServiceLoader
                                .load(AechronisModule::class.java, probe)
                                .stream()
                                .toList()
                                .groupBy {
                                    Path.of(
                                        it
                                            .type()
                                            .protectionDomain
                                            .codeSource
                                            .location
                                            .toURI(),
                                    )
                                }
                        }
                    val inheritedIds =
                        providersByJar.filterKeys { it !in jars }.values.flatten().map { provider ->
                            withContextClassLoader(probe) { provider.get().id }
                        }
                    jars
                        .map { jar ->
                            val providers = providersByJar[jar].orEmpty()
                            require(
                                providers.size == 1,
                            ) { "Module JAR '${jar.fileName}' must declare exactly one AechronisModule provider (found ${providers.size})" }
                            reused[jar]?.let { known ->
                                require(providers.single().type().name == known.definition.provider) {
                                    "Module provider changed while reusing ${jar.fileName}"
                                }
                                return@map known
                            }
                            val module = withContextClassLoader(probe) { providers.single().get() }
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
                            // Hash the private copy, not the mutable source used for the reuse check.
                            val fingerprint = fingerprint(jar)
                            val known = knownContent[fingerprint]
                            val (codeFingerprint, resourcePackFingerprint) =
                                if (known == null) contentFingerprints(jar) else known.codeFingerprint to known.resourcePackFingerprint
                            ModuleArtifact(
                                jar.parent,
                                jar,
                                jar.fileName.toString(),
                                fingerprint,
                                definition,
                                codeFingerprint,
                                resourcePackFingerprint,
                            ).also { artifact -> definition.reloadTogetherInputs.keys.forEach(artifact::dependencyFingerprint) }
                        }.also { requireUniqueIds(it, inheritedIds) }
                }
            } catch (error: Throwable) {
                copies.forEach(::deleteModuleTree)
                throw error
            }
        }

        private fun requireUniqueIds(
            artifacts: List<ModuleArtifact>,
            inheritedIds: List<String> = emptyList(),
        ) {
            val ids = artifacts.map { it.definition.id } + inheritedIds
            require(ids.distinct().size == ids.size) { "Duplicate module ids" }
        }

        private fun requireStableSource(
            source: Path,
            before: BasicFileAttributes,
        ) {
            val after = Files.readAttributes(source, BasicFileAttributes::class.java)
            require(
                before.size() == after.size() &&
                    before.lastModifiedTime() == after.lastModifiedTime() &&
                    before.fileKey() == after.fileKey(),
            ) { "Module JAR changed while being staged: $source" }
        }

        private fun fingerprint(jar: Path): String {
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(jar).use { input ->
                val buffer = ByteArray(65536)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().toHexString()
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
