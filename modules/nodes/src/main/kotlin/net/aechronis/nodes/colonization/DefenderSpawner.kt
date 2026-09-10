package net.aechronis.nodes.colonization

import net.aechronis.nodes.objects.Coord
import net.aechronis.nodes.objects.Territory
import net.aechronis.server.modules.ModuleScheduler
import net.aechronis.utils.EntityTags
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.component.DataComponents
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.EntityCreature
import net.minestom.server.entity.EntityType
import net.minestom.server.entity.EquipmentSlot
import net.minestom.server.entity.PlayerSkin
import net.minestom.server.entity.attribute.Attribute
import net.minestom.server.entity.metadata.avatar.MannequinMeta
import net.minestom.server.instance.Instance
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import net.minestom.server.network.player.ResolvableProfile
import kotlin.random.Random

private const val MAX_DEFENDER_SPAWNS_PER_WAVE = 4
private const val MIN_RANDOM_GUARD_COLUMNS = 32
private const val RANDOM_GUARD_COLUMNS_PER_DEFENDER = 8
internal const val MAX_DEFENDER_RESPAWN_DELAY_MILLIS = 60_000L
private const val AI_DEFENDER_SKIN_TEXTURES =
    "ewogICJ0aW1lc3RhbXAiIDogMTc4NjQ3ODMyNzM5NiwKICAicHJvZmlsZUlkIiA6ICJjMmFlZGUzN2M3MDQ0ZWFmOWJkYWYy" +
        "N2U2N2ZhZWRiZSIsCiAgInByb2ZpbGVOYW1lIiA6ICJKb25hc01hbm5lbjQ3IiwKICAic2lnbmF0dXJlUmVxdWlyZWQiIDog" +
        "dHJ1ZSwKICAidGV4dHVyZXMiIDogewogICAgIlNLSU4iIDogewogICAgICAidXJsIiA6ICJodHRwOi8vdGV4dHVyZXMubWlu" +
        "ZWNyYWZ0Lm5ldC90ZXh0dXJlL2U4MzMyNTQ3YWIwNjZlZWUyMWI5NTZhNDczZmNiZTA3MGY5OWJiOWQ2MGMxN2EzNzVlOTNh" +
        "ZWY2MGVlNjYyZGMiCiAgICB9CiAgfQp9"
private const val AI_DEFENDER_SKIN_SIGNATURE =
    "yXgHlIfkgQ89nu1LFIvhdQZtXxHC3TXznyRXEqGUVU0appvrnC7TdmMD5B+btO5TkRjpQOQi838c7STlGCA4gyfflSr9O+jb" +
        "ywQ6oNVVd/rhvI7pPn9QeA442eI1nnYNOH7qmoxIvYJmsfqdPAq8jm5or2Y6/TpP3ihwrW2smQl73Nagib4rHu1qkQesNS/g" +
        "2iB0xkCbTeA+nkfPY+JejgPx8qqF9YiQl2k3IOi97zEnkve/HkWIgPGDrIOAQ6SrJEuMJsRYuDBrdPks2jHi5ffbguv86q58" +
        "Mimup8FTPwPDdaAMo1Xapd7/3apspg9GCESB2yqao1KFtwtxli8nYQ0NmKdldGAEWjLHQ6FNc0tPJK0tW84PtKieFDT39oNZ" +
        "zBHufwhoV8SlAG5OUhLxz/vhNAXLKTRSmayWbRSuaPYOwa6E9OACeoNYC9qcZeRlNZ4pvpG8/YcSw1/qZdQOzf8y64K3lN1z" +
        "IGsZIDkmihnjVCJcJZ0T3OzeuBfUcLm3kTc8igF4TlfcOCfwZXz/YoqBWzkVgJYRL51oHcSaA/Fw+nDIzIQrfNE9Q+5e4d+l" +
        "+Wx6Ee0MzlfJ5rzhisxbSj1JgV1HT/SSPoN4GMXOXHu3byFudGi+BZM3zHZS7SKVg9qKPnF85I8ZNV6JwsXsb3MO/IhrtMvo" +
        "irRSKV30HbE="

internal val AI_DEFENDER_PROFILE = ResolvableProfile(
    PlayerSkin(AI_DEFENDER_SKIN_TEXTURES, AI_DEFENDER_SKIN_SIGNATURE),
)

/** Creates defender lives and coordinates terrain preparation, spawning, and respawning. */
internal class DefenderSpawner(private val sessions: DefenseSessions) {
    private val diamondPickaxe: ItemStack = ItemStack.of(Material.DIAMOND_PICKAXE)

    private fun createDefender(
        session: DefenseSession,
        slot: AiDefenderSlot,
        guardPosition: Pos,
    ): AiDefender {
        val entity = EntityCreature(EntityType.MANNEQUIN)
        val defenderName = Component.text("${session.targetTown.name} Defender", NamedTextColor.RED)
        entity.setTag(EntityTags.TRANSIENT_ENTITY, true)
        entity.setTag(EntityTags.DAMAGEABLE_MANNEQUIN, true)
        entity.getAttribute(Attribute.MAX_HEALTH).baseValue = 20.0
        entity.getAttribute(Attribute.MOVEMENT_SPEED).baseValue = 0.28
        entity.health = 20F
        entity.set(DataComponents.CUSTOM_NAME, defenderName)
        entity.isCustomNameVisible = true
        entity.editEntityMeta(MannequinMeta::class.java) { meta ->
            meta.profile = AI_DEFENDER_PROFILE
            meta.description = null
            meta.isImmovable = false
        }
        val motor = PlayerLikeGroundFollower(entity)
        entity.navigator.setNodeFollower { motor }
        entity.setEquipment(EquipmentSlot.MAIN_HAND, slot.gun.toItemStack())
        val weapon = DefenderWeapon(slot.gun)
        return AiDefender(
            entity = entity,
            brain = DefenderBrain(),
            motor = motor,
            navigation = DefenderNavigator(entity),
            weapon = weapon,
            blockBreaker = DefenderBlockBreaker(entity, weapon, diamondPickaxe),
            guardPosition = guardPosition,
        )
    }

    fun retireDeadDefenders(
        session: DefenseSession,
        now: Long,
    ) {
        var changed = false
        session.slots.forEach { slot ->
            val defender = slot.defender ?: return@forEach
            if (!defender.entity.isDead && !defender.entity.isRemoved) return@forEach

            defender.blockBreaker.stop(session.instance)
            defender.navigation.cancel()
            defender.navigation.clearChunks()
            if (!defender.entity.isRemoved) defender.entity.remove()
            slot.defender = null
            slot.spawnPending = false
            slot.generation += 1
            val delay = runCatching(session.respawnDelayMillis)
                .getOrDefault(MAX_DEFENDER_RESPAWN_DELAY_MILLIS)
                .coerceIn(0, MAX_DEFENDER_RESPAWN_DELAY_MILLIS)
            slot.respawnAtMillis = now + delay
            changed = true
        }
        if (changed) sessions.reconcileChunks(session)
    }

    fun prepareDueDefenderSpawns(
        session: DefenseSession,
        now: Long,
    ) {
        if (!sessions.isActive(session) || session.spawnPreparationInFlight) return
        val wave = session.slots
            .filter { slot -> slot.defender == null && !slot.spawnPending && slot.respawnAtMillis <= now }
            .take(MAX_DEFENDER_SPAWNS_PER_WAVE)
            .map { slot ->
                slot.spawnPending = true
                slot.generation += 1
                slot to slot.generation
            }
        if (wave.isEmpty()) return
        prepareLocalDefenderSpawns(session, wave)
    }

    private fun prepareLocalDefenderSpawns(
        session: DefenseSession,
        wave: List<Pair<AiDefenderSlot, Int>>,
    ) {
        val instance = session.instance
        val targetTown = session.targetTown
        session.spawnPreparationInFlight = true
        val candidates = localDefenderSpawnColumns(targetTown, session.spawnCenter)
            .filter(instance.worldBorder::inBounds)
        if (candidates.isEmpty()) {
            failSpawnPreparationWave(session, wave, "has no blocks available near its campaign base")
            return
        }
        val guardCandidates = coreTerritoryGuardColumns(
            targetTown = targetTown,
            referenceY = session.spawnCenter.y,
            maximumColumns = maxOf(MIN_RANDOM_GUARD_COLUMNS, wave.size * RANDOM_GUARD_COLUMNS_PER_DEFENDER),
        ).filter(instance.worldBorder::inBounds)
        if (guardCandidates.isEmpty()) {
            failSpawnPreparationWave(session, wave, "has no blocks available in its core territory")
            return
        }

        val candidateChunks = buildSet {
            candidates.mapTo(this) { Coord(it.chunkX(), it.chunkZ()) }
            guardCandidates.mapTo(this) { Coord(it.chunkX(), it.chunkZ()) }
            add(Coord(session.spawnCenter.chunkX(), session.spawnCenter.chunkZ()))
        }
        session.spawnPreparationChunks.addAll(candidateChunks)
        sessions.loadChunks(session, candidateChunks).whenComplete { _, loadError ->
            ModuleScheduler.scheduleNextTick {
                if (!sessions.isActive(session)) return@scheduleNextTick
                session.spawnPreparationInFlight = false
                session.spawnPreparationChunks.clear()
                if (loadError != null) {
                    failSpawnPreparationWave(
                        session,
                        wave,
                        "could not load campaign defender terrain: ${loadError.message}",
                    )
                    return@scheduleNextTick
                }

                val safeSpawns = candidates
                    .mapNotNull { horizontalPosition ->
                        if (!instance.worldBorder.inBounds(horizontalPosition)) return@mapNotNull null
                        if (Territory.fromBlock(horizontalPosition.blockX(), horizontalPosition.blockZ())?.town !== targetTown) {
                            return@mapNotNull null
                        }
                        resolveSafeSurfaceNear(instance, horizontalPosition, session.spawnCenter.blockY())
                    }.distinctBy { spawn -> Triple(spawn.blockX(), spawn.blockY(), spawn.blockZ()) }
                    .filter { spawn ->
                        activeDefenders(session).none { defender ->
                            !defender.entity.isRemoved && defender.entity.position.distanceSquared(spawn) < 4.0
                        }
                    }
                if (safeSpawns.isEmpty()) {
                    failSpawnPreparationWave(session, wave, "has no safe defender spawns near its campaign base")
                    return@scheduleNextTick
                }
                val safeGuardPositions = guardCandidates
                    .mapNotNull { horizontalPosition ->
                        val territory = Territory.fromBlock(horizontalPosition.blockX(), horizontalPosition.blockZ())
                        if (territory?.id != targetTown.home || territory.town !== targetTown) return@mapNotNull null
                        resolveSafeGuardSurface(instance, horizontalPosition, session.spawnCenter.blockY())
                    }.distinctBy { position -> Triple(position.blockX(), position.blockY(), position.blockZ()) }
                    .filter { position ->
                        activeDefenders(session).none { defender ->
                            defender.guardPosition.distanceSquared(position) < 4.0
                        }
                    }
                if (safeGuardPositions.isEmpty()) {
                    failSpawnPreparationWave(session, wave, "has no safe guard positions in its core territory")
                    return@scheduleNextTick
                }

                val assignmentCount = minOf(wave.size, safeSpawns.size, safeGuardPositions.size)
                val assignedWave = (0 until assignmentCount).map { index ->
                    Triple(wave[index], safeSpawns[index], safeGuardPositions[index])
                }
                val retryAt = System.currentTimeMillis() + PATH_RETRY_MILLIS
                wave.drop(assignedWave.size).forEach { (slot, generation) ->
                    if (slot.generation == generation && slot.defender == null) {
                        slot.spawnPending = false
                        slot.respawnAtMillis = retryAt
                    }
                }
                assignedWave.forEach { (slotWithGeneration, spawn, guardPosition) ->
                    val (slot, generation) = slotWithGeneration
                    if (slot.generation != generation || !slot.spawnPending || slot.defender != null) {
                        return@forEach
                    }
                    slot.spawnPending = false
                    val defender = createDefender(session, slot, guardPosition)
                    slot.defender = defender
                    defender.navigation.retainChunkAt(spawn)
                    prepareDefenderSpawn(instance, session, slot, generation, defender, spawn)
                }
                sessions.reconcileChunks(session)
            }
        }
    }

    private fun failSpawnPreparationWave(
        session: DefenseSession,
        wave: List<Pair<AiDefenderSlot, Int>>,
        reason: String,
    ) {
        if (!sessions.isActive(session)) return
        session.spawnPreparationInFlight = false
        session.spawnPreparationChunks.clear()
        System.err.println("[Nodes] AI town ${session.targetTown.name} $reason; retrying defender spawn")
        val retryAt = System.currentTimeMillis() + PATH_RETRY_MILLIS
        wave.forEach { (slot, generation) ->
            if (slot.generation == generation && slot.defender == null) {
                slot.spawnPending = false
                slot.respawnAtMillis = retryAt
            }
        }
        sessions.reconcileChunks(session)
    }

    private fun prepareDefenderSpawn(
        instance: Instance,
        session: DefenseSession,
        slot: AiDefenderSlot,
        generation: Int,
        defender: AiDefender,
        spawn: Pos,
    ) {
        val spawnChunk = Coord(spawn.chunkX(), spawn.chunkZ())
        sessions.loadChunks(session, listOf(spawnChunk)).whenComplete { _, loadError ->
            ModuleScheduler.scheduleNextTick {
                if (loadError != null) {
                    failDefenderSpawn(session, slot, generation, defender, "failed to load campaign spawn chunk: ${loadError.message}")
                    return@scheduleNextTick
                }
                if (!isCurrentDefenderLife(session, slot, generation, defender)) {
                    if (!defender.entity.isRemoved && defender.entity.instance != null) defender.entity.remove()
                    return@scheduleNextTick
                }

                if (
                    Territory.fromBlock(spawn.blockX(), spawn.blockZ())?.town !== session.targetTown ||
                    !isSafeDefenderStandingPosition(instance, spawn)
                ) {
                    failDefenderSpawn(session, slot, generation, defender, "campaign spawn became unsafe at $spawn")
                    return@scheduleNextTick
                }

                val spawnFuture = defender.entity.setInstance(instance, spawn)
                if (spawnFuture == null) {
                    failDefenderSpawn(session, slot, generation, defender, "campaign spawn could not start at $spawn")
                    return@scheduleNextTick
                }
                spawnFuture.whenComplete { _, spawnError ->
                    ModuleScheduler.scheduleNextTick {
                        if (spawnError != null) {
                            failDefenderSpawn(session, slot, generation, defender, "campaign spawn failed: ${spawnError.message}")
                        } else if (isCurrentDefenderLife(session, slot, generation, defender)) {
                            defender.ready = true
                        } else if (!defender.entity.isRemoved) {
                            defender.entity.remove()
                        }
                    }
                }
            }
        }
    }

    private fun isCurrentDefenderLife(
        session: DefenseSession,
        slot: AiDefenderSlot,
        generation: Int,
        defender: AiDefender,
    ): Boolean = sessions.isActive(session) && slot.generation == generation && slot.defender === defender

    private fun failDefenderSpawn(
        session: DefenseSession,
        slot: AiDefenderSlot,
        generation: Int,
        defender: AiDefender,
        reason: String,
    ) {
        if (!isCurrentDefenderLife(session, slot, generation, defender)) return
        System.err.println("[Nodes] AI defender $reason; retrying spawn")
        defender.navigation.cancel()
        defender.navigation.clearChunks()
        if (!defender.entity.isRemoved && defender.entity.instance != null) defender.entity.remove()
        slot.defender = null
        slot.spawnPending = false
        slot.generation += 1
        slot.respawnAtMillis = System.currentTimeMillis() + PATH_RETRY_MILLIS
        sessions.reconcileChunks(session)
    }
}

internal fun randomDefenderRespawnDelayMillis(random: Random = Random.Default): Long = random.nextLong(
    MAX_DEFENDER_RESPAWN_DELAY_MILLIS + 1,
)
