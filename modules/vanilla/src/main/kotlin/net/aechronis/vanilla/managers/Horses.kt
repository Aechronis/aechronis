package net.aechronis.vanilla.managers

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.aechronis.server.modules.ModuleScheduler
import net.aechronis.vanilla.Vanilla
import net.aechronis.vanilla.listeners.HorseListener
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.ServerFlag
import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.EntityCreature
import net.minestom.server.entity.EntityType
import net.minestom.server.entity.EquipmentSlot
import net.minestom.server.entity.Player
import net.minestom.server.entity.attribute.Attribute
import net.minestom.server.entity.metadata.animal.HorseMeta
import net.minestom.server.instance.Instance
import net.minestom.server.inventory.AbstractInventory
import net.minestom.server.inventory.Inventory
import net.minestom.server.inventory.InventoryType
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import net.minestom.server.network.packet.server.play.ParticlePacket
import net.minestom.server.particle.Particle
import net.minestom.server.timer.Task
import net.minestom.server.timer.TaskSchedule
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.floor
import kotlin.random.Random

/**
 * Tamed vanilla horses with vanilla breeding. Stat ranges are 20% above vanilla, so breeding
 * can reach 20% more health, speed and jump than any vanilla horse. Horses are saved to JSON
 * and, like other non-player entities, live only while their chunk is loaded.
 */
object Horses {
    private const val FORMAT_VERSION = 1
    private const val STAT_MULTIPLIER = 1.2
    private const val TICK_MS = 1_000L
    private const val GROWTH_MS = 20 * 60_000L
    private const val LOVE_MS = 30_000L
    private const val BREED_COOLDOWN_MS = 5 * 60_000L
    private const val BREED_RANGE = 8.0
    private const val CROWD_RANGE = 16.0
    private const val CROWD_LIMIT = 12
    private const val GOLDEN_CARROT_HEAL = 4f
    private const val GOLDEN_APPLE_HEAL = 10f

    // Minestom's default for horses, as in vanilla.
    private const val GRAVITY = 0.08

    // Ridden ground speed of a vanilla horse per point of its movement speed attribute.
    private const val BLOCKS_PER_SECOND_PER_SPEED = 42.16

    private val HEALTH_RANGE = 15.0 * STAT_MULTIPLIER..30.0 * STAT_MULTIPLIER
    private val SPEED_RANGE = 0.1125 * STAT_MULTIPLIER..0.3375 * STAT_MULTIPLIER
    private val JUMP_RANGE = 0.4 * STAT_MULTIPLIER..1.0 * STAT_MULTIPLIER

    @Serializable
    private data class SavedHorses(
        val version: Int,
        val horses: List<SavedHorse>,
    )

    @Serializable
    private data class SavedHorse(
        val x: Double,
        val y: Double,
        val z: Double,
        val yaw: Float,
        val health: Float,
        val maxHealth: Double,
        val speed: Double,
        val jump: Double,
        val variant: String,
        val marking: String,
        val saddled: Boolean,
        val growthRemainingMs: Long,
        val breedCooldownMs: Long,
    )

    private class HorseData(
        val maxHealth: Double,
        val speed: Double,
        val jump: Double,
        val variant: HorseMeta.Variant,
        val marking: HorseMeta.Marking,
        var saddled: Boolean = false,
        var growthRemainingMs: Long = 0,
        var breedCooldownMs: Long = 0,
        var loveRemainingMs: Long = 0,
        var rider: Player? = null,
    ) {
        val isFoal: Boolean get() = growthRemainingMs > 0
    }

    private data class ChunkKey(
        val x: Int,
        val z: Int,
    )

    private val horses = ConcurrentHashMap<EntityCreature, HorseData>()

    // Horses whose chunk is unloaded; they respawn when it loads again.
    private val dormant = ConcurrentHashMap<ChunkKey, MutableList<SavedHorse>>()
    private val mountBlockedUntil = ConcurrentHashMap<UUID, Long>()
    private lateinit var file: Path
    private lateinit var world: Instance
    private var tickTask: Task? = null

    fun init(
        path: Path,
        instance: Instance,
    ) {
        file = path
        world = instance
        load()
        HorseListener.init()
        tickTask =
            ModuleScheduler
                .buildTask { tick() }
                .delay(TaskSchedule.millis(TICK_MS))
                .repeat(TaskSchedule.millis(TICK_MS))
                .schedule()
    }

    fun isHorse(entity: Any?): Boolean = entity is EntityCreature && horses.containsKey(entity)

    // ================
    // SPAWNING
    // ================

    /** Spawns a fresh tamed adult with vanilla-distributed stats raised by 20%. */
    fun spawnFromEgg(
        instance: Instance,
        position: Pos,
    ) {
        val data =
            HorseData(
                maxHealth = (15.0 + Random.nextInt(8) + Random.nextInt(9)) * STAT_MULTIPLIER,
                speed = (0.45 + Random.nextDouble() * 0.3 + Random.nextDouble() * 0.3 + Random.nextDouble() * 0.3) * 0.25 * STAT_MULTIPLIER,
                jump = (0.4 + Random.nextDouble() * 0.2 + Random.nextDouble() * 0.2 + Random.nextDouble() * 0.2) * STAT_MULTIPLIER,
                variant = HorseMeta.Variant.entries.random(),
                marking = HorseMeta.Marking.entries.random(),
            )
        spawn(instance, position, data, data.maxHealth.toFloat())
    }

    private fun spawn(
        instance: Instance,
        position: Pos,
        data: HorseData,
        health: Float,
    ): EntityCreature {
        val horse = EntityCreature(EntityType.HORSE)
        horse.getAttribute(Attribute.MAX_HEALTH).baseValue = data.maxHealth
        horse.getAttribute(Attribute.MOVEMENT_SPEED).baseValue = data.speed
        horse.getAttribute(Attribute.JUMP_STRENGTH).baseValue = data.jump
        horse.health = health.coerceIn(1f, data.maxHealth.toFloat())
        horse.editEntityMeta(HorseMeta::class.java) { meta ->
            // Tamed horses can be steered by their rider once saddled.
            meta.isTamed = true
            meta.isBaby = data.isFoal
            meta.setVariantAndMarking(data.variant, data.marking)
        }
        if (data.saddled) horse.setEquipment(EquipmentSlot.SADDLE, ItemStack.of(Material.SADDLE))
        horses[horse] = data
        horse.setInstance(instance, position)
        return horse
    }

    // ================
    // INTERACTION
    // ================

    /** Returns true when [stack] was used on the horse and one should be consumed. */
    fun feed(
        horse: EntityCreature,
        stack: ItemStack,
    ): Boolean {
        val data = horses[horse] ?: return false
        val heal =
            when (stack.material()) {
                Material.GOLDEN_CARROT -> GOLDEN_CARROT_HEAL
                Material.GOLDEN_APPLE -> GOLDEN_APPLE_HEAL
                else -> return false
            }
        var used = false
        if (horse.health < data.maxHealth) {
            horse.health = (horse.health + heal).coerceAtMost(data.maxHealth.toFloat())
            used = true
        }
        if (!data.isFoal && data.breedCooldownMs <= 0 && data.loveRemainingMs <= 0) {
            data.loveRemainingMs = LOVE_MS
            showHearts(horse)
            used = true
        }
        return used
    }

    fun isSaddled(horse: EntityCreature): Boolean = horses[horse]?.saddled == true

    /** Returns true when a saddle was put on the horse. */
    fun saddle(horse: EntityCreature): Boolean {
        val data = horses[horse] ?: return false
        if (data.saddled || data.isFoal) return false
        data.saddled = true
        horse.setEquipment(EquipmentSlot.SADDLE, ItemStack.of(Material.SADDLE))
        // Saved now so a crash can't separate the saddle from the player's saved inventory.
        save()
        return true
    }

    /** Returns true when a saddle was taken off the horse. */
    fun unsaddle(horse: EntityCreature): Boolean {
        val data = horses[horse] ?: return false
        if (!data.saddled || horse.hasPassenger()) return false
        data.saddled = false
        horse.setEquipment(EquipmentSlot.SADDLE, ItemStack.AIR)
        save()
        return true
    }

    enum class MountResult {
        MOUNTED,
        FOAL,
        NO_SADDLE,
        OCCUPIED,
        COOLDOWN,
    }

    fun mount(
        player: Player,
        horse: EntityCreature,
    ): MountResult {
        val data = horses[horse] ?: return MountResult.OCCUPIED
        if (data.isFoal) return MountResult.FOAL
        if (!data.saddled) return MountResult.NO_SADDLE
        if (horse.hasPassenger() || player.vehicle != null) return MountResult.OCCUPIED
        if (mountCooldownMillis(player) > 0) return MountResult.COOLDOWN
        horse.velocity = Vec.ZERO
        setClientControlled(horse, true)
        horse.addPassenger(player)
        data.rider = player
        return MountResult.MOUNTED
    }

    /**
     * The rider's client simulates a ridden horse. Server gravity would drag it down between its
     * moves and the periodic zero-velocity sync would reset the client's momentum, so both are off
     * while ridden. The no-gravity metadata flag can't be used: it reaches the client and leaves
     * the horse floating, slow and unable to jump.
     */
    private fun setClientControlled(
        horse: EntityCreature,
        controlled: Boolean,
    ) {
        horse.aerodynamics = horse.aerodynamics.withGravity(if (controlled) 0.0 else GRAVITY)
        horse.synchronizationTicks = if (controlled) Int.MAX_VALUE.toLong() else ServerFlag.ENTITY_SYNCHRONIZATION_TICKS.toLong()
        horse.synchronizeNextTick()
    }

    fun dismount(player: Player) {
        val horse = player.vehicle as? EntityCreature ?: return
        val data = horses[horse] ?: return
        horse.removePassenger(player)
        riderLeft(horse, data)
    }

    /** Also runs when the rider left some other way, e.g. by dying or with their horse. */
    private fun riderLeft(
        horse: EntityCreature,
        data: HorseData,
    ) {
        val player = data.rider ?: return
        data.rider = null
        setClientControlled(horse, false)
        if (player.openInventory is StatsInventory) player.closeInventory()
        // Like vehicle exits, the teleport tells movement checks the rider moved with the horse.
        if (player.isOnline && !player.isDead) player.teleport(horse.position.withView(player.position.yaw, player.position.pitch))
        blockMounting(player)
    }

    fun blockMounting(player: Player) {
        mountBlockedUntil[player.uuid] = System.currentTimeMillis() + Vanilla.config.horseMountCooldownMs
    }

    fun mountCooldownMillis(player: Player): Long {
        val until = mountBlockedUntil[player.uuid] ?: return 0
        val remaining = until - System.currentTimeMillis()
        if (remaining <= 0) mountBlockedUntil.remove(player.uuid, until)
        return remaining.coerceAtLeast(0)
    }

    fun forget(player: Player) {
        mountBlockedUntil.remove(player.uuid)
    }

    /** Drops the saddle and vanilla leather of a horse that died. */
    fun onDeath(horse: EntityCreature) {
        val data = horses.remove(horse) ?: return
        riderLeft(horse, data)
        val instance = horse.instance ?: return
        val position = horse.position
        if (data.saddled) Items.spawn(instance, position, ItemStack.of(Material.SADDLE))
        val leather = Random.nextInt(3)
        if (leather > 0 && !data.isFoal) Items.spawn(instance, position, ItemStack.of(Material.LEATHER, leather))
        // Saved now so a crash can't bring the horse back next to the saddle it dropped.
        save()
    }

    // ================
    // STATS
    // ================

    private class StatsInventory : Inventory(InventoryType.CHEST_1_ROW, Component.text("Horse"))

    fun isStatsInventory(inventory: AbstractInventory): Boolean = inventory is StatsInventory

    /** Shows the ridden horse's stats in a read-only row above the rider's own inventory. */
    fun openStats(player: Player) {
        val horse = player.vehicle as? EntityCreature ?: return
        val data = horses[horse] ?: return
        val inventory = StatsInventory()
        // Filling the row leaves shift-clicks nowhere to put the rider's items.
        val filler = ItemStack.of(Material.GRAY_STAINED_GLASS_PANE).withCustomName(Component.empty())
        repeat(inventory.size) { inventory.setItemStack(it, filler) }
        inventory.setItemStack(
            4,
            ItemStack
                .of(Material.SADDLE)
                .withCustomName(Component.text("Horse", NamedTextColor.GOLD))
                .withLore(
                    listOf(
                        "Health: %.1f / %.1f".format(horse.health, data.maxHealth),
                        "Speed: %.1f blocks/s".format(data.speed * BLOCKS_PER_SECOND_PER_SPEED),
                        "Jump: %.1f blocks".format(jumpHeight(data.jump)),
                    ).map { Component.text(it, NamedTextColor.GRAY) },
                ),
        )
        player.openInventory(inventory)
    }

    /** Peak height of a full-power jump, stepping vanilla's per-tick jump physics. */
    private fun jumpHeight(strength: Double): Double {
        var velocity = strength
        var height = 0.0
        while (velocity > 0) {
            height += velocity
            velocity = (velocity - GRAVITY) * 0.98
        }
        return height
    }

    // ================
    // BREEDING
    // ================

    private fun tick() {
        horses.keys.removeIf { it.isRemoved }
        horses.forEach { (horse, data) ->
            // Riders can leave without dismount(), e.g. by entering a vehicle or dying.
            if (!horse.hasPassenger()) riderLeft(horse, data)
            if (data.breedCooldownMs > 0) data.breedCooldownMs = (data.breedCooldownMs - TICK_MS).coerceAtLeast(0)
            if (data.isFoal) {
                data.growthRemainingMs = (data.growthRemainingMs - TICK_MS).coerceAtLeast(0)
                if (!data.isFoal) horse.editEntityMeta(HorseMeta::class.java) { it.isBaby = false }
            }
            if (data.loveRemainingMs > 0) {
                data.loveRemainingMs = (data.loveRemainingMs - TICK_MS).coerceAtLeast(0)
                showHearts(horse)
            }
        }

        val inLove = horses.entries.filter { (horse, data) -> data.loveRemainingMs > 0 && !horse.isDead }
        val bred = HashSet<EntityCreature>()
        for ((horse, data) in inLove) {
            if (horse in bred) continue
            // Caps farms: crowded horses stay in love without breeding.
            if (isCrowded(horse)) continue
            val partner =
                inLove.firstOrNull { (other, _) ->
                    other !== horse &&
                        other !in bred &&
                        other.instance === horse.instance &&
                        other.position.distance(horse.position) <= BREED_RANGE
                } ?: continue
            bred += horse
            bred += partner.key
            breed(horse, data, partner.key, partner.value)
        }
    }

    private fun isCrowded(horse: EntityCreature): Boolean =
        horses.keys.count { it.instance === horse.instance && it.position.distance(horse.position) <= CROWD_RANGE } >= CROWD_LIMIT

    private fun breed(
        first: EntityCreature,
        firstData: HorseData,
        second: EntityCreature,
        secondData: HorseData,
    ) {
        val instance = first.instance ?: return
        listOf(firstData, secondData).forEach {
            it.loveRemainingMs = 0
            it.breedCooldownMs = BREED_COOLDOWN_MS
        }
        val foal =
            HorseData(
                maxHealth = offspringStat(firstData.maxHealth, secondData.maxHealth, HEALTH_RANGE),
                speed = offspringStat(firstData.speed, secondData.speed, SPEED_RANGE),
                jump = offspringStat(firstData.jump, secondData.jump, JUMP_RANGE),
                variant = inherit(firstData.variant, secondData.variant, HorseMeta.Variant.entries),
                marking = inherit(firstData.marking, secondData.marking, HorseMeta.Marking.entries),
                growthRemainingMs = GROWTH_MS,
            )
        // The midpoint between the parents can be inside a wall.
        spawn(instance, first.position, foal, foal.maxHealth.toFloat())
        showHearts(first)
        showHearts(second)
        save()
    }

    /** Vanilla's offspring formula: the parents' average plus a spread that narrows as they agree. */
    private fun offspringStat(
        first: Double,
        second: Double,
        range: ClosedFloatingPointRange<Double>,
    ): Double {
        val a = first.coerceIn(range)
        val b = second.coerceIn(range)
        val spread = abs(a - b) + (range.endInclusive - range.start) * 0.3
        val offset = (Random.nextDouble() + Random.nextDouble() + Random.nextDouble()) / 3.0 - 0.5
        val value = (a + b) / 2.0 + spread * offset
        val bounced =
            when {
                value > range.endInclusive -> 2 * range.endInclusive - value
                value < range.start -> 2 * range.start - value
                else -> value
            }
        return bounced.coerceIn(range)
    }

    // Like vanilla, each trait comes from a parent most of the time and is random otherwise.
    private fun <T> inherit(
        first: T,
        second: T,
        all: List<T>,
    ): T =
        when (Random.nextInt(9)) {
            in 0..3 -> first
            in 4..7 -> second
            else -> all.random()
        }

    private fun showHearts(horse: EntityCreature) {
        val instance = horse.instance ?: return
        val position = horse.position.add(0.0, horse.eyeHeight + 0.5, 0.0)
        instance.sendGroupedPacket(ParticlePacket(Particle.HEART, position, Vec(0.4, 0.3, 0.4), 0f, 2))
    }

    // ================
    // PERSISTENCE
    // ================

    /** Takes horses about to be removed with their chunk out of the world until it loads again. */
    fun onChunkUnload(
        instance: Instance,
        chunkX: Int,
        chunkZ: Int,
    ) {
        if (instance !== world) return
        horses.entries
            .filter { (horse, _) -> horse.instance === instance && horse.chunkKey() == ChunkKey(chunkX, chunkZ) }
            .forEach { (horse, data) ->
                horses.remove(horse)
                horse.passengers.forEach(horse::removePassenger)
                riderLeft(horse, data)
                dormant.getOrPut(ChunkKey(chunkX, chunkZ)) { mutableListOf() }.add(snapshot(horse, data))
            }
    }

    fun onChunkLoad(
        instance: Instance,
        chunkX: Int,
        chunkZ: Int,
    ) {
        if (instance !== world) return
        // Anvil loads chunks on virtual threads, so spawn on the next tick like ItemFrames does.
        // Horses stay dormant until then in case the chunk unloads again first.
        ModuleScheduler.scheduleNextTick {
            if (instance.isChunkLoaded(chunkX, chunkZ)) dormant.remove(ChunkKey(chunkX, chunkZ))?.forEach(::restore)
        }
    }

    fun prepareForShutdown() {
        horses.keys.forEach { horse ->
            horse.passengers.filterIsInstance<Player>().forEach(::dismount)
        }
    }

    fun save() {
        if (!::file.isInitialized) return
        val live = horses.entries.filter { (horse, _) -> horse.instance === world && !horse.isDead }.map { (h, d) -> snapshot(h, d) }
        val saved = SavedHorses(FORMAT_VERSION, live + dormant.values.flatMap { it.toList() })
        AtomicFiles.write(file) { writer -> writer.write(Json.encodeToString(saved)) }
    }

    fun shutdown() {
        tickTask?.cancel()
        tickTask = null
        horses.keys.forEach { horse ->
            horse.passengers.forEach(horse::removePassenger)
            horse.remove()
        }
        horses.clear()
        dormant.clear()
        mountBlockedUntil.clear()
    }

    private fun load() {
        if (!Files.exists(file)) return
        val saved =
            try {
                Json.decodeFromString<SavedHorses>(Files.readString(file))
            } catch (exception: Exception) {
                throw IllegalStateException("Failed to load horses from $file", exception)
            }
        require(saved.version == FORMAT_VERSION) { "Unsupported horse save version ${saved.version} in $file" }
        saved.horses.forEach { horse ->
            if (!horse.x.isFinite() || !horse.y.isFinite() || !horse.z.isFinite()) {
                System.err.println("Ignoring horse with an invalid position in $file")
                return@forEach
            }
            val key = ChunkKey(Math.floorDiv(floor(horse.x).toInt(), 16), Math.floorDiv(floor(horse.z).toInt(), 16))
            if (world.isChunkLoaded(key.x, key.z)) {
                restore(horse)
            } else {
                dormant.getOrPut(key) { mutableListOf() }.add(horse)
            }
        }
    }

    private fun restore(saved: SavedHorse) {
        val data =
            HorseData(
                maxHealth = saved.maxHealth.coerceIn(HEALTH_RANGE),
                speed = saved.speed.coerceIn(SPEED_RANGE),
                jump = saved.jump.coerceIn(JUMP_RANGE),
                variant = HorseMeta.Variant.entries.firstOrNull { it.name == saved.variant } ?: HorseMeta.Variant.BROWN,
                marking = HorseMeta.Marking.entries.firstOrNull { it.name == saved.marking } ?: HorseMeta.Marking.NONE,
                saddled = saved.saddled,
                growthRemainingMs = saved.growthRemainingMs.coerceIn(0, GROWTH_MS),
                breedCooldownMs = saved.breedCooldownMs.coerceIn(0, BREED_COOLDOWN_MS),
            )
        spawn(world, Pos(saved.x, saved.y, saved.z, saved.yaw, 0f), data, saved.health)
    }

    private fun snapshot(
        horse: EntityCreature,
        data: HorseData,
    ): SavedHorse {
        val position = horse.position
        return SavedHorse(
            x = position.x,
            y = position.y,
            z = position.z,
            yaw = position.yaw,
            health = horse.health,
            maxHealth = data.maxHealth,
            speed = data.speed,
            jump = data.jump,
            variant = data.variant.name,
            marking = data.marking.name,
            saddled = data.saddled,
            growthRemainingMs = data.growthRemainingMs,
            breedCooldownMs = data.breedCooldownMs,
        )
    }

    private fun EntityCreature.chunkKey() = ChunkKey(Math.floorDiv(position.blockX(), 16), Math.floorDiv(position.blockZ(), 16))
}
