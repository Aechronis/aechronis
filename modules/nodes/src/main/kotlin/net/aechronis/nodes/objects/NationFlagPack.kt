package net.aechronis.nodes.objects

import net.aechronis.server.modules.ModuleContext
import net.minestom.server.MinecraftServer
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock

/**
 * A shared, non-personalized dynamic resource pack: a flag-on-a-pole item per nation.
 * Rebuilt whenever a nation's flag URL changes via [Nation.setFlagUrl] ("/nda nation flag").
 */
object NationFlagPack {
    private val packMetadata =
        """{"pack":{"description":"           §6§lAechronis\n§7   Nation flags resource pack","min_format":[75,0],"max_format":[88,0]}}"""
            .toByteArray()

    private val poleTexture = readResourceBytes("/flag-textures/flag_pole.png")

    // Each geometry resource is `{"elements":[...],"display":{...}}` (converted from NoProfit's
    // Blockbench models); its leading "{" is dropped so "textures" can be spliced in ahead of it.
    private val geometryBodyByVariant: Map<FlagModelVariant, String> =
        FlagModelVariant.entries.associateWith { variant ->
            readResourceText("/flag-textures/flag_geometry_${variant.resourceName}.json").removePrefix("{")
        }

    @Volatile private var context: ModuleContext? = null
    private var registration: AutoCloseable? = null

    // The pack is identical for every player, so it's built once and reused until a flag
    // changes. Without this, every join/refresh re-walked every nation and re-touched the
    // texture cache. A ReentrantLock (not `synchronized`) guards the rebuild so a cache miss's
    // blocking download doesn't pin the calling virtual thread.
    @Volatile private var cachedAssets: Map<String, ByteArray>? = null

    // Bumped on every flag change. A build only stores its result if no change happened while it
    // was running, otherwise it would put a stale flag back into the cache.
    private val generation = AtomicInteger()
    private val buildLock = ReentrantLock()

    fun initialize(context: ModuleContext) {
        this.context = context
        registration = context.registerPlayerResourcePack("nation-flags") { _ -> buildAssets() }
    }

    fun flagModelId(nationUuid: UUID): String = "aechronis:flag_$nationUuid"

    /** Invalidates the cached texture and pushes the updated pack to everyone currently online. */
    fun onFlagChanged(nationUuid: UUID) {
        NationFlagTexture.invalidate(nationUuid)
        generation.incrementAndGet()
        cachedAssets = null
        val context = context ?: return
        MinecraftServer.getConnectionManager().onlinePlayers.forEach(context::refreshPlayerResourcePacks)
    }

    private fun buildAssets(): Map<String, ByteArray>? {
        if (context == null) return null
        cachedAssets?.let { return it }
        buildLock.lock()
        try {
            cachedAssets?.let { return it }
            val startedAt = generation.get()
            val assets = mutableMapOf("pack.mcmeta" to packMetadata)
            assets["assets/aechronis/textures/item/flag_pole.png"] = poleTexture
            val flagged = Nation.all().mapNotNull { nation -> nation.flagUrl?.let { nation to it } }
            // Downloads run in parallel (NationFlagTexture caps how many are in flight at once).
            val textures =
                Executors.newVirtualThreadPerTaskExecutor().use { executor ->
                    flagged.map { (nation, url) -> executor.submit(Callable { NationFlagTexture.resolve(nation.uuid, url) }) }.map { it.get() }
                }
            flagged.forEachIndexed { index, (nation, _) ->
                val texture = textures[index] ?: return@forEachIndexed
                val id = "flag_${nation.uuid}"
                assets["assets/aechronis/items/$id.json"] =
                    """{"model":{"type":"minecraft:model","model":"aechronis:item/$id"}}""".toByteArray()
                assets["assets/aechronis/models/item/$id.json"] =
                    (
                        """{"textures":{"cloth":"aechronis:item/$id","pole":"aechronis:item/flag_pole","particle":"aechronis:item/$id"},""" +
                            geometryBodyByVariant.getValue(texture.variant)
                        ).toByteArray()
                assets["assets/aechronis/textures/item/$id.png"] = texture.png
            }
            // A flag that failed to load is left out for now. Don't cache the pack in that case, so
            // the next build retries it once its short failure cooldown is over.
            if (textures.all { it != null } && generation.get() == startedAt) cachedAssets = assets
            return assets
        } finally {
            buildLock.unlock()
        }
    }

    fun shutdown() {
        context = null
        cachedAssets = null
        registration?.close()
        registration = null
    }

    private fun readResourceBytes(path: String): ByteArray = checkNotNull(javaClass.getResourceAsStream(path)) { "Missing bundled resource: $path" }.use { it.readBytes() }

    private fun readResourceText(path: String): String = String(readResourceBytes(path))
}
