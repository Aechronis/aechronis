/**
 * Main listener for Nodes world:
 * - town permissions and protections
 * - flag war events
 * - hidden ore
 * - ore taxation
 */

package luna.nodes.listeners

import luna.nodes.utils.ChatColor
import luna.nodes.Message
import luna.nodes.Nodes
import luna.nodes.Nodes.getRelationshipOfPlayerToTown
import luna.nodes.constants.DiplomaticRelationship
import luna.nodes.constants.ErrorAlreadyCaptured
import luna.nodes.constants.ErrorAlreadyUnderAttack
import luna.nodes.constants.ErrorChunkNotEdge
import luna.nodes.constants.ErrorFlagTooHigh
import luna.nodes.constants.ErrorNoTerritory
import luna.nodes.constants.ErrorNotBorderTerritory
import luna.nodes.constants.ErrorNotEnemy
import luna.nodes.constants.ErrorSkyBlocked
import luna.nodes.constants.ErrorTooManyAttacks
import luna.nodes.constants.ErrorTownBlacklisted
import luna.nodes.constants.ErrorTownNotWhitelisted
import luna.nodes.constants.INTERACTIVE_BLOCKS
import luna.nodes.constants.PROTECTED_BLOCKS
import luna.nodes.constants.PermissionsGroup
import luna.nodes.constants.TownPermissions
import luna.nodes.objects.Resident
import luna.nodes.objects.Territory
import luna.nodes.objects.TerritoryChunk
import luna.nodes.objects.Town
import luna.nodes.war.Attack
import luna.nodes.war.FlagWar
import net.minestom.server.MinecraftServer
import net.minestom.server.component.DataComponents
import net.minestom.server.coordinate.BlockVec
import net.minestom.server.entity.ItemEntity
import net.minestom.server.entity.Player
import net.minestom.server.event.player.PlayerBlockBreakEvent
import net.minestom.server.event.player.PlayerBlockInteractEvent
import net.minestom.server.event.player.PlayerBlockPlaceEvent
import net.minestom.server.item.ItemStack
import net.minestom.server.item.enchant.Enchantment
import java.util.concurrent.ThreadLocalRandom

object NodesWorldListener {
    private fun onBlockBreak(event: PlayerBlockBreakEvent) {
        val player: Player = event.player
        val blockPos = event.blockPosition
        val territoryChunk = Nodes.getTerritoryChunkFromBlock(blockPos.blockX, blockPos.blockZ)

        // if war enabled, and chunk is being attacked, do flag checks
        if (Nodes.war.enabled && territoryChunk?.attacker !== null) {
            val attack = FlagWar.chunkToAttacker.get(territoryChunk.coord)!!

            if (blockInWarFlagNoBuildRegion(blockPos, attack)) {
                // handle war flag breaking
                if (attack.flagBlock == blockPos) {
                    event.isCancelled = true

                    // handle breaking allies flags
                    if (!Nodes.config.allowBreakingAlliesFlags) {
                        // allow player to break their own flag
                        if (player.uuid != attack.attacker) {
                            val relationship = getRelationshipOfPlayerToTown(player, attack.town)
                            if (relationship in setOf(
                                    DiplomaticRelationship.NATION,
                                    DiplomaticRelationship.ALLY,
                                    DiplomaticRelationship.TOWN,
                                )
                            ) {
                                Message.error(player, "[War] Cannot break ally war flags")
                                return
                            }
                        }
                    }
                    attack.cancel()
                    Message.broadcast("${ChatColor.GOLD}[War] Attack at (${blockPos.blockX}, ${blockPos.blockY}, ${blockPos.blockZ}) defeated by ${player.username}")
                    return
                }
                event.isCancelled = true
                Message.error(
                    player,
                    "[War] Cannot break blocks within ${Nodes.config.flagNoBuildDistance} blocks of war flags"
                )
                return
            }
        }

        val territory: Territory? = Nodes.getTerritoryFromBlock(blockPos.blockX, blockPos.blockZ)
        val town: Town? = territory?.town
        val resident = Nodes.getResident(player)

        // interacting in areas with no territory or no town
        if (town === null) {
            if (hasWildernessPermissions(territory)) {
                return
            }

            event.isCancelled = true
            Message.error(player, "You cannot destroy here!")
            return
        }

        // interacting in a town
        if (resident !== null) {
            if (hasTownPermissions(TownPermissions.DESTROY, town, resident)) {
                return
            }

            // territory occupier permissions
            val occupier: Town? = territory.occupier
            if (occupier !== null && hasOccupierPermissions(TownPermissions.DESTROY, town, occupier, resident)) {
                return
            }

            // war permissions
            if (hasWarPermissions(resident, territory, territoryChunk!!)) {
                return
            }
        }

        event.isCancelled = true
        Message.error(player, "You cannot destroy here!")
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
        val block = event.block
        val blockPos = event.blockPosition
        val player: Player = event.player

        // war specific tasks
        if (Nodes.war.enabled) {
            val territoryChunk = Nodes.getTerritoryChunkFromBlock(blockPos.blockX, blockPos.blockZ)
            if (territoryChunk !== null) {
                // disable block placement in flag no build distance
                if (territoryChunk.attacker !== null) {

                    val attack = FlagWar.chunkToAttacker.get(territoryChunk.coord)
                    if (attack !== null) {
                        if (blockInWarFlagNoBuildRegion(blockPos, attack)) {
                            event.isCancelled = true
                            Message.error(
                                player,
                                "[War] Cannot build within ${Nodes.config.flagNoBuildDistance} blocks of war flags"
                            )
                            return
                        }
                    }
                }
                // check if this is flag placement
                else if (FlagWar.flagBlocks.contains(block)) {
                    // get player and town
                    val resident = Nodes.getResident(player)
                    if (resident !== null) {
                        val town = resident.town
                        if (town !== null) {
                            val result = FlagWar.beginAttack(player.uuid, town, territoryChunk, blockPos)
                            if (result.isSuccess) {
                                // get town being attacked
                                val townAttacked = territoryChunk.territory.town!!

                                // reclaiming your town
                                if (townAttacked === town) {
                                    Message.broadcast("${ChatColor.DARK_RED}[War] ${event.player.username} is liberating ${townAttacked.name} at (${blockPos.blockX}, ${blockPos.blockY}, ${blockPos.blockZ})")
                                } else { // attacking enemy
                                    Message.broadcast("${ChatColor.DARK_RED}[War] ${event.player.username} is attacking ${townAttacked.name} at (${blockPos.blockX}, ${blockPos.blockY}, ${blockPos.blockZ})")
                                }
                            } else {
                                when (result.exceptionOrNull()) {
                                    ErrorNoTerritory -> Message.error(player, "[War] There is no territory here")
                                    ErrorAlreadyUnderAttack -> Message.error(player, "[War] Chunk already under attack")
                                    ErrorAlreadyCaptured -> Message.error(
                                        player,
                                        "[War] Chunk already captured by town or allies"
                                    )

                                    ErrorTownBlacklisted -> Message.error(
                                        player,
                                        "[War] Cannot attack this town (blacklisted)"
                                    )

                                    ErrorTownNotWhitelisted -> Message.error(
                                        player,
                                        "[War] Cannot attack this town (not whitelisted)"
                                    )

                                    ErrorNotEnemy -> Message.error(player, "[War] Chunk does not belong to an enemy")
                                    ErrorNotBorderTerritory -> Message.error(
                                        player,
                                        "[War] You can only attack border territories"
                                    )

                                    ErrorChunkNotEdge -> Message.error(
                                        player,
                                        "[War] Must attack from territory edge or from captured chunk"
                                    )

                                    ErrorFlagTooHigh -> Message.error(
                                        player,
                                        "[War] Flag placement too high, cannot create flag"
                                    )

                                    ErrorSkyBlocked -> Message.error(player, "[War] Flag must see the sky")
                                    ErrorTooManyAttacks -> Message.error(
                                        player,
                                        "[War] You cannot attack any more chunks at the same time"
                                    )
                                }

                                // cancel event
                                event.isCancelled = true
                            }
                        } else {
                            Message.error(player, "[War] Cannot claim unless you are part of a town")
                            event.isCancelled = true
                        }
                    } else {
                        event.isCancelled = true
                    }
                }
            }
        }

        val territory: Territory? = Nodes.getTerritoryFromBlock(blockPos.blockX, blockPos.blockZ)
        val territoryChunk = Nodes.getTerritoryChunkFromBlock(blockPos.blockX, blockPos.blockZ)
        val resident = Nodes.getResident(player)
        val town: Town? = territory?.town

        // interacting in areas with no territory or no town
        if (town === null) {
            if (hasWildernessPermissions(territory)) {
                return
            }

            event.isCancelled = true
            Message.error(player, "You cannot build here!")
            return
        }

        // interacting in a town
        if (resident !== null) {
            if (hasTownPermissions(TownPermissions.BUILD, town, resident)) {
                return
            }

            // territory occupier permissions
            val occupier: Town? = territory.occupier
            if (occupier !== null && hasOccupierPermissions(TownPermissions.BUILD, town, occupier, resident)) {
                return
            }

            // war permissions
            if (hasWarPermissions(resident, territory, territoryChunk!!)) {
                return
            }

            // ignore if war enabled and item in hand is a flag material
            if (Nodes.war.enabled && Nodes.config.flagBlocks.contains(block)) {
                return
            }
        }

        event.isCancelled = true
        Message.error(player, "You cannot build here!")
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
        val territory: Territory? = Nodes.getTerritoryFromBlock(event.blockPosition.blockX,event.blockPosition.blockZ)
        val territoryChunk = Nodes.getTerritoryChunkFromBlock(event.blockPosition.blockX,event.blockPosition.blockZ)
        val resident = Nodes.getResident(event.player)
        val town: Town? = territory?.town

        // interacting in areas with no territory or no town
        // DO NOT USE WILDERNESS PERMISSIONS
        if (territory === null) {
            return
        }
        if (town === null) {
            return
        }

        if (resident !== null) {
            if (!INTERACTIVE_BLOCKS.contains(event.block)) {
                return
            }

            // special permissions for using chests, furnaces, etc...
            if (PROTECTED_BLOCKS.contains(event.block)) {
                // war permissions override
                if (hasWarPermissions(resident, territory, territoryChunk!!)) {
                    return
                }

                // normal town permissions
                if (hasTownPermissions(TownPermissions.CHESTS, town, resident)) {
                    // check if chest protected
                    if (town.protectedBlocks.contains(event.blockPosition) && !resident.hasTownProtectedChestPermissions(town)) {
                        event.isCancelled = true
                        Message.error(event.player, "This chest is for trusted residents only")
                    }

                    return
                }

                event.isCancelled = true
                Message.error(event.player, "You cannot use chests here!")
                return
            }

            // general interact permissions
            if (hasTownPermissions(TownPermissions.INTERACT, town, resident)) {
                return
            }

            // territory occupier permissions
            val occupier: Town? = territory.occupier
            if (occupier !== null && hasOccupierPermissions(TownPermissions.INTERACT, town, occupier, resident)) {
                return
            }

            // war permissions
            if (hasWarPermissions(resident, territory, territoryChunk!!)) {
                return
            }
        }

        event.isCancelled = true
        Message.error(event.player, "You cannot interact here!")
    }

    fun init() {
        Nodes.highPriorityEventNode.addListener(PlayerBlockBreakEvent::class.java, this::onBlockBreak)
        Nodes.lowPriorityEventNode.addListener(PlayerBlockBreakEvent::class.java, this::onBlockBreakSuccess)
        Nodes.highPriorityEventNode.addListener(PlayerBlockPlaceEvent::class.java, this::onBlockPlace)
        Nodes.lowPriorityEventNode.addListener(PlayerBlockPlaceEvent::class.java, this::onBlockPlaceSuccess)
        Nodes.highPriorityEventNode.addListener(PlayerBlockInteractEvent::class.java, this::onBlockInteract)
    }
}

/**
 * Permissions for unclaimed territories or empty areas (no territories)
 */
private fun hasWildernessPermissions(territory: Territory?): Boolean {
    if (territory !== null && Nodes.config.canInteractInUnclaimed) {
        return true
    } else if (Nodes.config.canInteractInEmpty) {
        return true
    }

    return false
}

/**
 * Default permissions check for town:
 * perms: town permissions type
 * town: town
 * player: player interacting in town
 */
private fun hasTownPermissions(perms: TownPermissions, town: Town, player: Resident): Boolean {
    if (town.permissions[perms].contains(PermissionsGroup.TOWN) && player.town === town) {
        return true
    } else if (town.permissions[perms].contains(PermissionsGroup.TRUSTED) && player.town === town && player.trusted) {
        return true
    } else if (town.permissions[perms].contains(PermissionsGroup.NATION) && town.nation !== null && player.nation === town.nation) {
        return true
    } else if (town.permissions[perms].contains(PermissionsGroup.ALLY) && town.nation !== null && player.town?.nation !== null && town.nation!!.allies.contains(player.town!!.nation)) {
        return true
    } else if (town.permissions[perms].contains(PermissionsGroup.OUTSIDER)) {
        return true
    }

    return false
}

/**
 * Permissions check for a town's territory occupied by another town:
 * perms: town permissions type
 * town: town that owns the territory
 * occupier: town that is occupier of the territory
 * player: player interacting in the territory
 */
private fun hasOccupierPermissions(perms: TownPermissions, town: Town, occupier: Town, player: Resident): Boolean {
    return if (Nodes.config.allowControlInOccupiedTownList.contains(town.uuid)) {
        hasTownPermissions(perms, occupier, player)
    } else {
        false
    }
}

// bypass permissions and allow all interaction in
// captured chunks/territories during wartime
private fun hasWarPermissions(resident: Resident, territory: Territory, territoryChunk: TerritoryChunk): Boolean {
    if (Nodes.war.enabled) {
        val residentTown = resident.town
        val territoryTown = territory.town

        if (residentTown !== null) {
            // extended permissions for allies
            if (Nodes.config.warPermissions) {
                val residentNation = residentTown.nation

                val territoryOccupierNation = territory.occupier?.nation
                val territoryTownNation = territoryTown?.nation
                val chunkOccupierNation = territoryChunk.occupier?.nation
                val chunkAttackerNation = territoryChunk.attacker?.nation

                if (territory.occupier === residentTown ||
                    (residentNation !== null && territoryOccupierNation !== null && residentNation.allies.contains(territoryOccupierNation)) ||
                    territoryChunk.occupier === residentTown ||
                    territoryChunk.attacker === residentTown ||
                    (residentNation !== null && territoryTownNation !== null && residentNation.allies.contains(territoryTownNation)) ||
                    (residentNation !== null && chunkOccupierNation !== null && residentNation.allies.contains(chunkOccupierNation)) ||
                    (residentNation !== null && chunkAttackerNation !== null && residentNation.allies.contains(chunkAttackerNation))
                ) {
                    return true
                }

                if (residentNation !== null) {
                    if (residentNation === territoryChunk.occupier?.nation ||
                        residentNation === territory.occupier?.nation ||
                        residentNation === territoryChunk.attacker?.nation
                    ) {
                        return true
                    }
                }
            }
            // only let town/nation by default
            else {
                if (territory.occupier === residentTown || territoryChunk.occupier === residentTown || territoryChunk.attacker === residentTown) {
                    return true
                }

                val residentNation = residentTown.nation
                if (residentNation !== null) {
                    if (residentNation === territoryChunk.occupier?.nation ||
                        residentNation === territory.occupier?.nation ||
                        residentNation === territoryChunk.attacker?.nation
                    ) {
                        return true
                    }
                }
            }
        }
    }

    return false
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

    val territory = Nodes.getTerritoryFromBlock(blockX, blockZ)

    if (territory !== null) {
        val random = ThreadLocalRandom.current()

        val territoryTown = territory.town
        val territoryNation = territoryTown?.nation

        val playerTown = Nodes.getTownFromPlayer(player)
        val playerNation = playerTown?.nation

        // conditions allowed for mining ore
        if ((Nodes.config.allowOreInWilderness && territoryTown === null) ||
            (territoryTown !== null && territoryTown === playerTown) ||
            (Nodes.config.allowOreInNationTowns && territoryNation !== null && territoryNation === playerNation) ||
            (Nodes.config.allowOreInCaptured && territory.occupier === playerTown)
        ) {
            val itemDrops = territory.ores.sample(blockY)

            // do tax event check
            val territoryOccupier = territory.occupier
            if (territoryOccupier !== null && random.nextDouble() <= Nodes.config.taxMineRate) {
                for (itemStack in itemDrops) {
                    Nodes.addToIncome(territoryOccupier, itemStack.material(), itemStack.amount())
                }
            }
            // else, drop items normally
            else {
                for (itemStack in itemDrops) {
                    val itemEntity = ItemEntity(itemStack)
                    itemEntity.setInstance(MinecraftServer.getInstanceManager().instances.first(), block)
                }
            }
        }
    }
}

/**
 * Return if a block is within a war attack flag's no build region
 */
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
