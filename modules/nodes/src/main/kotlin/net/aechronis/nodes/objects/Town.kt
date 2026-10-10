/**
 * Town
 *
 */

package net.aechronis.nodes.objects

import net.aechronis.nodes.Message
import net.aechronis.nodes.Nodes
import net.aechronis.nodes.colonization.AiTownConfig
import net.aechronis.nodes.constants.DiplomaticRelationship
import net.aechronis.nodes.constants.ErrorPlayerHasTown
import net.aechronis.nodes.constants.ErrorTerritoryIsTownHome
import net.aechronis.nodes.constants.ErrorTerritoryIsWarzone
import net.aechronis.nodes.constants.ErrorTerritoryNotInTown
import net.aechronis.nodes.constants.ErrorTerritoryOwned
import net.aechronis.nodes.constants.ErrorTownExists
import net.aechronis.nodes.constants.PermissionsGroup
import net.aechronis.nodes.constants.TownPermissions
import net.aechronis.nodes.serdes.SaveState
import net.aechronis.nodes.serdes.TownJsonCodec
import net.aechronis.nodes.serdes.TownLoadState
import net.aechronis.nodes.serdes.snapshotList
import net.aechronis.nodes.serdes.snapshotMap
import net.aechronis.nodes.utils.ChatColor
import net.aechronis.nodes.utils.Color
import net.aechronis.nodes.utils.EnumArrayMap
import net.aechronis.nodes.utils.createEnumArrayMap
import net.aechronis.nodes.war.FlagWar
import net.aechronis.nodes.war.Warzone
import net.aechronis.server.modules.ModuleScheduler
import net.minestom.server.command.CommandSender
import net.minestom.server.coordinate.BlockVec
import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.Player
import net.minestom.server.inventory.AbstractInventory
import net.minestom.server.inventory.Inventory
import net.minestom.server.item.Material
import net.minestom.server.network.packet.server.play.ParticlePacket
import net.minestom.server.particle.Particle
import net.minestom.server.timer.Task
import net.minestom.server.timer.TaskSchedule
import java.util.Collections
import java.util.EnumSet
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom

// internal town id counter
private var townNametagIdCounter: Int = 0

class Town(
    val uuid: UUID,
    name: String,
    home: TerritoryId, // main territory owned by town
    leader: Resident?,
    spawnpoint: Pos,
) {
    // Registry keys can change only through the domain rename operation.
    var name: String = name
        private set

    var leader: Resident? = leader
        private set

    var home: TerritoryId = home
        private set

    var spawnpoint: Pos = spawnpoint
        private set

    companion object {
        private val towns = linkedMapOf<String, Town>()

        /** Snapshot of registry membership; the domain objects retain their live identity. */
        internal fun all(): List<Town> = towns.values.toList()

        /** Called by world reload after runtime users of the old world have stopped. */
        internal fun clearRegistry() {
            towns.clear()
        }

        fun count(): Int = towns.size

        fun fromName(name: String): Town? = towns[name]

        fun fromUuid(uuid: UUID): Town? = towns.values.firstOrNull { town -> town.uuid == uuid }

        fun fromPlayer(player: Player): Town? = Resident.fromPlayer(player)?.town

        internal fun fromIncomeInventory(inventory: AbstractInventory): Town? = towns.values.firstOrNull { town -> town.income.owns(inventory) }

        fun areAllied(town1: Town?, town2: Town?): Boolean {
            if (town1 == null || town2 == null) return false
            if (town1 === town2) return true
            val nation1 = town1.nation
            val nation2 = town2.nation
            return nation1 != null && (nation1 === nation2 || (nation2 != null && nation1.allies.contains(nation2)))
        }

        fun areEnemies(town1: Town?, town2: Town?): Boolean {
            if (town1 == null || town2 == null) return false
            val nation1 = town1.nation
            val nation2 = town2.nation
            return nation1 != null && nation2 != null && Nation.areEnemies(nation1, nation2)
        }

        fun relationshipOfTownToTown(town: Town?, other: Town?): DiplomaticRelationship {
            if (town != null && other != null) {
                if (town === other) return DiplomaticRelationship.TOWN
                val nation = town.nation
                val otherNation = other.nation
                if (nation != null && nation === otherNation) return DiplomaticRelationship.NATION
                if (nation != null && otherNation != null) {
                    if (nation.allies.contains(otherNation)) return DiplomaticRelationship.ALLY
                    if (Nation.areEnemies(nation, otherNation)) return DiplomaticRelationship.ENEMY
                }
            }
            return DiplomaticRelationship.NEUTRAL
        }

        fun relationshipOfPlayerToTown(player: Player, town: Town): DiplomaticRelationship = relationshipOfTownToTown(fromPlayer(player), town)

        fun relationshipOfPlayerToPlayer(player: Player, other: Player): DiplomaticRelationship = relationshipOfTownToTown(fromPlayer(player), fromPlayer(other))

        fun create(name: String, territory: Territory, leader: Resident?): Result<Town> {
            val spawnpoint = leader?.player()?.position ?: Territory.defaultSpawnLocation(territory)
            if (fromName(name) != null) return Result.failure(ErrorTownExists)
            if (territory.town != null) return Result.failure(ErrorTerritoryOwned)
            if (Warzone.isRegistered(territory)) return Result.failure(ErrorTerritoryIsWarzone)
            if (leader?.town != null) return Result.failure(ErrorPlayerHasTown)
            val town = Town(UUID.randomUUID(), name, territory.id, leader, spawnpoint)
            territory.town = town
            if (leader != null) {
                TownMembershipRequests.cancel(leader)
            }
            towns[name] = town
            Nametag.onTownCreated(town)
            Nodes.markWorldDirty()
            Resident.renderMinimaps()
            return Result.success(town)
        }

        internal fun load(state: TownLoadState): Town = with(state) {
            val leaderResident = leader?.let { Resident.fromUuid(it) }
            val home = requireNotNull(Territory.fromId(TerritoryId(homeId))) {
                "Cannot load town '$name': home territory $homeId is missing from the world definition"
            }
            val spawnpoint = spawn ?: Territory.defaultSpawnLocation(home)
            val town = Town(uuid, name, home.id, leaderResident, spawnpoint)
            residents.forEach { id ->
                Resident.fromUuid(id)?.let { resident -> attachResident(town, resident) }
            }
            officers.forEach { id ->
                Resident.fromUuid(id)?.takeIf { it.town === town }?.let(town.mutableOfficers::add)
            }
            territoryIds.forEach { id ->
                val territory = Territory.fromId(TerritoryId(id))
                if (territory != null) {
                    territory.town?.takeIf { it !== town }?.let { previousOwner ->
                        System.err.println(
                            "Territory ${territory.id} is claimed by both ${previousOwner.name} and ${town.name}; keeping ${town.name}",
                        )
                        previousOwner.mutableTerritories.remove(territory.id)
                        previousOwner.mutableAnnexed.remove(territory.id)
                        previousOwner.markChanged()
                    }
                    town.mutableTerritories.add(territory.id)
                    territory.town = town
                }
            }
            annexedTerritoryIds.forEach { id ->
                val territoryId = TerritoryId(id)
                if (town.territories.contains(territoryId)) town.mutableAnnexed.add(territoryId)
            }
            capturedTerritoryIds.forEach { id ->
                val territoryId = TerritoryId(id)
                Territory.fromId(territoryId)?.let { territory ->
                    territory.occupier?.mutableCaptured?.remove(territoryId)
                    town.mutableCaptured.add(territoryId)
                    territory.occupier = town
                }
            }
            town.income.storage.putAll(income)
            if (color != null) town.color = color
            if (permissions.values.any { it.isNotEmpty() }) {
                permissions.forEach { (type, groups) ->
                    town.permissions[type].clear()
                    town.permissions[type].addAll(groups)
                }
            } else {
                applyDefaultPermissions(town)
            }
            town.protectedBlocks.addAll(protectedBlocks)
            plots.forEach { state ->
                val plot = Plot(state)
                if (plot.name.isNotBlank() && !town.plots.containsKey(plot.name) && Plot.isValid(town, plot)) town.mutablePlots[plot.name] = plot
            }
            town.coatOfArmsUrl = coatOfArmsUrl
            town.aiConfig = aiConfig
            town.lives = lives?.coerceAtLeast(0) ?: 1
            town.capitalLifeGranted = capitalLifeGranted
            town.lifeRevision = lifeRevision.coerceAtLeast(0L)
            towns[name] = town
            town.invalidateSaveState()
            town
        }

        internal fun initializeCapitalLives(town: Town) {
            if (town.capitalLifeGranted) return
            if (town.lives < 2) town.lives = 2
            town.capitalLifeGranted = true
            town.lifeRevision++
            town.markChanged()
        }

        internal fun loseLife(town: Town): Int {
            require(town.lives > 0) { "A town with no remaining lives cannot lose another life" }
            town.lives--
            town.lifeRevision++
            town.markChanged()
            return town.lives
        }

        fun setLives(town: Town, lives: Int) {
            require(lives > 0) { "Town lives must be at least 1" }
            if (town.lives == lives) return
            town.lives = lives
            town.lifeRevision++
            town.markChanged()
            FlagWar.needsSave = true
        }

        internal fun restoreLives(
            town: Town,
            lives: Int,
            capitalLifeGranted: Boolean,
            revision: Long,
        ) {
            if (revision <= town.lifeRevision) return
            val restoredLives = lives.coerceAtLeast(0)
            town.lives = restoredLives
            town.capitalLifeGranted = capitalLifeGranted
            town.lifeRevision = revision
            town.markChanged()
        }

        fun destroy(town: Town) {
            require(!Warzone.ownsRegisteredZone(town)) {
                "Cannot destroy ${town.name}: warzone territories must remain inside a town"
            }
            val nation = town.nation
            if (nation != null) {
                if (nation.towns.size == 1) Nation.destroy(nation) else Nation.removeTown(nation, town)
            }
            town.territories.forEach { territoryId ->
                Territory.fromId(territoryId)?.let { territory ->
                    release(territory)
                    territory.town = null
                }
            }
            FlagWar.clearOccupationsBy(town)
            town.captured.toList().forEach { territoryId ->
                Territory.fromId(territoryId)?.let(::release)
            }
            town.residents.toList().forEach { resident -> removeResident(town, resident) }
            TownMembershipRequests.cancelTown(town)
            towns.remove(town.name)
            Nametag.onTownDestroyed(town)
            Nodes.markWorldDirty()
            Resident.renderMinimaps()
        }

        fun getPlotAt(town: Town, blockX: Int, blockY: Int, blockZ: Int): Plot? = Plot.at(town, blockX, blockY, blockZ)

        fun unclaim(town: Town, territory: Territory): Result<Territory> {
            if (!town.territories.contains(territory.id)) return Result.failure(ErrorTerritoryNotInTown)
            if (town.home == territory.id) return Result.failure(ErrorTerritoryIsTownHome)
            if (Warzone.isRegistered(territory)) return Result.failure(ErrorTerritoryIsWarzone)
            release(territory)
            town.mutableTerritories.remove(territory.id)
            territory.town = null
            town.mutableAnnexed.remove(territory.id)
            town.markChanged()
            Resident.renderMinimaps()
            return Result.success(territory)
        }

        fun addTerritory(town: Town, territory: Territory): Result<Territory> {
            if (territory.town != null) return Result.failure(ErrorTerritoryOwned)
            if (Warzone.isRegistered(territory)) return Result.failure(ErrorTerritoryIsWarzone)
            town.mutableTerritories.add(territory.id)
            territory.town = town
            town.markChanged()
            Resident.renderMinimaps()
            return Result.success(territory)
        }

        fun capture(
            town: Town,
            territory: Territory,
            commitWarState: Boolean = true,
        ) = synchronized(Nodes.occupationPersistenceLock) {
            FlagWar.clearTerritoryOccupation(territory)
            val current = territory.occupier
            if (current != null) {
                current.mutableCaptured.remove(territory.id)
                territory.occupier = null
                current.invalidateSaveState()
            }
            if (territory.town !== town) {
                town.mutableCaptured.add(territory.id)
                territory.occupier = town
            }
            town.markChanged()
            FlagWar.requestMinimapRefresh()
            if (commitWarState) FlagWar.commitTerritoryOccupation(territory, town, colonized = false)
        }

        // Occupy all of a defeated town's land, preserving its ownership and residents.
        internal fun annex(
            annexingTown: Town,
            defeatedTown: Town,
            colonized: Boolean = false,
        ) = synchronized(Nodes.occupationPersistenceLock) {
            require(annexingTown !== defeatedTown) { "A town cannot annex itself" }
            require(towns[annexingTown.name] === annexingTown) { "The annexing town must still exist" }
            require(towns[defeatedTown.name] === defeatedTown) { "The defeated town must still exist" }

            val territories = defeatedTown.territories
                .mapNotNull(Territory::fromId)
                .toList()

            FlagWar.deferMinimapRefresh {
                territories.forEach { territory ->
                    capture(annexingTown, territory, commitWarState = false)
                }
                if (defeatedTown.lives > 0) loseLife(defeatedTown)
                territories.forEach { territory ->
                    FlagWar.commitTerritoryOccupation(territory, annexingTown, colonized, flushJournal = false)
                }
                FlagWar.flushTerritoryOccupationJournal()
            }
        }

        /** Moves every territory from [source] to [destination], then destroys [source]. */
        internal fun merge(destination: Town, source: Town): Int = synchronized(Nodes.occupationPersistenceLock) {
            require(destination !== source) { "A town cannot merge into itself" }
            require(towns[destination.name] === destination) { "The destination town must still exist" }
            require(towns[source.name] === source) { "The source town must still exist" }

            val transferred = source.territories
                .mapNotNull(Territory::fromId)
                .toList()

            transferred.forEach { territory ->
                // Territory occupations belong to the previous ownership state and must not
                // survive an administrative ownership transfer.
                release(territory)
                source.mutableTerritories.remove(territory.id)
                source.mutableAnnexed.remove(territory.id)
                destination.mutableTerritories.add(territory.id)
                territory.town = destination
            }

            destroy(source)
            destination.markChanged()
            Resident.renderMinimaps()
            transferred.size
        }

        /**
         * Transfers ownership of [territory] to [destination] as annexed land.
         * If it was the previous owner's home, the home moves to another of its
         * territories; a town left with no territory is destroyed.
         */
        internal fun annexTerritory(destination: Town, territory: Territory) = synchronized(Nodes.occupationPersistenceLock) {
            val source = territory.town
            require(source !== destination) { "${destination.name} already owns territory ${territory.id}" }
            require(towns[destination.name] === destination) { "The annexing town must still exist" }

            release(territory)
            if (source != null) {
                source.mutableTerritories.remove(territory.id)
                source.mutableAnnexed.remove(territory.id)
            }
            destination.mutableTerritories.add(territory.id)
            destination.mutableAnnexed.add(territory.id)
            territory.town = destination
            destination.invalidateSaveState()

            if (source != null) {
                val newHome = source.territories.firstNotNullOfOrNull(Territory::fromId)
                when {
                    newHome == null -> destroy(source)
                    source.home == territory.id -> setHome(source, newHome)
                    else -> source.invalidateSaveState()
                }
            }
            Nodes.markWorldDirty()
            Resident.renderMinimaps()
        }

        /**
         * Moves all residents from [source] to [destination] as regular residents.
         * Leadership and officer roles are deliberately not transferred.
         */
        internal fun moveResidents(destination: Town, source: Town): Int {
            require(destination !== source) { "A town cannot move residents into itself" }

            val transferred = source.residents.toList()
            setLeader(source, null)
            transferred.forEach { resident ->
                removeResident(source, resident)
                check(addResident(destination, resident, bypassTestTownSelection = true, bypassJoinRestrictions = true)) {
                    "Could not move resident ${resident.name} from ${source.name} to ${destination.name}"
                }
            }
            // Clear any stale role records left by malformed saved data as well.
            if (source.officers.isNotEmpty()) {
                source.mutableOfficers.clear()
                source.markChanged()
            }
            return transferred.size
        }

        fun release(territory: Territory) {
            synchronized(Nodes.occupationPersistenceLock) {
                FlagWar.clearTerritoryOccupation(territory)
                territory.occupier?.let { town ->
                    town.mutableCaptured.remove(territory.id)
                    territory.occupier = null
                    town.markChanged()
                    Resident.renderMinimaps()
                }
                FlagWar.commitTerritoryOccupation(territory, null, colonized = false)
            }
        }

        internal fun restoreOccupation(
            territory: Territory,
            occupier: Town?,
        ) {
            var changed = false
            towns.values.forEach { town ->
                if (town !== occupier && town.mutableCaptured.remove(territory.id)) {
                    town.invalidateSaveState()
                    changed = true
                }
            }
            if (occupier != null && occupier.mutableCaptured.add(territory.id)) {
                occupier.invalidateSaveState()
                changed = true
            }
            if (territory.occupier !== occupier) {
                territory.occupier = occupier
                changed = true
            }
            if (changed) Nodes.markWorldDirty()
        }

        fun addToIncome(town: Town, material: Material, amount: Int) {
            town.income.add(material, amount)
            town.markChanged()
        }

        fun setCoatOfArmsUrl(town: Town, value: String?) {
            town.coatOfArmsUrl = value
            town.markChanged()
        }

        fun setColor(town: Town, r: Int, g: Int, b: Int) {
            town.color = Color(r, g, b)
            town.markChanged()
            Resident.renderMinimaps()
        }

        fun setSpawn(town: Town, spawnpoint: Pos): Boolean {
            val territory = Territory.fromBlock(spawnpoint.blockX(), spawnpoint.blockZ())
            if (territory == null || territory.id != town.home) return false
            town.spawnpoint = spawnpoint
            town.markChanged()
            return true
        }

        internal fun resetSpawn(town: Town): Pos? {
            val home = Territory.fromId(town.home) ?: return null
            val spawnpoint = Territory.defaultSpawnLocation(home)
            town.spawnpoint = spawnpoint
            town.markChanged()
            return spawnpoint
        }

        fun joinRestriction(town: Town, resident: Resident): String? {
            if (resident.town != null || towns.values.any { it.residents.contains(resident) }) {
                return "You are already a member of a town"
            }
            val cooldown = resident.townJoinCooldownRemainingMillis()
            if (cooldown > 0) return "You cannot join another town for ${formatDuration(cooldown)} after leaving a town"
            return null
        }

        /** Shared by loading and live joins; persisted trust and UI are handled by the caller. */
        private fun attachResident(town: Town, resident: Resident) {
            val previousTown = resident.town
            val player = resident.player() ?: previousTown?.playersOnline?.firstOrNull { it.uuid == resident.uuid }
            if (previousTown !== null && previousTown !== town) detachResident(previousTown, resident)
            town.mutableResidents.add(resident)
            resident.updateTownMembership(town)
            town.nation?.let { Nation.indexResident(it, resident, player) }
            if (player != null) indexOnlinePlayer(town, player)
            town.invalidateSaveState()
        }

        private fun detachResident(town: Town, resident: Resident) {
            town.nation?.let { Nation.unindexResident(it, resident) }
            town.mutableResidents.remove(resident)
            town.mutableOfficers.remove(resident)
            if (town.leader === resident) town.leader = null
            town.mutablePlayersOnline.removeAll { it.uuid == resident.uuid }
            resident.updateTownMembership(null)
            town.invalidateSaveState()
        }

        /** Nation owns the town index; this operation keeps every resident index in sync. */
        internal fun updateNationMembership(town: Town, nation: Nation?) {
            val previousNation = town.nation
            if (previousNation === nation) return
            town.residents.forEach { resident ->
                if (previousNation != null) Nation.unindexResident(previousNation, resident)
            }
            town.nation = nation
            town.residents.forEach { resident ->
                if (nation != null) {
                    val player = resident.player() ?: town.playersOnline.firstOrNull { it.uuid == resident.uuid }
                    Nation.indexResident(nation, resident, player)
                }
                resident.needsUpdate()
            }
            town.invalidateSaveState()
        }

        private fun indexOnlinePlayer(town: Town, player: Player) {
            town.mutablePlayersOnline.removeAll { it.uuid == player.uuid }
            town.mutablePlayersOnline.add(player)
        }

        internal fun setOnline(resident: Resident, player: Player) {
            require(resident.uuid == player.uuid) { "Online player does not match resident" }
            val town = resident.town ?: return
            indexOnlinePlayer(town, player)
            town.nation?.let { Nation.indexResident(it, resident, player) }
        }

        internal fun setOffline(resident: Resident, player: Player) {
            val town = resident.town ?: return
            // A delayed disconnect from the previous session must not remove its replacement.
            town.mutablePlayersOnline.removeAll { it === player }
            town.nation?.let { Nation.unindexOnlinePlayer(it, player) }
        }

        fun addResident(
            town: Town,
            resident: Resident,
            bypassTestTownSelection: Boolean = false,
            bypassJoinRestrictions: Boolean = false,
        ): Boolean {
            if (Nodes.config.testTownSelectionEnabled && !bypassTestTownSelection) return false
            if (resident.town != null || towns.values.any { it.residents.contains(resident) }) return false
            if (!bypassJoinRestrictions && joinRestriction(town, resident) != null) return false

            attachResident(town, resident)
            resident.trusted = false
            TownMembershipRequests.cancel(resident)
            resident.minimap?.refresh()
            resident.player()?.let { player -> Nametag.onResidentAdded(town, player) }
            Nodes.markWorldDirty()
            return true
        }

        fun removeResident(town: Town, resident: Resident) {
            if (resident.town !== town) return
            val player = resident.player() ?: town.playersOnline.firstOrNull { it.uuid == resident.uuid }
            Resident.stopPlotSelection(resident)
            detachResident(town, resident)
            resident.minimap?.refresh()
            if (player != null) WaypointMenu.closeBrowse(player, resident)
            if (player != null) Nametag.onResidentRemoved(town, player)
            Nodes.markWorldDirty()
        }

        fun addOfficer(town: Town, resident: Resident): Boolean {
            if (resident.town !== town) return false
            if (town.officers.contains(resident)) return true
            town.mutableOfficers.add(resident)
            town.markChanged()
            return true
        }

        fun removeOfficer(town: Town, resident: Resident): Boolean {
            if (resident.town !== town) return false
            town.mutableOfficers.remove(resident)
            town.markChanged()
            return true
        }

        fun setLeader(town: Town, resident: Resident?) {
            if (resident != null) {
                if (resident.town !== town || town.leader === resident) return
                town.mutableOfficers.remove(resident)
                town.leader = resident
            } else {
                if (town.leader == null) return
                town.leader = null
            }
            town.markChanged()
        }

        fun rename(town: Town, name: String): Boolean {
            if (towns.containsKey(name)) return false
            towns.remove(town.name)
            town.name = name
            town.updateNametags()
            towns[name] = town
            Nametag.onTownRenamed(town)
            town.nation?.needsUpdate()
            town.residents.forEach { it.needsUpdate() }
            town.markChanged()
            return true
        }

        fun incomeInventory(town: Town): Inventory = town.income.getInventory()

        fun setPermissions(town: Town, permissions: Iterable<TownPermissions>, group: PermissionsGroup, flag: Boolean) {
            permissions.forEach { if (flag) town.permissions[it].add(group) else town.permissions[it].remove(group) }
            town.markChanged()
        }

        fun setHome(town: Town, territory: Territory) {
            if (town !== territory.town || town.home == territory.id) return
            town.home = territory.id
            town.spawnpoint = Territory.defaultSpawnLocation(territory)
            Resident.renderMinimaps()
            town.markChanged()
        }

        fun setAiConfig(town: Town, config: AiTownConfig) {
            config.requireRegisteredGuns()
            if (town.aiConfig == config) return
            town.aiConfig = config
            town.markChanged()
        }

        internal fun protectChest(town: Town, block: BlockVec, protect: Boolean) {
            if (protect) town.protectedBlocks.add(block) else town.protectedBlocks.remove(block)
            town.markChanged()
        }

        internal fun showProtectedChests(town: Town, resident: Resident) {
            val player = resident.player() ?: return
            val particle = Particle.HAPPY_VILLAGER
            val offset = Vec(0.1, 0.1, 0.1)
            var runs = 0
            var task: Task? = null
            task = ModuleScheduler.buildTask {
                for (block in town.protectedBlocks) {
                    val locations = listOf(
                        Pos(block.x() + 0.1, block.y() + 0.5, block.z() + 0.1),
                        Pos(block.x() + 0.1, block.y() + 0.5, block.z() + 0.9),
                        Pos(block.x() + 0.9, block.y() + 0.5, block.z() + 0.1),
                        Pos(block.x() + 0.9, block.y() + 0.5, block.z() + 0.9),
                        Pos(block.x() + 0.5, block.y() + 0.5, block.z()),
                        Pos(block.x(), block.y() + 0.5, block.z() + 0.5),
                        Pos(block.x() + 0.5, block.y() + 0.5, block.z() + 1.0),
                        Pos(block.x() + 1.0, block.y() + 0.5, block.z() + 0.5),
                    )
                    player.sendPackets(*locations.map { ParticlePacket(particle, it, offset, 0F, 3) }.toTypedArray())
                }
                runs += 1
                if (runs > 10) task?.cancel()
            }.delay(TaskSchedule.millis(1000)).repeat(TaskSchedule.millis(1000)).schedule()
        }

        internal fun onIncomeInventoryChanged(town: Town) {
            if (!town.income.synchronizeFromInventory()) return
            town.markChanged()
        }

        private fun formatDuration(milliseconds: Long): String {
            val totalMinutes = (milliseconds + 59_999) / 60_000
            val days = totalMinutes / (24 * 60)
            val hours = (totalMinutes % (24 * 60)) / 60
            val minutes = totalMinutes % 60
            return buildList {
                if (days > 0) add("${days}d")
                if (hours > 0) add("${hours}h")
                if (minutes > 0 && days == 0L) add("${minutes}m")
            }.joinToString(" ")
        }

        private fun applyDefaultPermissions(town: Town) {
            enumValues<TownPermissions>().forEach {
                town.permissions[it].clear()
                town.permissions[it].addAll(Nodes.config.defaultTownPermissions[it].orEmpty())
            }
            town.invalidateSaveState()
        }
    }

    // town numeric id, not saved, can change on reload
    // used by nametag scoreboard system (cannot use name because 16 char team limit)
    val townNametagId: Int = townNametagIdCounter++

    // residents belong to town
    private val mutableResidents = hashSetOf<Resident>()
    val residents: Set<Resident> = Collections.unmodifiableSet(mutableResidents)

    // officer rank players (assistants to leader)
    private val mutableOfficers = hashSetOf<Resident>()
    val officers: Set<Resident> = Collections.unmodifiableSet(mutableOfficers)

    // territories owned by town
    // this includes annexed territories
    private val mutableTerritories: HashSet<TerritoryId> = hashSetOf(home)
    val territories: Set<TerritoryId> = Collections.unmodifiableSet(mutableTerritories)

    // separate set of all annexed territories
    private val mutableAnnexed: HashSet<TerritoryId> = hashSetOf()
    val annexed: Set<TerritoryId> = Collections.unmodifiableSet(mutableAnnexed)

    // territories captured by town (but not annexed)
    private val mutableCaptured: HashSet<TerritoryId> = hashSetOf()
    val captured: Set<TerritoryId> = Collections.unmodifiableSet(mutableCaptured)

    // nation for town
    var nation: Nation? = null
        private set

    // Remaining lives; defeated towns retain their territory ownership even at zero.
    var lives: Int = 1
        private set

    internal var capitalLifeGranted: Boolean = false
        private set
    internal var lifeRevision: Long = 0L
        private set

    // Live, read-only view of the online membership index.
    private val mutablePlayersOnline = mutableSetOf<Player>()
    val playersOnline: Set<Player> = Collections.unmodifiableSet(mutablePlayersOnline)

    // income storage container from territory income
    // map material -> current amount of it
    val income: IncomeInventory = IncomeInventory()

    // permission flags, map of
    // town permissions category -> set of allowed groups in (town, ally, nation, outsider)
    val permissions: EnumArrayMap<TownPermissions, EnumSet<PermissionsGroup>> =
        createEnumArrayMap<TownPermissions, EnumSet<PermissionsGroup>> { _ -> EnumSet.of(PermissionsGroup.TOWN) }

    // protected chest blocks in town (for leader, officers, + trusted players)
    val protectedBlocks: HashSet<BlockVec> = hashSetOf()

    // persistent 3D cuboid plots inside the town's claimed territory
    private val mutablePlots = linkedMapOf<String, Plot>()
    val plots: Map<String, Plot> = Collections.unmodifiableMap(mutablePlots)

    var aiConfig: AiTownConfig = AiTownConfig()
        private set

    val isAi: Boolean get() = aiConfig.controlled

    // color for displaying on map
    var color: Color = Color(
        ThreadLocalRandom.current().nextInt(256),
        ThreadLocalRandom.current().nextInt(256),
        ThreadLocalRandom.current().nextInt(256),
    )
        private set

    // re-usable nametag strings, for each diplomatic relation type
    var nametagTown: String = "${DiplomaticRelationship.TOWN.chatColor}[${this.name}]"
    var nametagNation: String = "${DiplomaticRelationship.NATION.chatColor}[${this.name}]"
    var nametagNeutral: String = "${DiplomaticRelationship.NEUTRAL.chatColor}[${this.name}]"
    var nametagAlly: String = "${DiplomaticRelationship.ALLY.chatColor}[${this.name}]"
    var nametagEnemy: String = "${DiplomaticRelationship.ENEMY.chatColor}[${this.name}]"

    var coatOfArmsUrl: String? = null
        private set

    // json string and memoization flag
    private var saveState: TownSaveState

    private var needsUpdate = false

    init {
        if (leader != null) {
            attachResident(this, leader)
        }

        // generate initial json string (must be at end to capture state after leader added)
        this.saveState = TownSaveState(this)
    }

    override fun hashCode(): Int = this.uuid.hashCode()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Town) return false
        return this.uuid == other.uuid
    }

    // update town nametag display strings from name
    // (different color for each diplomacy group)
    fun updateNametags() {
        this.nametagTown = "${DiplomaticRelationship.TOWN.chatColor}[${this.name}]"
        this.nametagNation = "${DiplomaticRelationship.NATION.chatColor}[${this.name}]"
        this.nametagNeutral = "${DiplomaticRelationship.NEUTRAL.chatColor}[${this.name}]"
        this.nametagAlly = "${DiplomaticRelationship.ALLY.chatColor}[${this.name}]"
        this.nametagEnemy = "${DiplomaticRelationship.ENEMY.chatColor}[${this.name}]"
    }

    // prints out nation object info
    fun printInfo(sender: CommandSender) {
        val nation = this.nation?.name ?: "${ChatColor.GRAY}None"
        val leader = this.leader?.name ?: "${ChatColor.GRAY}None"
        val officers = if (this.officers.isNotEmpty()) {
            this.officers.joinToString(", ") { r -> r.name }
        } else {
            "${ChatColor.GRAY}None"
        }
        val residents = if (this.residents.isNotEmpty()) {
            this.residents.joinToString(", ") { r -> r.name }
        } else {
            "${ChatColor.GRAY}None"
        }
        // allies/enemies are inherited from nation
        val allies = if (this.nation?.allies?.isNotEmpty() == true) {
            this.nation!!.allies.joinToString(", ") { it -> it.name }
        } else {
            "${ChatColor.GRAY}None"
        }
        val currentEnemies = this.nation?.effectiveEnemies().orEmpty()
        val enemies = if (currentEnemies.isNotEmpty()) {
            currentEnemies.joinToString(", ") { it -> it.name }
        } else {
            "${ChatColor.GRAY}None"
        }

        Message.print(sender, "${ChatColor.BOLD}Town ${this.name}:")
        Message.print(sender, "- Home${ChatColor.WHITE}: Territory (id = ${this.home})")
        Message.print(sender, "- Territories${ChatColor.WHITE}: ${this.territories.size}")
        Message.print(sender, "- Lives${ChatColor.WHITE}: ${this.lives}")
        Message.print(sender, "- Nation${ChatColor.WHITE}: $nation")
        this.nation?.rallyCap?.let { rallyCap ->
            Message.print(sender, "- War rally cap${ChatColor.WHITE}: $rallyCap online players")
        }
        Message.print(sender, "- Allies${ChatColor.WHITE}: $allies")
        Message.print(sender, "- Enemies${ChatColor.WHITE}: $enemies")
        Message.print(sender, "- Leader${ChatColor.WHITE}: $leader")
        Message.print(sender, "- Officers[${this.officers.size}]${ChatColor.WHITE}: $officers")
        Message.print(sender, "- Residents[${this.residents.size}]${ChatColor.WHITE}: $residents")
    }

    fun printAiConfig(sender: CommandSender) {
        Message.print(sender, "${ChatColor.BOLD}$name AI defenders:")
        Message.print(sender, "- AI-controlled${ChatColor.WHITE}: $isAi")
        Message.print(sender, "- Defenders enabled${ChatColor.WHITE}: ${aiConfig.enabled}")
        Message.print(sender, "- Enemies${ChatColor.WHITE}: ${aiConfig.enemyCount}")
        Message.print(sender, "- Guns${ChatColor.WHITE}: ${aiConfig.guns.ifEmpty { listOf("None") }.joinToString(", ")}")
        Message.print(sender, "- Deployment${ChatColor.WHITE}: entire campaign from safe positions near the town spawn")
    }

    /**
     * Immutable save snapshot, must be composed of immutable primitives.
     * Used to generate json string serialization.
     */
    class TownSaveState(t: Town) : SaveState() {
        val uuid = t.uuid
        val name = t.name
        val leader = t.leader?.uuid
        val home = t.home
        val spawnpoint = Vec(t.spawnpoint.x, t.spawnpoint.y, t.spawnpoint.z)
        val color = t.color
        val coatOfArmsUrl = t.coatOfArmsUrl
        val permissions = TownPermissions.entries.associateWith { t.permissions[it].snapshotList() }.snapshotMap()
        val residents = t.residents.map { x -> x.uuid }.snapshotList()
        val officers = t.officers.map { x -> x.uuid }.snapshotList()
        val territories = t.territories.snapshotList()
        val annexed = t.annexed.snapshotList()
        val captured = t.captured.snapshotList()
        val lives = t.lives
        val capitalLifeGranted = t.capitalLifeGranted
        val lifeRevision = t.lifeRevision
        val income = t.income.snapshot().snapshotMap()
        val protectedBlocks: List<BlockVec> = t.protectedBlocks.snapshotList()
        val plots: List<Plot.PlotSaveState> = t.plots.values.map { it.getSaveState() }.snapshotList()
        val aiConfig: AiTownConfig = t.aiConfig.copy(guns = t.aiConfig.guns.snapshotList())

        override fun encode(): String = TownJsonCodec.encode(this)
    }

    internal fun addPlot(plot: Plot) {
        check(plot.name !in mutablePlots) { "A plot with that name already exists" }
        mutablePlots[plot.name] = plot
        markChanged()
    }

    internal fun replacePlot(previous: Plot, replacement: Plot) {
        check(mutablePlots[previous.name] === previous) { "Plot does not belong to this town" }
        require(previous.name == replacement.name) { "Replacing a plot cannot change its name" }
        mutablePlots[previous.name] = replacement
        markChanged()
    }

    internal fun removePlot(plot: Plot): Boolean {
        if (!mutablePlots.remove(plot.name, plot)) return false
        markChanged()
        return true
    }

    internal fun updatePlotPermissions(plot: Plot, update: () -> Unit) {
        require(mutablePlots[plot.name] === plot) { "Plot does not belong to this town" }
        update()
        markChanged()
    }

    /** Invalidates only the snapshot; loading, save preparation or a containing mutation owns scheduling. */
    internal fun invalidateSaveState() {
        this.needsUpdate = true
    }

    /** Completes a live mutation: the next world save must include a fresh town snapshot. */
    private fun markChanged() {
        invalidateSaveState()
        Nodes.markWorldDirty()
    }

    // wrapper to return self as savestate
    // - returns memoized copy if needsUpdate false
    // - otherwise, parses self
    fun getSaveState(): TownSaveState {
        if (this.needsUpdate) {
            this.saveState = TownSaveState(this)
            this.needsUpdate = false
        }
        return this.saveState
    }
}
