package net.aechronis.spark

import com.google.gson.Gson
import me.lucko.spark.common.platform.MetadataProvider
import me.lucko.spark.common.sampler.source.ClassSourceLookup
import me.lucko.spark.common.sampler.source.SourceMetadata
import me.lucko.spark.common.util.classfinder.ClassFinder
import net.aechronis.server.modules.ModuleContext
import net.aechronis.server.modules.ModuleDiagnostics
import net.minestom.server.MinecraftServer

internal class SparkDiagnostics(
    private val context: ModuleContext,
) {
    private val gson = Gson()

    fun sources(): List<SourceMetadata> =
        listOf(SourceMetadata("aechronis", "unknown", "", "Server core", true)) +
            ModuleDiagnostics.sources().map {
                SourceMetadata(it.id, it.fingerprint.take(12), "", "Runtime module (${it.artifact})", false)
            }

    fun sourceLookup(): ClassSourceLookup =
        ClassSourceLookup { type ->
            ModuleDiagnostics.sourceOf(type)?.id ?: if (type.name.startsWith("net.aechronis.server.")) "aechronis" else null
        }

    fun classFinder(fallback: ClassFinder): ClassFinder =
        ClassFinder { name ->
            ModuleDiagnostics.findClass(name)
                ?: fallback.findClass(name)
        }

    fun metadata(): MetadataProvider =
        MetadataProvider {
            val snapshot = context.moduleSnapshot()
            mapOf(
                "aechronis" to
                    gson.toJsonTree(
                        mapOf(
                            "moduleGeneration" to snapshot.generation,
                            "modulePhase" to snapshot.phase,
                            "modules" to
                                snapshot.modules.map {
                                    mapOf(
                                        "id" to it.id,
                                        "enabled" to it.enabled,
                                        "dependencies" to it.dependencies.sorted(),
                                    )
                                },
                            "instances" to
                                MinecraftServer.getInstanceManager().instances.map {
                                    mapOf(
                                        "id" to it.uuid.toString(),
                                        "dimension" to it.dimensionName,
                                    )
                                },
                            "unsupportedWorldFields" to listOf("blockEntityCounts", "vanillaGameRules", "datapacks"),
                        ),
                    ),
            )
        }
}
