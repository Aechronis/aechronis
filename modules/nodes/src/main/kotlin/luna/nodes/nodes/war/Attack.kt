/**
 * Instance for attacking a chunk
 * - holds state data of attack
 * - functions as runnable thread for attack tick
 */

package luna.nodes.nodes.war

import luna.nodes.nodes.Nodes
import luna.nodes.nodes.objects.Coord
import luna.nodes.nodes.objects.Town
import luna.nodes.nodes.objects.townNametagViewedByPlayer
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
import net.minestom.server.timer.Task
import net.minestom.server.timer.TaskSchedule
import java.util.UUID

class Attack(
    val attacker: UUID, // attacker's UUID
    val town: Town, // attacker's town
    val coord: Coord, // chunk coord under attack
    val flagBase: BlockVec, // fence base of flag
    val flagBlock: BlockVec, // wool block for flag
    val flagTorch: BlockVec, // torch block of flag
    val skyBeaconColorBlocks: List<BlockVec>,
    val skyBeaconWireframeBlocks: List<BlockVec>,
    val progressBar: BossBar, // progress bar
    val attackTime: Long, //
    var progress: Long, // initial progress, current tick count
) : Runnable {
    // no build region
    val noBuildXMin: Int
    val noBuildXMax: Int
    val noBuildZMin: Int
    val noBuildZMax: Int
    val noBuildYMin: Int
    val noBuildYMax: Int = 255 // temporarily set to height

    var thread: Task = MinecraftServer.getSchedulerManager()
        .buildTask { this.run() }
        .delay(TaskSchedule.tick(FlagWar.ATTACK_TICK))
        .repeat(TaskSchedule.tick(FlagWar.ATTACK_TICK))
        .schedule()

    // text display used to show town name and progress on flag
    val textDisplay = AttackTextDisplay(this, flagBase.add(0.5, 3.0, 0.5).asPos())

    // re-used json serialization StringBuilders
    val jsonStringBase: StringBuilder
    val jsonString: StringBuilder

    init {
        val flagX = flagBase.blockX
        val flagY = flagBase.blockY
        val flagZ = flagBase.blockZ

        // set no build ranges
        this.noBuildXMin = flagX - Nodes.config.flagNoBuildDistance
        this.noBuildXMax = flagX + Nodes.config.flagNoBuildDistance
        this.noBuildZMin = flagZ - Nodes.config.flagNoBuildDistance
        this.noBuildZMax = flagZ + Nodes.config.flagNoBuildDistance
        this.noBuildYMin = flagY + Nodes.config.flagNoBuildYOffset

        // set boss bar progress
        val progressNormalized: Float = this.progress.toFloat() / this.attackTime.toFloat()
        this.progressBar.progress(progressNormalized)

        // pre-generate main part of the JSON serialization string
        this.jsonStringBase = generateFixedJsonBase(
            this.attacker,
            this.coord,
            this.flagBase,
        )

        // full json StringBuilder, initialize capacity to be
        // base capacity + room for progress ticks length
        val jsonStringBufferSize = this.jsonStringBase.capacity() + 20
        this.jsonString = StringBuilder(jsonStringBufferSize)
    }

    override fun run() {
        FlagWar.attackTick(this)
    }

    fun cancel() {
        this.thread.cancel()
        FlagWar.cancelAttack(this)
    }

    // returns json format string as a StringBuilder
    // only used with WarSerializer objects
    fun toJson(): StringBuilder {
        // reset json StringBuilder
        this.jsonString.setLength(0)

        // add base
        this.jsonString.append(this.jsonStringBase)

        // add progress in ticks
        this.jsonString.append("\"p\":${this.progress}")
        this.jsonString.append("}")

        return this.jsonString
    }
}

// pre-generate main part of the JSON serialization string
// for the attack which does not change
// (only part that changes is progress)
// parts required for serialization:
// - attacker: player uuid
// - coord: chunk coord
// - block: flag base block (fence)
// - skyBeaconColorBlocks: track blocks in sky beacon
// - skyBeaconWireframeBlocks: track blocks in sky beacon
private fun generateFixedJsonBase(
    attacker: UUID,
    coord: Coord,
    block: BlockVec,
): StringBuilder {
    val s = StringBuilder()

    s.append("{")

    // attacker uuid
    s.append("\"id\":\"$attacker\",")

    // chunk coord [c.x, c.z]
    s.append("\"c\":[${coord.x},${coord.z}],")

    // flag base block [b.x, b.y, b.z]
    s.append("\"b\":[${block.blockX},${block.blockY},${block.blockZ}],")

    return s
}

class AttackTextDisplay(
    val attack: Attack,
    val loc: Pos,
) {
    // per-player displays
    val playerTextDisplays: MutableMap<UUID, Entity> = mutableMapOf()

    init {
        // create textdisplays for all online players
        for (player in MinecraftServer.getConnectionManager().onlinePlayers) {
            update(player)
        }
    }

    /**
     * Remove a player's TextDisplay.
     */
    fun removePlayerTextDisplay(player: Player) {
        // remove the display from the map
        val display = playerTextDisplays.remove(player.uuid)

        // remove the actual display
        display?.remove()
    }

    /**
     * Update the progress text display with current timer.
     */
    fun update(player: Player) {
        var textDisplay = playerTextDisplays[player.uuid]

        // create display
        if (textDisplay == null) {
            textDisplay = createTextDisplay(loc)
            playerTextDisplays[player.uuid] = textDisplay
        }

        // set viewable rule so only this player can see it
        textDisplay.updateViewableRule { viewer -> viewer == player }

        val remainingTicks = attack.attackTime - attack.progress
        val remainingSeconds = remainingTicks / 20
        val minutes = remainingSeconds / 60
        val seconds = remainingSeconds % 60

        val timeText = String.format("%02d:%02d", minutes, seconds)
        val townNameText = townNametagViewedByPlayer(attack.town, player, false)
        val text = "$townNameText\n$timeText"

        setTextDisplayText(textDisplay, text)
    }

    /**
     * Remove all entities, for cleanup.
     */
    fun remove() {
        // Remove all per-player town name displays
        for (display in playerTextDisplays.values) {
            display.remove()
        }
        playerTextDisplays.clear()
    }
}

/**
 * Create a new textDisplay with associated metadata.
 */
private fun createTextDisplay(
    loc: Pos,
): Entity {
    val textDisplay = Entity(EntityType.TEXT_DISPLAY)
    textDisplay.setInstance(MinecraftServer.getInstanceManager().instances.first(), loc)
    textDisplay.setNoGravity(true)

    // Set billboard mode to CENTER so the text always faces the player
    val meta = textDisplay.entityMeta
    if (meta is TextDisplayMeta) {
        meta.billboardRenderConstraints = AbstractDisplayMeta.BillboardConstraints.CENTER // face player
        meta.backgroundColor = 0 // invisible bg
    }

    return textDisplay
}

/**
 * Helper function to set text on a TEXT_DISPLAY entity.
 */
private fun setTextDisplayText(entity: Entity, text: String) {
    val meta = entity.entityMeta
    if (meta is TextDisplayMeta) {
        meta.text = Component.text(text)
    }
}
