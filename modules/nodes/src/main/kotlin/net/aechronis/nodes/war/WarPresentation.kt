package net.aechronis.nodes.war

import net.aechronis.nodes.Message
import net.aechronis.nodes.Nodes
import net.aechronis.nodes.objects.Coord
import net.aechronis.nodes.objects.Nation
import net.aechronis.nodes.objects.Resident
import net.aechronis.nodes.objects.Territory
import net.aechronis.nodes.objects.TerritoryChunk
import net.aechronis.nodes.objects.Town
import net.aechronis.nodes.utils.ChatColor
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import net.minestom.server.MinecraftServer
import net.minestom.server.command.CommandSender
import net.minestom.server.coordinate.BlockVec
import net.minestom.server.entity.Player
import net.minestom.server.instance.block.Block

private val SKY_BEACON_FRAME_BLOCK = Block.SEA_LANTERN
private val SKY_BEACON_BLOCK = Block.BLACK_WOOL
private val SKY_BEACON_BLOCKS: Set<Block> = setOf(
    SKY_BEACON_FRAME_BLOCK,
    SKY_BEACON_BLOCK,
)

/** Player-facing war messages, attack visuals, and batched minimap refreshes. */
internal class WarPresentation(private val state: FlagWarState) {
    // Core captures can cancel many flags; refresh all minimaps once afterward.
    private var minimapRefreshDeferrals: Int = 0
    private var minimapRefreshPending: Boolean = false

    fun printInfo(sender: CommandSender, detailed: Boolean = false) {
        val status = if (state.enabled) "enabled" else "${ChatColor.GRAY}disabled"
        Message.print(sender, "${ChatColor.BOLD}Nodes war status: $status")
        if (state.enabled) {
            val mode = when {
                state.isDeathWar -> "deathwar"
                state.canOnlyAttackBorders -> "skirmish"
                else -> "normal"
            }
            Message.print(sender, "- Mode${ChatColor.WHITE}: $mode")
            Message.print(sender, "- Can Annex Territories${ChatColor.WHITE}: ${state.canAnnexTerritories}")
            Message.print(sender, "- Can Only Attack Borders${ChatColor.WHITE}: ${state.canOnlyAttackBorders}")
            Message.print(sender, "- Destruction Enabled${ChatColor.WHITE}: ${state.destructionEnabled}")
            if (state.canOnlyAttackBorders) {
                Message.print(sender, "- Nations With Selected Targets${ChatColor.WHITE}: ${state.skirmishTargetsByNation.size}")
            }
            if (detailed) {
                Message.print(sender, "- Using Towns Whitelist${ChatColor.WHITE}: ${Nodes.config.warUseWhitelist}")
                Message.print(sender, "- Can leave town${ChatColor.WHITE}: ${Nodes.config.canLeaveTownDuringWar}")
            }
        }
    }

    fun warEnabled() {
        if (state.isDeathWar) {
            Message.broadcast("${ChatColor.DARK_RED}${ChatColor.BOLD}Nodes deathwar enabled")
        } else if (state.canOnlyAttackBorders) {
            Message.broadcast("${ChatColor.DARK_RED}${ChatColor.BOLD}Nodes border skirmishing enabled")
        } else {
            Message.broadcast("${ChatColor.DARK_RED}${ChatColor.BOLD}Nodes war enabled")
        }
    }

    fun createProgressBar(mode: AttackMode, territory: Territory, flagBase: BlockVec): BossBar {
        val flagBaseX = flagBase.blockX
        val flagBaseY = flagBase.blockY
        val flagBaseZ = flagBase.blockZ
        val action = when (mode) {
            AttackMode.COLONIZATION -> "Colonizing"
            AttackMode.WARZONE -> "Capturing warzone"
            AttackMode.WAR -> "Attacking"
        }
        return BossBar.bossBar(Component.text("$action ${territory.town!!.name} at ($flagBaseX, $flagBaseY, $flagBaseZ)"), 1f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS)
    }

    /**
     * Create/update a flag attack beacon. Coordinates are recorded so only
     * blocks owned by this attack are removed during cleanup.
     */
    fun createAttackBeacon(
        skyBeaconColorBlocks: MutableList<BlockVec>,
        skyBeaconWireframeBlocks: MutableList<BlockVec>,
        coord: Coord,
        flagBaseY: Int,
    ) {
        // get starting corner
        val size = state.skyBeaconSize
        val startPositionInChunk: Int = (16 - size) / 2
        val x0: Int = coord.x * 16 + startPositionInChunk
        val z0: Int = coord.z * 16 + startPositionInChunk
        val y0: Int = maxOf(flagBaseY + Nodes.config.flagBeaconSkyLevel, Nodes.config.flagBeaconMinSkyLevel)
        val xEnd: Int = x0 + size - 1
        val zEnd: Int = z0 + size - 1
        val yEnd: Int = minOf(255, y0 + size - 1) // beacon is size blocks high
        val instance = MinecraftServer.getInstanceManager().instances.first()

        for (y in y0..yEnd) {
            for (x in x0..xEnd) {
                for (z in z0..zEnd) {
                    val block = instance.getBlock(x, y, z)
                    if (block != Block.AIR && block !in SKY_BEACON_BLOCKS) continue

                    // Avoid allocating a coordinate unless this position will be changed.
                    val blockPos = BlockVec(x, y, z)
                    if (((y == y0 || y == yEnd) && (x == x0 || x == xEnd || z == z0 || z == zEnd)) ||
                        ((x == x0 || x == xEnd) && (z == z0 || z == zEnd))
                    ) {
                        skyBeaconWireframeBlocks.add(blockPos)
                        instance.setBlock(blockPos, SKY_BEACON_FRAME_BLOCK)
                    } else {
                        skyBeaconColorBlocks.add(blockPos)
                        instance.setBlock(blockPos, SKY_BEACON_BLOCK)
                    }
                }
            }
        }
    }

    fun clearAttackBlocks(attack: Attack) {
        val instance = MinecraftServer.getInstanceManager().instances.first()
        instance.setBlock(attack.flagTorch, Block.AIR)
        instance.setBlock(attack.flagBlock, Block.AIR)
        instance.setBlock(attack.flagBase, Block.AIR)
        attack.skyBeaconWireframeBlocks.forEach { instance.setBlock(it, Block.AIR) }
        attack.skyBeaconColorBlocks.forEach { instance.setBlock(it, Block.AIR) }
    }

    fun requestMinimapRefresh() {
        if (minimapRefreshDeferrals > 0) {
            minimapRefreshPending = true
        } else {
            Resident.renderMinimaps()
        }
    }

    fun deferMinimapRefresh(block: () -> Unit) {
        minimapRefreshDeferrals++
        try {
            block()
        } finally {
            minimapRefreshDeferrals--
            if (minimapRefreshDeferrals == 0 && minimapRefreshPending) {
                minimapRefreshPending = false
                Resident.renderMinimaps()
            }
        }
    }

    // Intended to run on PlayerJoin to restore a player's active progress bars.
    fun sendWarProgressBarToPlayer(player: Player) {
        val uuid = player.uuid

        val currentAttacks = state.attackers.get(uuid)
        if (currentAttacks != null) {
            for (attack in currentAttacks) {
                attack.progressBar.addViewer(player)
            }
        }
    }

    /** Re-evaluate display grouping after diplomacy or membership changes. */
    fun refreshAttackTextDisplays() {
        state.chunkToAttacker.values.forEach { it.textDisplay.refreshPlayers() }
    }

    fun skirmishTargetSelected(nation: Nation, territory: Territory) {
        Message.broadcast(
            "${ChatColor.DARK_RED}[War] ${nation.name} selected ${territory.name} " +
                "(id=${territory.id}) for this border skirmish!",
        )
    }

    fun liberatedTerritory(mode: AttackMode, attacker: Resident?, territory: Territory, occupier: Town?) {
        val messageContext = messageContext(mode)
        Message.broadcast("${ChatColor.DARK_RED}$messageContext ${attacker?.name} liberated territory (id=${territory.id}) from ${occupier?.name}!")
    }

    fun capturedTerritory(mode: AttackMode, attacker: Resident?, territory: Territory, formerTown: Town?) {
        val messageContext = messageContext(mode)
        val action = if (mode == AttackMode.COLONIZATION) "colonized" else "captured"
        Message.broadcast("${ChatColor.DARK_RED}$messageContext ${attacker?.name} $action territory (id=${territory.id}) from ${formerTown?.name}!")
    }

    fun townDefeated(
        outcome: TownDefeatOutcome,
        attackerTown: Town,
        defeatedTown: Town,
        defeatedTownName: String,
        attacker: Resident?,
    ) {
        when (outcome) {
            TownDefeatOutcome.ALREADY_DEFEATED_THIS_WAR -> Unit
            TownDefeatOutcome.LOST_LIFE -> Message.broadcast(
                "${ChatColor.DARK_RED}[Conquest] ${attackerTown.name} occupied all territories of " +
                    "$defeatedTownName, which lost a life and has " +
                    "${defeatedTown.lives} remaining; it cannot lose another life this war!",
            )
            TownDefeatOutcome.ANNEXED -> Message.broadcast(
                "${ChatColor.DARK_RED}[Conquest] ${attackerTown.name} occupied all territories of " +
                    "$defeatedTownName, which has ${defeatedTown.lives} lives remaining; " +
                    "${attacker?.name ?: attackerTown.name} made the decisive capture!",
            )
            TownDefeatOutcome.FINAL_LIFE_PROTECTED -> Message.broadcast(
                "${ChatColor.DARK_RED}[Conquest] $defeatedTownName was defeated but cannot be annexed " +
                    "during this war mode; it remains on its final life.",
            )
        }
    }

    fun liberatedChunk(mode: AttackMode, attacker: Resident?, chunk: TerritoryChunk, occupier: Town) {
        val messageContext = messageContext(mode)
        Message.broadcast("${ChatColor.DARK_RED}$messageContext ${attacker?.name} liberated chunk (${chunk.coord.x}, ${chunk.coord.z}) from ${occupier.name}!")
    }

    fun defendedChunk(mode: AttackMode, attacker: Resident?, chunk: TerritoryChunk, occupier: Town?) {
        val messageContext = messageContext(mode)
        Message.broadcast("${ChatColor.DARK_RED}$messageContext ${attacker?.name} defended chunk (${chunk.coord.x}, ${chunk.coord.z}) against ${occupier?.name}!")
    }

    fun capturedChunk(mode: AttackMode, attacker: Resident?, chunk: TerritoryChunk) {
        val messageContext = messageContext(mode)
        Message.broadcast("${ChatColor.DARK_RED}$messageContext ${attacker?.name} captured chunk (${chunk.coord.x}, ${chunk.coord.z}) from ${chunk.territory.town?.name}!")
    }

    private fun messageContext(mode: AttackMode): String = when (mode) {
        AttackMode.COLONIZATION -> "[Colonization]"
        AttackMode.WARZONE -> "[Warzone]"
        AttackMode.WAR -> "[War]"
    }
}
