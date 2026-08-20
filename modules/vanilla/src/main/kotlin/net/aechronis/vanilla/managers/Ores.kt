package net.aechronis.vanilla.managers

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import net.aechronis.vanilla.Vanilla
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.BlockVec
import net.minestom.server.entity.Player
import net.minestom.server.event.player.PlayerBlockBreakEvent
import net.minestom.server.event.player.PlayerChunkLoadEvent
import net.minestom.server.event.player.PlayerSpawnEvent
import net.minestom.server.instance.Instance
import net.minestom.server.instance.block.Block
import net.minestom.server.item.ItemStack
import net.minestom.server.network.packet.server.play.BlockChangePacket
import net.minestom.server.timer.TaskSchedule
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.floor

/** Configured, player-specific regenerating ores. */
object Ores {
    data class OreLocation(
        val world: String,
        val x: Int,
        val y: Int,
        val z: Int,
    )

    data class Ore(
        val timeSeconds: Long,
        @Transient var originalBlock: Block? = null,
    )

    private data class SavedOre(
        val world: String,
        val x: Int,
        val y: Int,
        val z: Int,
        val timeSeconds: Long,
    )

    private data class Cooldown(
        val player: UUID,
        val ore: OreLocation,
    )

    val ores = ConcurrentHashMap<OreLocation, Ore>()

    private val cooldowns = ConcurrentHashMap<Cooldown, Long>()
    private val gson = Gson()
    private lateinit var file: Path

    fun init(path: Path) {
        file = path
        Files.createDirectories(path.parent)
        load()

        Vanilla.eventNode.addListener(PlayerBlockBreakEvent::class.java, Ores::onBreak)
        Vanilla.eventNode.addListener(PlayerChunkLoadEvent::class.java, Ores::onChunkLoad)
        Vanilla.eventNode.addListener(PlayerSpawnEvent::class.java, Ores::onSpawn)

        MinecraftServer
            .getSchedulerManager()
            .buildTask(::regenerationTick)
            .repeat(TaskSchedule.seconds(1))
            .schedule()

        println("├─ Ores enabled (${ores.size} configured)")
    }

    fun saveAll() = save()

    fun configure(
        player: Player,
        timeSeconds: Long,
    ): Boolean {
        if (timeSeconds <= 0) return false
        val target = targetOre(player) ?: return false
        val location = location(player.instance ?: return false, target)
        val block = player.instance!!.getBlock(target)
        ores[location] = Ore(timeSeconds, block)
        cooldowns.keys.removeIf { it.ore == location }
        save()
        player.sendMessage(
            Component.text(
                "Configured ${block.name()} at ${location.x}, ${location.y}, ${location.z} to regenerate in $timeSeconds seconds per player.",
                NamedTextColor.GREEN,
            ),
        )
        return true
    }

    private fun onBreak(event: PlayerBlockBreakEvent) {
        if (event.isCancelled) return
        val player = event.player
        val instance = player.instance ?: return
        val location = location(instance, event.blockPosition)
        val ore = ores[location] ?: return

        val original = ore.originalBlock
        if (original != null && !event.block.compare(original)) return
        if (original == null) {
            if (!isOre(event.block)) return
            ore.originalBlock = event.block
        }

        event.isCancelled = true
        val cooldown = Cooldown(player.uuid, location)
        val now = System.currentTimeMillis()
        val expiresAt = cooldowns[cooldown]
        if (expiresAt != null && expiresAt > now) {
            sendBlock(player, location, Block.BEDROCK)
            return
        }
        cooldowns.remove(cooldown)

        val material = event.block.registry()?.material() ?: return
        val config = Vanilla.config
        if (material in config.blocksConfig.blocksRequiringTool) {
            val heldMaterial = player.itemInMainHand.material()
            if (config.blocksConfig.toolMinableBlocks[heldMaterial]?.contains(material) != true) return
        }

        val heldItem = player.itemInMainHand
        val silkTouch =
            heldItem
                .get(
                    net.minestom.server.component.DataComponents.ENCHANTMENTS,
                    net.minestom.server.item.component.EnchantmentList.EMPTY,
                ).has(net.minestom.server.item.enchant.Enchantment.SILK_TOUCH)
        val drops =
            if (silkTouch && material in config.blocksConfig.blocksSilkTouchable) {
                listOf(ItemStack.of(material))
            } else {
                config.blocksConfig.blockDrops[material] ?: listOf(ItemStack.of(material))
            }
        val dropPos = event.blockPosition.add(0.5, 0.5, 0.5).asPos()
        for (stack in drops) {
            if (!stack.isAir && stack.amount() > 0) Items.spawn(instance, dropPos, stack)
        }

        val damagedTool = heldItem.damage(1)
        if (damagedTool != heldItem) player.itemInMainHand = damagedTool

        cooldowns[cooldown] = now + ore.timeSeconds * 1_000L
        sendBlock(player, location, Block.BEDROCK)
    }

    private fun onChunkLoad(event: PlayerChunkLoadEvent) {
        val player = event.player
        val world = player.instance?.getDimensionName() ?: return
        for ((location, ore) in ores) {
            if (location.world != world || location.x shr 4 != event.chunkX || location.z shr 4 != event.chunkZ) continue
            refresh(player, location, ore)
        }
    }

    private fun onSpawn(event: PlayerSpawnEvent) {
        refreshPlayer(event.player)
    }

    private fun refreshPlayer(player: Player) {
        val world = player.instance?.getDimensionName() ?: return
        val now = System.currentTimeMillis()
        for ((cooldown, expiresAt) in cooldowns) {
            if (cooldown.player != player.uuid || expiresAt <= now || cooldown.ore.world != world) continue
            ores[cooldown.ore]?.let { refresh(player, cooldown.ore, it) }
        }
    }

    private fun refresh(
        player: Player,
        location: OreLocation,
        ore: Ore,
    ) {
        val cooldown = Cooldown(player.uuid, location)
        val expiresAt = cooldowns[cooldown] ?: return
        if (expiresAt <= System.currentTimeMillis()) return
        sendBlock(player, location, Block.BEDROCK)
    }

    private fun regenerationTick() {
        val now = System.currentTimeMillis()
        for ((cooldown, expiresAt) in cooldowns) {
            if (expiresAt > now || !cooldowns.remove(cooldown, expiresAt)) continue
            val player = MinecraftServer.getConnectionManager().onlinePlayers.firstOrNull { it.uuid == cooldown.player }
            val ore = ores[cooldown.ore]
            val instance = player?.instance
            if (player != null && instance != null && instance.getDimensionName() == cooldown.ore.world && ore != null) {
                val block = ore.originalBlock ?: instance.getBlock(cooldown.ore.x, cooldown.ore.y, cooldown.ore.z)
                if (isOre(block)) {
                    ore.originalBlock = block
                    sendBlock(player, cooldown.ore, block)
                }
            }
        }
    }

    private fun sendBlock(
        player: Player,
        location: OreLocation,
        block: Block,
    ) = player.sendPacket(BlockChangePacket(BlockVec(location.x, location.y, location.z), block))

    private fun targetOre(player: Player): BlockVec? {
        val instance = player.instance ?: return null
        val start = player.position.add(0.0, player.getEyeHeight(), 0.0)
        val direction = player.position.direction()
        var x = floor(start.x()).toInt()
        var y = floor(start.y()).toInt()
        var z = floor(start.z()).toInt()
        val stepX =
            direction.x().let {
                if (it > 0) {
                    1
                } else if (it < 0) {
                    -1
                } else {
                    0
                }
            }
        val stepY =
            direction.y().let {
                if (it > 0) {
                    1
                } else if (it < 0) {
                    -1
                } else {
                    0
                }
            }
        val stepZ =
            direction.z().let {
                if (it > 0) {
                    1
                } else if (it < 0) {
                    -1
                } else {
                    0
                }
            }
        val deltaX = if (stepX == 0) Double.POSITIVE_INFINITY else abs(1.0 / direction.x())
        val deltaY = if (stepY == 0) Double.POSITIVE_INFINITY else abs(1.0 / direction.y())
        val deltaZ = if (stepZ == 0) Double.POSITIVE_INFINITY else abs(1.0 / direction.z())
        var maxX =
            if (stepX >
                0
            ) {
                (x + 1 - start.x()) / direction.x()
            } else if (stepX < 0) {
                (start.x() - x) / -direction.x()
            } else {
                Double.POSITIVE_INFINITY
            }
        var maxY =
            if (stepY >
                0
            ) {
                (y + 1 - start.y()) / direction.y()
            } else if (stepY < 0) {
                (start.y() - y) / -direction.y()
            } else {
                Double.POSITIVE_INFINITY
            }
        var maxZ =
            if (stepZ >
                0
            ) {
                (z + 1 - start.z()) / direction.z()
            } else if (stepZ < 0) {
                (start.z() - z) / -direction.z()
            } else {
                Double.POSITIVE_INFINITY
            }

        repeat(128) {
            if (minOf(maxX, maxY, maxZ) > MAX_REACH) return null
            val block = instance.getBlock(x, y, z)
            if (!block.isAir) return if (isOre(block)) BlockVec(x, y, z) else null
            when (minOf(maxX, maxY, maxZ)) {
                maxX -> {
                    x += stepX
                    maxX += deltaX
                }

                maxY -> {
                    y += stepY
                    maxY += deltaY
                }

                else -> {
                    z += stepZ
                    maxZ += deltaZ
                }
            }
        }
        return null
    }

    private fun location(
        instance: Instance,
        position: BlockVec,
    ) = OreLocation(instance.getDimensionName(), position.blockX(), position.blockY(), position.blockZ())

    private fun isOre(block: Block): Boolean = block.name().endsWith("_ore")

    private fun load() {
        ores.clear()
        if (!Files.exists(file)) return
        runCatching {
            Files.newBufferedReader(file).use { reader ->
                val type = object : TypeToken<List<SavedOre>>() {}.type
                val saved: List<SavedOre>? = gson.fromJson(reader, type)
                saved.orEmpty().filter { it.timeSeconds > 0 }.forEach { entry ->
                    ores[OreLocation(entry.world, entry.x, entry.y, entry.z)] = Ore(entry.timeSeconds)
                }
            }
        }.onFailure { error ->
            System.err.println("Failed to load ores: ${error.message}")
        }
    }

    private fun save() {
        Files.newBufferedWriter(file).use { writer ->
            val saved =
                ores.map { (location, ore) ->
                    SavedOre(location.world, location.x, location.y, location.z, ore.timeSeconds)
                }
            gson.toJson(saved, writer)
        }
    }

    private const val MAX_REACH = 6.0
}
