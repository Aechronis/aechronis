/**
 * Instance for attacking a chunk. Holds the state for a flag attack; FlagWar
 * owns the single scheduler that advances all active attacks.
 */
package net.aechronis.nodes.war

import net.aechronis.nodes.Nodes
import net.aechronis.nodes.constants.DiplomaticRelationship
import net.aechronis.nodes.objects.Coord
import net.aechronis.nodes.objects.Resident
import net.aechronis.nodes.objects.Territory
import net.aechronis.nodes.objects.Town
import net.aechronis.nodes.objects.townNametagViewedByPlayer
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.BlockVec
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.Entity
import net.minestom.server.entity.EntityType
import net.minestom.server.entity.Player
import net.minestom.server.entity.metadata.display.AbstractDisplayMeta
import net.minestom.server.entity.metadata.display.TextDisplayMeta
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class Attack(
    val attacker: UUID,
    val town: Town,
    val coord: Coord,
    val targetTerritory: Territory,
    val flagBase: BlockVec,
    val flagBlock: BlockVec,
    val flagTorch: BlockVec,
    val skyBeaconColorBlocks: List<BlockVec>,
    val skyBeaconWireframeBlocks: List<BlockVec>,
    val progressBar: BossBar,
    val attackTime: Long,
    var progress: Long,
    val mode: AttackMode = AttackMode.WAR,
) {
    private val ended = AtomicBoolean(false)

    private var completionTimeMillis: Long = System.currentTimeMillis() + (attackTime - progress) * 50L

    // The town that owned the target when this attack was created.
    val targetTown: Town? = targetTerritory.town

    val noBuildXMin: Int
    val noBuildXMax: Int
    val noBuildZMin: Int
    val noBuildZMax: Int
    val noBuildYMin: Int
    val noBuildYMax: Int = 255 // temporarily set to height

    // Text displays are shared by diplomatic relationship, not duplicated for
    // every player. At most five display entities are created per attack.
    val textDisplay = AttackTextDisplay(this, flagBase.add(0.5, 3.0, 0.5).asPos())

    // Re-used JSON serialization builders.
    val jsonStringBase: StringBuilder
    val jsonString: StringBuilder

    init {
        val flagX = flagBase.blockX
        val flagY = flagBase.blockY
        val flagZ = flagBase.blockZ
        noBuildXMin = flagX - Nodes.config.flagNoBuildDistance
        noBuildXMax = flagX + Nodes.config.flagNoBuildDistance
        noBuildZMin = flagZ - Nodes.config.flagNoBuildDistance
        noBuildZMax = flagZ + Nodes.config.flagNoBuildDistance
        noBuildYMin = flagY + Nodes.config.flagNoBuildYOffset

        progressBar.progress(progress.toFloat() / attackTime.toFloat())
        jsonStringBase = generateFixedJsonBase(attacker, coord, flagBase, mode)
        jsonString = StringBuilder(jsonStringBase.length + 20)
    }

    fun cancel() = FlagWar.cancelAttack(this)

    internal fun markEnded(): Boolean = ended.compareAndSet(false, true)

    internal fun updateProgressFromClock(nowMillis: Long = System.currentTimeMillis()): Long {
        val remainingMillis = (completionTimeMillis - nowMillis).coerceAtLeast(0)
        val remainingTicks = (remainingMillis * 20L + 999L) / 1000L
        progress = (attackTime - remainingTicks).coerceIn(0, attackTime)
        return progress
    }

    internal fun restoreCompletionTime(completionTimeSeconds: Long) {
        completionTimeMillis = completionTimeSeconds * 1000L
        updateProgressFromClock()
    }

    fun toJson(): StringBuilder {
        jsonString.setLength(0)
        jsonString.append(jsonStringBase)
        jsonString.append("\"t\":${completionTimeMillis / 1000L}")
        jsonString.append("}")
        return jsonString
    }
}

private fun generateFixedJsonBase(
    attacker: UUID,
    coord: Coord,
    block: BlockVec,
    mode: AttackMode,
): StringBuilder = StringBuilder().apply {
    append("{")
    append("\"id\":\"$attacker\",")
    append("\"c\":[${coord.x},${coord.z}],")
    append("\"b\":[${block.blockX},${block.blockY},${block.blockZ}],")
    append("\"m\":\"${mode.name}\",")
}

class AttackTextDisplay(
    private val attack: Attack,
    private val loc: Pos,
) {
    private class DisplayState(
        val entity: Entity,
        var townNameText: String,
        val viewers: MutableSet<UUID> = hashSetOf(),
    )

    private val displays = mutableMapOf<DiplomaticRelationship, DisplayState>()
    private val playerRelationships = mutableMapOf<UUID, DiplomaticRelationship>()

    init {
        MinecraftServer.getConnectionManager().onlinePlayers.forEach(::update)
    }

    fun removePlayerTextDisplay(player: Player) {
        val relationship = playerRelationships.remove(player.uuid) ?: return
        val state = displays[relationship] ?: return
        if (state.viewers.remove(player.uuid)) updateViewers(state)
    }

    /** Assign a player to the display matching their current relationship. */
    fun update(player: Player) {
        val relationship = Town.relationshipOfTownToTown(attack.town, Resident.fromPlayer(player)?.town)
        val previous = playerRelationships.put(player.uuid, relationship)
        if (previous == relationship) return

        if (previous != null) {
            displays[previous]?.let { state ->
                if (state.viewers.remove(player.uuid)) updateViewers(state)
            }
        }

        val state = displays.getOrPut(relationship) {
            DisplayState(createTextDisplay(loc), townNametagViewedByPlayer(attack.town, player, false)).also {
                setTextDisplayText(it.entity, "${it.townNameText}\n${countdownText()}")
            }
        }
        state.viewers.add(player.uuid)
        updateViewers(state)
    }

    /** Refresh relationships after diplomacy or town-membership changes. */
    fun refreshPlayers() {
        val onlinePlayers = MinecraftServer.getConnectionManager().onlinePlayers
        val onlineIds = onlinePlayers.mapTo(hashSetOf()) { it.uuid }
        playerRelationships.keys.filter { it !in onlineIds }.toList().forEach { playerId ->
            val relationship = playerRelationships.remove(playerId) ?: return@forEach
            displays[relationship]?.let { state ->
                if (state.viewers.remove(playerId)) updateViewers(state)
            }
        }
        onlinePlayers.forEach { player ->
            update(player)
            playerRelationships[player.uuid]?.let { relationship ->
                displays[relationship]?.townNameText = townNametagViewedByPlayer(attack.town, player, false)
            }
        }
        updateProgress()
    }

    /** Update all currently-used relationship displays with one shared timer. */
    fun updateProgress() {
        val timeText = countdownText()
        displays.values.forEach { state -> setTextDisplayText(state.entity, "${state.townNameText}\n$timeText") }
    }

    private fun countdownText(): String {
        val remainingSeconds = (attack.attackTime - attack.progress) / 20
        return "%02d:%02d".format(remainingSeconds / 60, remainingSeconds % 60)
    }

    fun remove() {
        displays.values.forEach { it.entity.remove() }
        displays.clear()
        playerRelationships.clear()
    }

    private fun updateViewers(state: DisplayState) {
        state.entity.updateViewableRule { viewer -> viewer.uuid in state.viewers }
    }
}

private fun createTextDisplay(loc: Pos): Entity {
    val textDisplay = Entity(EntityType.TEXT_DISPLAY)
    textDisplay.setInstance(MinecraftServer.getInstanceManager().instances.first(), loc)
    textDisplay.setNoGravity(true)

    val meta = textDisplay.entityMeta
    if (meta is TextDisplayMeta) {
        meta.billboardRenderConstraints = AbstractDisplayMeta.BillboardConstraints.CENTER
        meta.backgroundColor = 0
    }
    return textDisplay
}

private fun setTextDisplayText(entity: Entity, text: String) {
    val meta = entity.entityMeta
    if (meta is TextDisplayMeta) meta.text = Component.text(text)
}
