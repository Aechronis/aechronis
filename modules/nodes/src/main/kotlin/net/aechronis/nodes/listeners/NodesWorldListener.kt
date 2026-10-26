/**
 * Main listener for Nodes world:
 * - town permissions and protections
 * - flag war events
 * - hidden ore
 * - ore taxation
 */

package net.aechronis.nodes.listeners

import net.aechronis.economy.MiningBoostManager
import net.aechronis.nodes.Message
import net.aechronis.nodes.Nodes
import net.aechronis.nodes.colonization.Colonization
import net.aechronis.nodes.colonization.canStartColonization
import net.aechronis.nodes.constants.DiplomaticRelationship
import net.aechronis.nodes.constants.ErrorAlreadyCaptured
import net.aechronis.nodes.constants.ErrorAlreadyUnderAttack
import net.aechronis.nodes.constants.ErrorAnnexDisabled
import net.aechronis.nodes.constants.ErrorChunkNotEdge
import net.aechronis.nodes.constants.ErrorFlagTooHigh
import net.aechronis.nodes.constants.ErrorNoTerritory
import net.aechronis.nodes.constants.ErrorNotBorderTerritory
import net.aechronis.nodes.constants.ErrorNotEnemy
import net.aechronis.nodes.constants.ErrorSkirmishNationRequired
import net.aechronis.nodes.constants.ErrorSkirmishTargetLocked
import net.aechronis.nodes.constants.ErrorSkirmishTargetSelectionRole
import net.aechronis.nodes.constants.ErrorSkyBlocked
import net.aechronis.nodes.constants.ErrorTooManyAttacks
import net.aechronis.nodes.constants.ErrorTownBlacklisted
import net.aechronis.nodes.constants.ErrorTownNotWhitelisted
import net.aechronis.nodes.constants.PROTECTED_BLOCKS
import net.aechronis.nodes.objects.Coord
import net.aechronis.nodes.objects.Resident
import net.aechronis.nodes.objects.Territory
import net.aechronis.nodes.objects.TerritoryChunk
import net.aechronis.nodes.objects.Town
import net.aechronis.nodes.permissions.LandAccess
import net.aechronis.nodes.permissions.LandAccessAction
import net.aechronis.nodes.permissions.LandAccessDecision
import net.aechronis.nodes.permissions.warzoneOccupier
import net.aechronis.nodes.utils.ChatColor
import net.aechronis.nodes.war.Attack
import net.aechronis.nodes.war.AttackMode
import net.aechronis.nodes.war.FlagWar
import net.aechronis.nodes.war.Warzone
import net.aechronis.stats.Stat
import net.aechronis.stats.Stats
import net.aechronis.utils.OreSounds
import net.aechronis.vanilla.managers.BlockDrops
import net.aechronis.vanilla.managers.StorageAccess
import net.minestom.server.MinecraftServer
import net.minestom.server.component.DataComponents
import net.minestom.server.coordinate.BlockVec
import net.minestom.server.coordinate.Point
import net.minestom.server.entity.Player
import net.minestom.server.event.player.PlayerBlockBreakEvent
import net.minestom.server.event.player.PlayerBlockInteractEvent
import net.minestom.server.event.player.PlayerBlockPlaceEvent
import net.minestom.server.item.ItemStack
import net.minestom.server.item.enchant.Enchantment
import java.util.concurrent.ThreadLocalRandom

object NodesWorldListener {
    private fun onBlockBreak(event: PlayerBlockBreakEvent) {
        if (event.isCancelled) return

        val player: Player = event.player
        val blockPos = event.blockPosition
        val territoryChunk = TerritoryChunk.fromBlock(blockPos.blockX, blockPos.blockZ)

        // If a war or colonization flag is active, protect its immediate area.
        val attack = flagProtectingBlock(blockPos)
        if (attack != null) {
            val context = if (attack.mode == AttackMode.COLONIZATION) "[Colonization]" else "[War]"

            if (blockInWarFlagNoBuildRegion(blockPos, attack)) {
                // handle war flag breaking
                if (attack.flagBlock == blockPos) {
                    event.isCancelled = true

                    // handle breaking allies flags
                    if (!Nodes.config.allowBreakingAlliesFlags) {
                        // allow player to break their own flag
                        if (player.uuid != attack.attacker) {
                            val relationship = Town.relationshipOfPlayerToTown(player, attack.town)
                            if (relationship in setOf(
                                    DiplomaticRelationship.NATION,
                                    DiplomaticRelationship.ALLY,
                                    DiplomaticRelationship.TOWN,
                                )
                            ) {
                                Message.error(player, "$context Cannot break ally flags")
                                return
                            }
                        }
                    }
                    attack.cancel()
                    if (attack.mode != AttackMode.COLONIZATION) Stats.add(player, Stat.CAPS_BROKEN)
                    Message.broadcast("${ChatColor.GOLD}$context Attack at (${blockPos.blockX}, ${blockPos.blockY}, ${blockPos.blockZ}) defeated by ${player.username}")
                    return
                }
                event.isCancelled = true
                Message.error(
                    player,
                    "$context Cannot break blocks within ${Nodes.config.flagNoBuildDistance} blocks of flags",
                )
                return
            }
        }

        val decision = LandAccess.check(player, blockPos, LandAccessAction.BREAK)
        if (decision != LandAccessDecision.ALLOW) {
            event.isCancelled = true
            Message.error(player, checkNotNull(decision.message))
        }
    }

    private fun onBlockBreakSuccess(event: PlayerBlockBreakEvent) {
        if (event.isCancelled) {
            return
        }

        val player = event.player
        val block = event.block
        val blockPos = event.blockPosition

        // handle hidden ore mining
        if (Nodes.config.oreBlocks.contains(block)) {
            if (!Nodes.hiddenOreInvalidBlocks.contains(blockPos)) {
                handleHiddenOre(player, blockPos)

                // temporarily invalide block location
                Nodes.hiddenOreInvalidBlocks.add(blockPos)
            }
        }
    }

    private fun onBlockPlace(event: PlayerBlockPlaceEvent) {
        if (event.isCancelled) return

        val block = event.block
        val blockPos = event.blockPosition
        val player: Player = event.player

        // Block placement is disabled inside a flag's no-build region, even across a chunk edge.
        flagProtectingBlock(blockPos)?.let { attack ->
            val context = if (attack.mode == AttackMode.COLONIZATION) "[Colonization]" else "[War]"
            event.isCancelled = true
            Message.error(
                player,
                "$context Cannot build within ${Nodes.config.flagNoBuildDistance} blocks of flags",
            )
            return
        }

        // War flags are globally available during war. Outside war, /colonize
        // enables them only inside the specifically selected AI town.
        val selectedColonizationTown = Colonization.selectedTown(player)
        val flagTerritoryChunk = TerritoryChunk.fromBlock(blockPos.blockX, blockPos.blockZ)
        val isWarzone = flagTerritoryChunk?.territory?.let(Warzone::isActive) == true
        if (FlagWar.enabled || selectedColonizationTown != null || isWarzone || flagTerritoryChunk?.attacker !== null) {
            if (flagTerritoryChunk !== null) {
                // check if this is flag placement; a chunk already under attack takes no second flag
                if (flagTerritoryChunk.attacker === null && FlagWar.flagBlocks.contains(block)) {
                    // get player and town
                    val resident = Resident.fromPlayer(player)
                    if (resident !== null) {
                        val town = resident.town
                        if (town !== null) {
                            val townAttacked = flagTerritoryChunk.territory.town
                            val isColonization = Colonization.isAuthorized(player.uuid, town, townAttacked)
                            // During global war, flags on claimed warzone land follow the normal war rules.
                            val isWarzoneAttack = isWarzone && !isColonization && (!FlagWar.enabled || townAttacked == null)
                            if (!isColonization && !isWarzoneAttack && !FlagWar.enabled) {
                                val error = when {
                                    townAttacked === selectedColonizationTown && town.nation == null ->
                                        "[Colonization] You must be in a nation to colonize"

                                    townAttacked === selectedColonizationTown && !canStartColonization(resident, town) ->
                                        "[Colonization] You must be a town officer or town leader in your nation to colonize"

                                    else ->
                                        "[Colonization] Place the flag inside ${selectedColonizationTown?.name ?: "the selected AI town"}"
                                }
                                if (!canStartColonization(resident, town)) Colonization.clearSelection(player)
                                Message.error(
                                    player,
                                    error,
                                )
                                event.isCancelled = true
                                return
                            }

                            val result = when {
                                isColonization -> FlagWar.beginColonizationAttack(player.uuid, town, flagTerritoryChunk, blockPos)
                                isWarzoneAttack -> FlagWar.beginWarzoneAttack(player.uuid, town, flagTerritoryChunk, blockPos)
                                else -> FlagWar.beginAttack(player.uuid, town, flagTerritoryChunk, blockPos)
                            }
                            val context = when {
                                isColonization -> "[Colonization]"
                                isWarzoneAttack -> "[Warzone]"
                                else -> "[War]"
                            }
                            if (result.isSuccess) {
                                // get town being attacked; an unclaimed warzone has none
                                val attacked = townAttacked
                                if (attacked == null) {
                                    Message.broadcast("${ChatColor.DARK_RED}$context ${event.player.username} is capturing warzone territory ${flagTerritoryChunk.territory.id} at (${blockPos.blockX}, ${blockPos.blockY}, ${blockPos.blockZ})")
                                }
                                // reclaiming your town
                                else if (attacked === town) {
                                    Message.broadcast("${ChatColor.DARK_RED}$context ${event.player.username} is liberating ${attacked.name} at (${blockPos.blockX}, ${blockPos.blockY}, ${blockPos.blockZ})")
                                } else if (isColonization) {
                                    Message.broadcast("${ChatColor.DARK_RED}$context ${event.player.username} started colonizing ${attacked.name} at (${blockPos.blockX}, ${blockPos.blockY}, ${blockPos.blockZ})")
                                } else if (isWarzoneAttack) {
                                    Message.broadcast("${ChatColor.DARK_RED}$context ${event.player.username} is capturing warzone ${attacked.name} at (${blockPos.blockX}, ${blockPos.blockY}, ${blockPos.blockZ})")
                                } else { // attacking enemy
                                    Message.broadcast("${ChatColor.DARK_RED}$context ${event.player.username} is attacking ${attacked.name} at (${blockPos.blockX}, ${blockPos.blockY}, ${blockPos.blockZ})")
                                }
                                return
                            } else {
                                when (result.exceptionOrNull()) {
                                    ErrorNoTerritory -> Message.error(player, "$context There is no territory here")

                                    ErrorAlreadyUnderAttack -> Message.error(player, "$context Chunk already under attack")

                                    ErrorAlreadyCaptured -> Message.error(
                                        player,
                                        "$context Chunk already captured by town or allies",
                                    )

                                    ErrorTownBlacklisted -> Message.error(
                                        player,
                                        "$context Cannot attack this town (blacklisted)",
                                    )

                                    ErrorTownNotWhitelisted -> Message.error(
                                        player,
                                        "$context Cannot attack this town (not whitelisted)",
                                    )

                                    ErrorNotEnemy -> Message.error(
                                        player,
                                        when {
                                            isColonization -> "$context Chunk does not belong to the selected AI town"
                                            isWarzoneAttack -> "$context You must be in a nation to capture this warzone"
                                            else -> "$context Chunk does not belong to an enemy"
                                        },
                                    )

                                    ErrorAnnexDisabled -> Message.error(player, "$context Territory annexing is disabled")

                                    ErrorNotBorderTerritory -> Message.error(
                                        player,
                                        "$context You can only attack border territories",
                                    )

                                    ErrorSkirmishNationRequired -> Message.error(
                                        player,
                                        "$context You must be in a nation to attack during a border skirmish",
                                    )

                                    ErrorSkirmishTargetSelectionRole -> Message.error(
                                        player,
                                        "$context A town leader or officer must place the first flag to select your nation's target",
                                    )

                                    ErrorSkirmishTargetLocked -> {
                                        val selected = FlagWar.skirmishTarget(town)
                                        Message.error(
                                            player,
                                            "$context Your nation can only attack ${selected?.name ?: "its selected territory"} during this skirmish",
                                        )
                                    }

                                    ErrorChunkNotEdge -> Message.error(
                                        player,
                                        "$context Must attack from territory edge or from captured chunk",
                                    )

                                    ErrorFlagTooHigh -> Message.error(
                                        player,
                                        "$context Flag placement too high, cannot create flag",
                                    )

                                    ErrorSkyBlocked -> Message.error(player, "$context Flag must see the sky")

                                    ErrorTooManyAttacks -> Message.error(
                                        player,
                                        "$context You cannot attack any more chunks at the same time",
                                    )
                                }

                                // cancel event
                                event.isCancelled = true
                                return
                            }
                        } else {
                            Message.error(player, "Cannot claim unless you are part of a town")
                            event.isCancelled = true
                        }
                    } else {
                        event.isCancelled = true
                    }
                }
            }
        }

        val decision = LandAccess.check(player, blockPos, LandAccessAction.PLACE, block)
        if (decision != LandAccessDecision.ALLOW) {
            if (decision.applyPlacementCooldown) {
                NodesBlockPlacementCooldownListener.apply(player, blockPos.blockX, blockPos.blockZ)
            }
            event.isCancelled = true
            Message.error(player, checkNotNull(decision.message))
        }
    }

    private fun onBlockPlaceSuccess(event: PlayerBlockPlaceEvent) {
        if (event.isCancelled) {
            return
        }

        val block = event.block
        val blockPos = event.blockPosition

        // invalide hidden ore blocks
        if (Nodes.config.oreBlocks.contains(block)) {
            Nodes.hiddenOreInvalidBlocks.add(blockPos)
        }
    }

    private fun onBlockInteract(event: PlayerBlockInteractEvent) {
        if (event.isCancelled) return

        val action = if (PROTECTED_BLOCKS.any { event.block.compare(it) }) LandAccessAction.CHEST_INTERACT else LandAccessAction.INTERACT
        val decision = LandAccess.check(event.player, event.blockPosition, action, event.block)
        if (decision != LandAccessDecision.ALLOW) {
            event.isCancelled = true
            Message.error(event.player, checkNotNull(decision.message))
        }
    }

    fun hasStorageAccess(player: Player, position: Point, access: StorageAccess): Boolean {
        val blockPosition = BlockVec(position.blockX(), position.blockY(), position.blockZ())
        val action = if (access == StorageAccess.INTERACT) LandAccessAction.STORAGE_INTERACT else LandAccessAction.STORAGE_BREAK
        return LandAccess.check(player, blockPosition, action) == LandAccessDecision.ALLOW
    }

    fun init() {
        Nodes.highPriorityEventNode.addListener(PlayerBlockBreakEvent::class.java, this::onBlockBreak)
        Nodes.lowPriorityEventNode.addListener(PlayerBlockBreakEvent::class.java, this::onBlockBreakSuccess)
        Nodes.highPriorityEventNode.addListener(PlayerBlockPlaceEvent::class.java, this::onBlockPlace)
        Nodes.lowPriorityEventNode.addListener(PlayerBlockPlaceEvent::class.java, this::onBlockPlaceSuccess)
        Nodes.highPriorityEventNode.addListener(PlayerBlockInteractEvent::class.java, this::onBlockInteract)
    }
}

// handle hidden ore generation during mining
private fun handleHiddenOre(player: Player, block: BlockVec) {
    // ignore hidden ore for silk touch tools
    val inMainHand: ItemStack? = player.itemInMainHand
    if (inMainHand?.get(DataComponents.ENCHANTMENTS)?.level(Enchantment.SILK_TOUCH) != 0) {
        return
    }

    val blockX = block.blockX
    val blockZ = block.blockZ
    val blockY = block.blockY

    val territory = Territory.fromBlock(blockX, blockZ)

    if (territory !== null) {
        val random = ThreadLocalRandom.current()

        val territoryTown = territory.town
        val territoryNation = territoryTown?.nation

        val playerTown = Town.fromPlayer(player)
        val playerNation = playerTown?.nation
        val warzoneOccupier = TerritoryChunk.fromBlock(blockX, blockZ)?.let { warzoneOccupier(territory, it) }

        // conditions allowed for mining ore
        if ((Nodes.config.allowOreInWilderness && territoryTown === null) ||
            (territoryTown !== null && territoryTown === playerTown) ||
            (Nodes.config.allowOreInNationTowns && territoryNation !== null && territoryNation === playerNation) ||
            (Nodes.config.allowOreInCaptured && (territory.occupier === playerTown || warzoneOccupier === playerTown))
        ) {
            val miningMultiplier = MiningBoostManager.miningMultiplier() * Warzone.multiplierFor(territory)
            val itemDrops = territory.ores.sample(blockY).map { itemStack ->
                itemStack.withAmount { amount ->
                    (amount.toDouble() * miningMultiplier).toLong().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                }
            }

            // do tax event check
            val territoryOccupier = territory.occupier
            if (territoryOccupier !== null && random.nextDouble() <= Nodes.config.taxMineRate) {
                for (itemStack in itemDrops) {
                    Town.addToIncome(territoryOccupier, itemStack.material(), itemStack.amount())
                }
            }
            // else, drop items normally
            else {
                // Boosted hidden ore goes straight into the inventory, and only falls to the ground when it is full.
                val instance = player.instance ?: MinecraftServer.getInstanceManager().instances.first()
                var dropped = false
                for (itemStack in itemDrops) {
                    BlockDrops.giveOrDrop(player, instance, itemStack)
                    dropped = true
                }
                if (dropped) player.playSound(OreSounds.DING)
            }
        }
    }
}

/**
 * Return if a block is within a war attack flag's no build region
 */
/** The attack whose flag protects [block], looking into neighbouring chunks; a flag block itself wins. */
private fun flagProtectingBlock(block: BlockVec): Attack? {
    if (FlagWar.chunkToAttacker.isEmpty()) return null
    val reach = Nodes.config.flagNoBuildDistance
    var protecting: Attack? = null
    for (chunkX in ((block.blockX - reach) shr 4)..((block.blockX + reach) shr 4)) {
        for (chunkZ in ((block.blockZ - reach) shr 4)..((block.blockZ + reach) shr 4)) {
            val attack = FlagWar.chunkToAttacker[Coord(chunkX, chunkZ)] ?: continue
            if (!blockInWarFlagNoBuildRegion(block, attack)) continue
            if (attack.flagBlock == block) return attack
            if (protecting == null) protecting = attack
        }
    }
    return protecting
}

private fun blockInWarFlagNoBuildRegion(block: BlockVec, attack: Attack): Boolean {
    val x = block.blockX
    val y = block.blockY
    val z = block.blockZ

    if (x < attack.noBuildXMin || x > attack.noBuildXMax) {
        return false
    }
    if (y < attack.noBuildYMin || y > attack.noBuildYMax) {
        return false
    }
    if (z < attack.noBuildZMin || z > attack.noBuildZMax) {
        return false
    }

    return true
}
