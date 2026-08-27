package net.aechronis.nodes

import net.aechronis.nodes.commands.TownFlyCommand
import net.aechronis.nodes.commands.TownLeaveCommand
import net.aechronis.nodes.constants.PermissionsGroup
import net.aechronis.nodes.constants.TownPermissions
import net.aechronis.nodes.listeners.NodesBlockPlacementCooldownListener
import net.aechronis.nodes.listeners.NodesWorldListener
import net.aechronis.nodes.objects.Building
import net.aechronis.nodes.objects.Farm
import net.aechronis.nodes.objects.MinimapPosition
import net.aechronis.nodes.objects.Nation
import net.aechronis.nodes.objects.Plot
import net.aechronis.nodes.objects.Port
import net.aechronis.nodes.objects.Resident
import net.aechronis.nodes.objects.Territory
import net.aechronis.nodes.objects.TerritoryChunk
import net.aechronis.nodes.objects.TerritoryId
import net.aechronis.nodes.objects.TestTownSide
import net.aechronis.nodes.objects.Town
import net.aechronis.nodes.objects.TrainStationBuilding
import net.aechronis.nodes.objects.Trains
import net.aechronis.nodes.objects.testTownLockedSide
import net.aechronis.nodes.tasks.IncomeCalculator
import net.aechronis.nodes.war.FlagWar
import net.aechronis.nodes.war.Warzone
import net.aechronis.vanilla.Vanilla
import net.aechronis.vanilla.managers.StorageAccess
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.BlockVec
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.GameMode
import net.minestom.server.entity.Player
import net.minestom.server.entity.PlayerHand
import net.minestom.server.event.EventNode
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent
import net.minestom.server.event.player.PlayerBlockInteractEvent
import net.minestom.server.event.player.PlayerBlockPlaceEvent
import net.minestom.server.event.player.PlayerMoveEvent
import net.minestom.server.event.player.PlayerSpawnEvent
import net.minestom.server.event.server.ServerTickMonitorEvent
import net.minestom.server.instance.InstanceContainer
import net.minestom.server.instance.block.Block
import net.minestom.server.instance.block.BlockFace
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import net.minestom.server.network.packet.server.SendablePacket
import net.minestom.server.network.packet.server.play.SetCooldownPacket
import net.minestom.server.network.player.GameProfile
import net.minestom.server.network.player.PlayerConnection
import net.minestom.server.potion.PotionEffect
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Comparator
import java.util.UUID
import kotlin.math.floor
import kotlin.math.min
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NodesTest {
    private lateinit var tmpDir: Path
    private lateinit var instance: InstanceContainer
    private var serverInitialized = false

    @BeforeAll
    fun setup() {
        // start server
        val server = MinecraftServer.init()
        serverInitialized = true
        server.start("0.0.0.0", 25565)

        // create instance
        instance = MinecraftServer.getInstanceManager().createInstanceContainer()
        instance.setGenerator(TestGenerator())

        val eventNode = EventNode.all("test-node").setPriority(0)

        MinecraftServer.getGlobalEventHandler().addChild(eventNode)

        val bossBar = BossBar.bossBar(Component.empty(), 1f, BossBar.Color.GREEN, BossBar.Overlay.PROGRESS)

        eventNode.addListener(AsyncPlayerConfigurationEvent::class.java) { event ->
            val player = event.player
            event.spawningInstance = instance
            player.respawnPoint = Pos(27000.0, 60.0, 5700.0)
            player.gameMode = GameMode.CREATIVE
        }

        eventNode.addListener(PlayerSpawnEvent::class.java) { event ->
            event.player.showBossBar(bossBar)
        }

        eventNode.addListener(PlayerBlockInteractEvent::class.java) { event ->
            if (!event.isCancelled) {
                Message.print(event.player, "you would have just interacted")
            } else {
                Message.error(event.player, "interact event cancelled")
            }
        }

        eventNode.addListener(ServerTickMonitorEvent::class.java) { e ->
            val tickTime = floor(e.tickMonitor.tickTime * 100.0) / 100.0
            val runtime = Runtime.getRuntime()
            val usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
            val maxMemory = runtime.maxMemory() / 1024 / 1024

            bossBar.name(
                Component.text()
                    .append(Component.text("MSPT: $tickTime | Mem: ${usedMemory}MB/${maxMemory}MB")),
            )
            bossBar.progress(min(tickTime / MinecraftServer.TICK_MS, 1.0).toFloat())

            if (tickTime > MinecraftServer.TICK_MS) {
                bossBar.color(BossBar.Color.RED)
            } else {
                bossBar.color(BossBar.Color.GREEN)
            }
        }

        val dir = Paths.get(javaClass.getResource("/nodes/world.json")!!.toURI()).parent
        tmpDir = Files.createTempDirectory("nodes-test")
        Files.walk(dir).use { resources ->
            resources.forEach { src ->
                val dest = tmpDir.resolve(dir.relativize(src))
                if (Files.isDirectory(src)) {
                    Files.createDirectories(dest)
                } else {
                    Files.copy(src, dest)
                }
            }
        }

        // create test config
        val config = NodesConfig(
            path = tmpDir.toString(),
            defaultTownPermissions = enumValues<TownPermissions>().associateWith { setOf(PermissionsGroup.OUTSIDER) },
            chunkAttackTime = 10,
        )

        // initialize nodes with test config
        Nodes.initialize(config)

        Vanilla.init()
    }

    @Test
    fun `train station validation loads its chunk`() {
        val trainInstance = MinecraftServer.getInstanceManager().createInstanceContainer()
        trainInstance.setGenerator { unit -> unit.modifier().fillHeight(64, 65, Block.GOLD_BLOCK) }
        val position = BlockVec(320, 64, 320)
        assertFalse(trainInstance.isChunkLoaded(20, 20))

        val station = Trains.create(position, trainInstance).getOrThrow()
        try {
            assertTrue(trainInstance.isChunkLoaded(20, 20))
        } finally {
            Trains.remove(station.id)
            trainInstance.unloadChunk(20, 20)
        }
    }

    @Test
    fun `train building tier boosts every station in its chunk`() {
        val chunkX = 30
        val chunkZ = 30
        val position = BlockVec(chunkX * 16, 64, chunkZ * 16)
        val trainInstance = MinecraftServer.getInstanceManager().createInstanceContainer()
        trainInstance.setGenerator { unit -> unit.modifier().fillHeight(64, 65, Block.GOLD_BLOCK) }
        val building = TrainStationBuilding.create(chunkX, chunkZ, tier = 3).getOrThrow()
        val station = Trains.create(position, trainInstance).getOrThrow()

        try {
            assertEquals(3, Trains.tierAt(position))
            assertEquals(100.0, Trains.speed(Trains.tierAt(position)))
            assertEquals(32.0, Trains.incomeAt(chunkX, chunkZ)[Material.COAL])
        } finally {
            Trains.remove(station.id)
            Building.destroy(building)
        }
    }

    @Test
    fun `territories are loaded`() {
        assertTrue(Territory.count() > 0, "Should have loaded territories")
    }

    @Test
    fun `rates command is registered and territory ores remain inspectable`() {
        assertNotNull(MinecraftServer.getCommandManager().getCommand("rates"))

        val territory = Nodes.territories.values.first { it.ores.deposits.isNotEmpty() }
        assertTrue(territory.ores.deposits.all { it.dropChance >= 0.0 })
    }

    @Test
    fun `town income rates aggregate territory income without changing storage`() {
        assertNotNull(MinecraftServer.getCommandManager().getCommand("t"))

        val town = Nodes.towns.values.first()
        val storedBefore = town.income.snapshot()
        val rates = IncomeCalculator.calculate()[town].orEmpty()
        val expected = mutableMapOf<Material, Double>()
        val taxRate = Nodes.config.taxIncomeRate.coerceIn(0.0, 1.0)

        Nodes.towns.values.forEach { owner ->
            owner.territories.forEach { territoryId ->
                val territory = Territory.fromId(territoryId) ?: return@forEach
                val factor = if (territory.occupier == null) {
                    if (owner === town) 1.0 else 0.0
                } else {
                    when {
                        owner === town -> 1.0 - taxRate
                        territory.occupier === town -> taxRate
                        else -> 0.0
                    }
                }
                if (factor > 0.0) {
                    territory.income.forEach { (material, amount) ->
                        expected[material] = (expected[material] ?: 0.0) + amount * factor
                    }
                }
            }
        }

        expected.forEach { (material, amount) ->
            assertTrue((rates[material] ?: 0.0) >= amount - 1.0e-9)
        }
        assertEquals(storedBefore, town.income.snapshot())
    }

    @Test
    fun `buildings are collected as town income and included in the breakdown`() {
        val town = Town.fromName("London")!!
        val territory = Territory.fromId(town.home)!!
        val port = Nodes.buildings.filterIsInstance<Port>().single { it.name == "London" }
        val before = IncomeCalculator.calculateBreakdown().getValue(town)
        val farmChunk = territory.chunks.first { Building.getAt(it.x, it.z) == null }
        val farm = Farm.create(farmChunk.x, farmChunk.z, tier = 1).getOrThrow()
        val storageBefore = town.income.snapshot()

        try {
            val after = IncomeCalculator.calculateBreakdown().getValue(town)
            port.income().forEach { (material, amount) ->
                assertEquals(amount, before.buildings[material])
            }
            farm.income().forEach { (material, amount) ->
                assertEquals((before.buildings[material] ?: 0.0) + amount, after.buildings[material])
                assertEquals(
                    (before.total[material] ?: 0.0) + amount,
                    after.total[material],
                )
            }

            Nodes.runIncome()
            farm.income().forEach { (material, amount) ->
                val collected = (town.income.snapshot()[material] ?: 0) - (storageBefore[material] ?: 0)
                assertTrue(collected >= amount.toInt(), "Expected at least $amount $material from the farm, got $collected")
            }
        } finally {
            Building.destroy(farm)
            town.income.storage.clear()
            town.income.storage.putAll(storageBefore)
        }
    }

    @Test
    fun `building income is taxed while its territory is occupied`() {
        val owner = Town.fromName("London")!!
        val occupier = Town.fromName("Brighton")!!
        val territory = Territory.fromId(owner.home)!!
        val ownerBefore = IncomeCalculator.calculateBreakdown().getValue(owner).buildings
        val occupierBefore = IncomeCalculator.calculateBreakdown().getValue(occupier).buildings
        val taxRate = Nodes.config.taxIncomeRate.coerceIn(0.0, 1.0)
        assertTrue(ownerBefore.isNotEmpty())

        territory.occupier = occupier
        occupier.captured.add(territory.id)
        try {
            val ownerAfter = IncomeCalculator.calculateBreakdown().getValue(owner).buildings
            val occupierAfter = IncomeCalculator.calculateBreakdown().getValue(occupier).buildings
            ownerBefore.forEach { (material, amount) ->
                assertEquals(amount * (1.0 - taxRate), ownerAfter[material])
                assertEquals((occupierBefore[material] ?: 0.0) + amount * taxRate, occupierAfter[material])
            }
        } finally {
            occupier.captured.remove(territory.id)
            territory.occupier = null
        }
    }

    @Test
    fun `unclaimed building does not add town income`() {
        val territory = Nodes.territories.values.first { it.town == null }
        val chunk = territory.chunks.first { Building.getAt(it.x, it.z) == null }
        val before = IncomeCalculator.calculateBreakdown()
        val farm = Farm.create(chunk.x, chunk.z, tier = 1).getOrThrow()

        try {
            assertEquals(before, IncomeCalculator.calculateBreakdown())
        } finally {
            Building.destroy(farm)
        }
    }

    @Test
    fun `town fly uses the dedicated command permission`() {
        assertEquals("nodes.fly", TownFlyCommand().permission)
    }

    @Test
    fun `leaving town or nation territory disables flight`() {
        val town = Nodes.towns.values.first()
        val home = Territory.fromId(town.home)!!
        val destination = Nodes.territories.values.first { it.town !== town }
        val player = Player(TestConnection(), GameProfile(UUID.randomUUID(), "flight-test"))
        val resident = Resident(player.uuid, player.username)
        Nodes.residents[resident.uuid] = resident
        resident.town = town

        try {
            player.setInstance(instance, positionIn(home)).join()
            player.gameMode = GameMode.SURVIVAL
            player.isAllowFlying = true

            MinecraftServer.getGlobalEventHandler().call(PlayerMoveEvent(player, positionIn(destination), false))

            assertFalse(player.isAllowFlying)
            assertTrue(player.hasEffect(PotionEffect.SLOW_FALLING))
        } finally {
            Nodes.residents.remove(resident.uuid)
            player.remove()
        }
    }

    @Test
    fun `entering a town in the player's nation preserves flight`() {
        val town = Nodes.towns.values.first()
        val home = Territory.fromId(town.home)!!
        val territory = Nodes.territories.values.first { it.town == null }
        val suffix = UUID.randomUUID().toString().take(8)
        val nationTown = Town.create("FlightNationTown$suffix", territory, null).getOrThrow()
        val nation = Nation.create("FlightNation$suffix", town).getOrThrow()
        Nation.addTown(nation, nationTown).getOrThrow()
        val player = Player(TestConnection(), GameProfile(UUID.randomUUID(), "nation-flight"))
        val resident = Resident(player.uuid, player.username)
        Nodes.residents[resident.uuid] = resident
        resident.town = town

        try {
            player.setInstance(instance, positionIn(home)).join()
            player.gameMode = GameMode.SURVIVAL
            player.isAllowFlying = true

            MinecraftServer.getGlobalEventHandler().call(PlayerMoveEvent(player, positionIn(territory), false))

            assertTrue(player.isAllowFlying)
            assertFalse(player.hasEffect(PotionEffect.SLOW_FALLING))
        } finally {
            Nodes.residents.remove(resident.uuid)
            player.remove()
            Nation.destroy(nation)
            Town.destroy(nationTown)
        }
    }

    @Test
    fun `entering unclaimed or missing territory disables flight`() {
        val town = Nodes.towns.values.first()
        val home = Territory.fromId(town.home)!!
        val unclaimed = Nodes.territories.values.first { it.town == null }
        val noTerritory = Pos(1_000_000.0, 60.0, 1_000_000.0)
        val player = Player(TestConnection(), GameProfile(UUID.randomUUID(), "wilderness-fly"))
        val resident = Resident(player.uuid, player.username)
        Nodes.residents[resident.uuid] = resident
        resident.town = town

        try {
            assertEquals(null, Territory.fromBlock(noTerritory.blockX(), noTerritory.blockZ()))
            player.setInstance(instance, positionIn(home)).join()
            player.gameMode = GameMode.SURVIVAL

            player.isAllowFlying = true
            MinecraftServer.getGlobalEventHandler().call(PlayerMoveEvent(player, positionIn(unclaimed), false))
            assertFalse(player.isAllowFlying)
            assertTrue(player.hasEffect(PotionEffect.SLOW_FALLING))

            player.isAllowFlying = true
            MinecraftServer.getGlobalEventHandler().call(PlayerMoveEvent(player, noTerritory, false))
            assertFalse(player.isAllowFlying)
            assertTrue(player.hasEffect(PotionEffect.SLOW_FALLING))
        } finally {
            Nodes.residents.remove(resident.uuid)
            player.remove()
        }
    }

    @Test
    fun `towns are loaded`() {
        assertTrue(Town.count() > 0, "Should have loaded towns")
    }

    private fun positionIn(territory: Territory): Pos = Pos(
        territory.core.x * 16.0 + 8.0,
        60.0,
        territory.core.z * 16.0 + 8.0,
    )

    private class TestConnection : PlayerConnection() {
        override fun sendPacket(packet: SendablePacket) = Unit

        override fun getRemoteAddress(): SocketAddress = InetSocketAddress(0)
    }

    private class CapturingConnection : PlayerConnection() {
        val packets = mutableListOf<SendablePacket>()

        override fun sendPacket(packet: SendablePacket) {
            packets.add(packet)
        }

        override fun getRemoteAddress(): SocketAddress = InetSocketAddress(0)
    }

    @Test
    fun `foreign claim placement cools block materials but exempts friendly claims`() {
        val territories = Nodes.territories.values.filter { it.town == null }.take(4)
        assertEquals(4, territories.size)
        val suffix = UUID.randomUUID().toString().take(8)
        val target = Town.create("CooldownTarget$suffix", territories[0], null).getOrThrow()
        val nationTown = Town.create("CooldownNation$suffix", territories[1], null).getOrThrow()
        val allyTown = Town.create("CooldownAlly$suffix", territories[2], null).getOrThrow()
        val targetNation = Nation.create("CooldownTargetNation$suffix", target).getOrThrow()
        Nation.addTown(targetNation, nationTown).getOrThrow()
        val allyNation = Nation.create("CooldownAllyNation$suffix", allyTown).getOrThrow()
        Nation.addAlly(targetNation, allyNation).getOrThrow()

        val connection = CapturingConnection()
        val player = Player(connection, GameProfile(UUID.randomUUID(), "cooldown-test"))
        val resident = Resident(player.uuid, player.username)
        val position = BlockVec(territories[0].core.x * 16, 64, territories[0].core.z * 16)
        Nodes.residents[resident.uuid] = resident
        player.inventory.setItemStack(0, ItemStack.of(Material.DIRT))
        player.inventory.setItemStack(1, ItemStack.of(Material.STONE))
        player.inventory.setItemStack(2, ItemStack.of(Material.DIRT))
        player.inventory.setItemStack(3, ItemStack.of(Material.DIAMOND))

        try {
            NodesBlockPlacementCooldownListener.apply(player, position.blockX, position.blockZ)
            val cooldowns = connection.packets.filterIsInstance<SetCooldownPacket>()
            assertEquals(setOf(Material.DIRT.key().asString(), Material.STONE.key().asString()), cooldowns.map { it.cooldownGroup() }.toSet())
            assertTrue(cooldowns.all { it.cooldownTicks() == 4 })

            fun assertNoCooldown(town: Town?, blockPosition: BlockVec = position) {
                connection.packets.clear()
                resident.town = town
                NodesBlockPlacementCooldownListener.apply(player, blockPosition.blockX, blockPosition.blockZ)
                assertTrue(connection.packets.filterIsInstance<SetCooldownPacket>().isEmpty())
            }

            assertNoCooldown(target)
            assertNoCooldown(nationTown)
            assertNoCooldown(allyTown)
            assertNoCooldown(null, BlockVec(territories[3].core.x * 16, 64, territories[3].core.z * 16))
        } finally {
            resident.town = null
            Nodes.residents.remove(resident.uuid)
            Nation.destroy(targetNation)
            Nation.destroy(allyNation)
            Town.destroy(target)
            Town.destroy(nationTown)
            Town.destroy(allyTown)
            player.remove()
        }
    }

    @Test
    fun `can add and remove a territory resource node`() {
        val worldPath = Nodes.config.pathWorld
        val originalWorld = Files.readString(worldPath)
        val territory = Nodes.territories.values.first()
        val resourceNode = Nodes.resourceNodes.keys.first { it !in territory.resourceNodes }

        try {
            val addResult = Nodes.updateTerritoryResourceNode(territory.id, resourceNode, add = true)
            assertTrue(addResult.isSuccess, addResult.exceptionOrNull()?.message)
            assertTrue(Territory.fromId(territory.id)!!.resourceNodes.contains(resourceNode))

            val removeResult = Nodes.updateTerritoryResourceNode(territory.id, resourceNode, add = false)
            assertTrue(removeResult.isSuccess, removeResult.exceptionOrNull()?.message)
            assertFalse(Territory.fromId(territory.id)!!.resourceNodes.contains(resourceNode))
        } finally {
            Files.writeString(worldPath, originalWorld)
            Nodes.loadWorld()
        }
    }

    @Test
    fun `legacy residents use the default minimap position`() {
        assertTrue(Nodes.residents.isNotEmpty())
        Nodes.residents.values.forEach { resident ->
            assertEquals(MinimapPosition.TOP_RIGHT, resident.minimapPosition)
        }
    }

    @Test
    fun `can get town by name`() {
        assertNotNull(Town.fromName("London"), "Town from test data should not be null")
    }

    @Test
    fun `can create a new town`() {
        // territory without a town
        val territory = Territory.fromId(TerritoryId(18248))
        assertNotNull(territory, "Territory should exist")

        val result = Town.create("Birmingham", territory, null)
        assertTrue(result.isSuccess, "Town should have created")

        val town = Town.fromName("Birmingham")
        assertNotNull(town)
        assertEquals("Birmingham", town.name)
        for (permission in enumValues<TownPermissions>()) {
            assertEquals(setOf(PermissionsGroup.TOWN), town.permissions[permission])
        }
    }

    @Test
    fun `empty town permissions use configured defaults`() {
        val territory = Nodes.territories.values.first { it.town == null }
        val town = Town.load(
            UUID.randomUUID(),
            "EmptyDefaults",
            null,
            territory.id.toInt(),
            null,
            null,
            arrayListOf(),
            arrayListOf(),
            arrayListOf(territory.id.toInt()),
            arrayListOf(),
            arrayListOf(),
            mutableMapOf(),
            permissions = mutableMapOf(),
            protectedBlocks = hashSetOf(),
            plots = arrayListOf(),
        )

        assertNotNull(town)
        for (permission in enumValues<TownPermissions>()) {
            assertEquals(setOf(PermissionsGroup.OUTSIDER), town.permissions[permission])
        }
    }

    @Test
    fun `nodes bypass allows building despite town permission denial`() {
        val territory = Nodes.territories.values.first { it.town == null }
        val town = Town.create("BypassTown${UUID.randomUUID().toString().take(8)}", territory, null).getOrThrow()
        val player = Player(TestConnection(), GameProfile(UUID.randomUUID(), "bypass-test"))
        val resident = Resident(player.uuid, player.username)
        val position = BlockVec(territory.core.x * 16, 64, territory.core.z * 16)
        val permissionFlag = "aechronis.dangerously-enable-all-permissions"
        val previousPermissionFlag = System.getProperty(permissionFlag)
        town.permissions[TownPermissions.BUILD].clear()
        Nodes.residents[resident.uuid] = resident

        try {
            System.setProperty(permissionFlag, "true")
            val event = PlayerBlockPlaceEvent(
                player,
                instance,
                Block.STONE,
                BlockFace.TOP,
                position,
                position,
                PlayerHand.MAIN,
            )

            MinecraftServer.getGlobalEventHandler().call(event)

            assertFalse(event.isCancelled)
        } finally {
            if (previousPermissionFlag == null) {
                System.clearProperty(permissionFlag)
            } else {
                System.setProperty(permissionFlag, previousPermissionFlag)
            }
            Nodes.residents.remove(resident.uuid)
            player.remove()
            Town.destroy(town)
        }
    }

    @Test
    fun `all permission updates apply to town and plots`() {
        val territory = Nodes.territories.values.first { it.town == null }
        val town = Town.create("BulkPermissions", territory, null).getOrThrow()
        val allPermissions = enumValues<TownPermissions>().toList()

        Town.setPermissions(town, allPermissions, PermissionsGroup.OUTSIDER, true)
        for (permission in allPermissions) {
            assertTrue(town.permissions[permission].contains(PermissionsGroup.OUTSIDER))
        }

        val core = territory.core
        val plot = Plot.create(
            town,
            "all",
            Plot.BlockVec3(core.x * 16, 0, core.z * 16),
            Plot.BlockVec3(core.x * 16, 0, core.z * 16),
        ).getOrThrow()
        Plot.setGroupPermissions(town, plot, PermissionsGroup.OUTSIDER, allPermissions, false)
        for (permission in allPermissions) {
            assertEquals(false, plot.groupPermission(PermissionsGroup.OUTSIDER, permission))
        }

        val resident = Resident(UUID.randomUUID(), "plot-player")
        Plot.setPlayerPermissions(town, plot, resident, allPermissions, true)
        for (permission in allPermissions) {
            assertEquals(true, plot.playerPermission(resident.uuid, permission))
        }
    }

    @Test
    fun `admin merge transfers non-home territories without moving residents`() {
        val territories = Nodes.territories.values.filter { it.town == null }.take(4)
        assertEquals(4, territories.size)
        val destination = Town.create("MergeDestination", territories[0], null).getOrThrow()
        val source = Town.create("MergeSource", territories[1], null).getOrThrow()
        Town.addTerritory(source, territories[2]).getOrThrow()
        Town.addTerritory(source, territories[3]).getOrThrow()
        val resident = Resident(UUID.randomUUID(), "merge-resident")
        Nodes.residents[resident.uuid] = resident
        assertTrue(Town.addResident(source, resident, bypassTestTownSelection = true))

        try {
            assertEquals(2, Town.mergeTerritories(destination, source))

            assertEquals(setOf(source.home), source.territories)
            assertEquals(source, Territory.fromId(source.home)?.town)
            assertEquals(destination, Territory.fromId(territories[2].id)?.town)
            assertEquals(destination, Territory.fromId(territories[3].id)?.town)
            assertEquals(source, resident.town)
            assertTrue(source.residents.contains(resident))
        } finally {
            Town.destroy(destination)
            Town.destroy(source)
            Nodes.residents.remove(resident.uuid)
        }
    }

    @Test
    fun `admin move transfers residents without ranks`() {
        val territories = Nodes.territories.values.filter { it.town == null }.take(2)
        assertEquals(2, territories.size)
        val destination = Town.create("MoveDestination", territories[0], null).getOrThrow()
        val source = Town.create("MoveSource", territories[1], null).getOrThrow()
        val leader = Resident(UUID.randomUUID(), "move-leader")
        val officer = Resident(UUID.randomUUID(), "move-officer")
        Nodes.residents[leader.uuid] = leader
        Nodes.residents[officer.uuid] = officer
        assertTrue(Town.addResident(source, leader, bypassTestTownSelection = true))
        assertTrue(Town.addResident(source, officer, bypassTestTownSelection = true))
        assertTrue(Town.addOfficer(source, officer))
        Town.setLeader(source, leader)

        try {
            assertEquals(2, Town.moveResidents(destination, source))

            assertTrue(source.residents.isEmpty())
            assertTrue(source.officers.isEmpty())
            assertEquals(null, source.leader)
            assertEquals(destination, leader.town)
            assertEquals(destination, officer.town)
            assertTrue(destination.residents.containsAll(listOf(leader, officer)))
            assertEquals(null, destination.leader)
            assertFalse(destination.officers.contains(leader))
            assertFalse(destination.officers.contains(officer))
        } finally {
            Town.destroy(destination)
            Town.destroy(source)
            Nodes.residents.remove(leader.uuid)
            Nodes.residents.remove(officer.uuid)
        }
    }

    @Test
    fun `test town balance locks only the overpopulated side`() {
        assertEquals(TestTownSide.RED, testTownLockedSide(redPopulation = 5, bluePopulation = 2, difference = 3))
        assertEquals(TestTownSide.BLUE, testTownLockedSide(redPopulation = 2, bluePopulation = 5, difference = 3))
        assertEquals(null, testTownLockedSide(redPopulation = 4, bluePopulation = 2, difference = 3))
        assertEquals(TestTownSide.RED, testTownLockedSide(redPopulation = 2, bluePopulation = 0, difference = 0))
    }

    @Test
    fun `warzones score territory captures track handoffs and survive reload`() {
        val towns = Nodes.territories.values.filter { it.town == null }.take(2)
        assertEquals(2, towns.size, "Test world needs two unclaimed territories")
        val territory = Nodes.territories.values.first { it.town != null }
        val suffix = UUID.randomUUID().toString().take(8)
        val firstTown = Town.create("WarzoneFirst$suffix", towns[0], null).getOrThrow()
        val secondTown = Town.create("WarzoneSecond$suffix", towns[1], null).getOrThrow()
        val firstNation = Nation.create("WarzoneNationFirst$suffix", firstTown).getOrThrow()
        val secondNation = Nation.create("WarzoneNationSecond$suffix", secondTown).getOrThrow()
        val now = System.currentTimeMillis()

        try {
            Warzone.register(listOf(territory))
            assertTrue(Warzone.isActive(territory))
            assertTrue(Warzone.hasActiveZones())

            Town.capture(firstTown, territory)
            assertEquals(firstTown, territory.occupier)
            Warzone.onTerritoryOccupied(territory, firstTown, now)

            Town.capture(secondTown, territory)
            assertEquals(secondTown, territory.occupier)
            Warzone.onTerritoryOccupied(territory, secondTown, now + 5_000L)

            var ranking = Warzone.ranking(territory, now + 8_000L)
            assertEquals(firstNation, ranking[0].nation)
            assertEquals(5_000L, ranking[0].millis)
            assertEquals(secondNation, ranking[1].nation)
            assertEquals(3_000L, ranking[1].millis)

            Warzone.resetForReload()
            Warzone.load()
            ranking = Warzone.ranking(territory, now + 8_000L)
            assertEquals(listOf(firstNation, secondNation), ranking.map { it.nation })
            assertEquals(listOf(5_000L, 3_000L), ranking.map { it.millis })
        } finally {
            Warzone.resetForReload()
            Files.deleteIfExists(Nodes.config.pathWarzone)
            Nation.destroy(firstNation)
            Nation.destroy(secondNation)
            Town.destroy(firstTown)
            Town.destroy(secondTown)
        }
    }

    @Test
    fun `warzones require a town and can never trigger annexation`() {
        val unclaimed = Nodes.territories.values.first { it.town == null }
        val town = Nodes.towns.values.first()
        val home = Territory.fromId(town.home)!!

        try {
            Warzone.register(listOf(unclaimed))
            assertFalse(Warzone.isRegistered(unclaimed))

            assertTrue(FlagWar.shouldAnnexTown(town, home))
            Warzone.register(listOf(home))
            assertTrue(Warzone.isRegistered(home))
            assertFalse(FlagWar.shouldAnnexTown(town, home))
        } finally {
            Warzone.resetForReload()
            Files.deleteIfExists(Nodes.config.pathWarzone)
        }
    }

    @Test
    fun `warzone occupier permissions apply to chunks and captured home territory`() {
        val home = Nodes.territories.values.first { it.town == null && it.chunks.any { coord -> coord != it.core } }
        val attackerHome = Nodes.territories.values.first { it.town == null && it !== home }
        val suffix = UUID.randomUUID().toString().take(8)
        val owner = Town.create("WarzoneOwner$suffix", home, null).getOrThrow()
        val occupier = Town.create("WarzoneOccupier$suffix", attackerHome, null).getOrThrow()
        val occupierNation = Nation.create("WarzoneNation$suffix", occupier).getOrThrow()
        val ownerPlayer = Player(TestConnection(), GameProfile(UUID.randomUUID(), "wzo-$suffix"))
        val occupierPlayer = Player(TestConnection(), GameProfile(UUID.randomUUID(), "wzp-$suffix"))
        val ownerResident = Resident(ownerPlayer.uuid, ownerPlayer.username)
        val occupierResident = Resident(occupierPlayer.uuid, occupierPlayer.username)
        Nodes.residents[ownerResident.uuid] = ownerResident
        Nodes.residents[occupierResident.uuid] = occupierResident
        assertTrue(Town.addResident(owner, ownerResident, bypassTestTownSelection = true))
        assertTrue(Town.addResident(occupier, occupierResident, bypassTestTownSelection = true))
        listOf(owner, occupier).forEach { town ->
            listOf(TownPermissions.BUILD, TownPermissions.DESTROY, TownPermissions.INTERACT, TownPermissions.CHESTS).forEach { permission ->
                town.permissions[permission].clear()
                town.permissions[permission].add(PermissionsGroup.TOWN)
            }
        }
        val partialChunk = TerritoryChunk.fromCoord(home.chunks.first { it != home.core })!!
        val partialPosition = BlockVec(partialChunk.coord.x * 16 + 1, 64, partialChunk.coord.z * 16 + 1)
        val homePosition = BlockVec(home.core.x * 16 + 1, 64, home.core.z * 16 + 1)

        fun place(player: Player, position: BlockVec): PlayerBlockPlaceEvent {
            val event = PlayerBlockPlaceEvent(player, instance, Block.STONE, BlockFace.TOP, position, position, PlayerHand.MAIN)
            MinecraftServer.getGlobalEventHandler().call(event)
            return event
        }

        try {
            Warzone.register(listOf(home))
            partialChunk.occupier = occupier

            assertTrue(place(ownerPlayer, partialPosition).isCancelled)
            assertFalse(place(occupierPlayer, partialPosition).isCancelled)
            assertFalse(NodesWorldListener.hasStorageAccess(ownerPlayer, Pos(partialPosition.x(), partialPosition.y(), partialPosition.z()), StorageAccess.BREAK))
            assertTrue(NodesWorldListener.hasStorageAccess(occupierPlayer, Pos(partialPosition.x(), partialPosition.y(), partialPosition.z()), StorageAccess.BREAK))

            partialChunk.occupier = null
            Town.capture(occupier, home)
            val now = System.currentTimeMillis()
            Warzone.onTerritoryOccupied(home, occupier, now)

            assertFalse(FlagWar.shouldAnnexTown(owner, home))
            assertEquals(owner, home.town)
            assertTrue(Nodes.towns.containsValue(owner))
            assertEquals(occupierNation, Warzone.ranking(home, now + 1L).single().nation)
            assertTrue(place(ownerPlayer, homePosition).isCancelled)
            assertFalse(place(occupierPlayer, homePosition).isCancelled)
            assertFalse(NodesWorldListener.hasStorageAccess(ownerPlayer, Pos(homePosition.x(), homePosition.y(), homePosition.z()), StorageAccess.BREAK))
            assertTrue(NodesWorldListener.hasStorageAccess(occupierPlayer, Pos(homePosition.x(), homePosition.y(), homePosition.z()), StorageAccess.BREAK))
        } finally {
            Warzone.resetForReload()
            Files.deleteIfExists(Nodes.config.pathWarzone)
            ownerPlayer.remove()
            occupierPlayer.remove()
            Nodes.residents.remove(ownerResident.uuid)
            Nodes.residents.remove(occupierResident.uuid)
            Nation.destroy(occupierNation)
            Town.destroy(owner)
            Town.destroy(occupier)
        }
    }

    @Test
    fun `stopping a warzone awards its winner's capital and applies boosted rates`() {
        val territories = Nodes.territories.values.filter { it.town == null }.take(3)
        assertEquals(3, territories.size, "Test world needs three unclaimed territories")
        val suffix = UUID.randomUUID().toString().take(8)
        val owner = Town.create("WarzoneOwner$suffix", territories[0], null).getOrThrow()
        val winnerTown = Town.create("WarzoneWinner$suffix", territories[1], null).getOrThrow()
        val winnerNation = Nation.create("WarzoneWinnerNation$suffix", winnerTown).getOrThrow()
        val now = System.currentTimeMillis()

        try {
            Warzone.register(listOf(territories[0]))
            Town.capture(winnerTown, territories[0])
            Warzone.onTerritoryOccupied(territories[0], winnerTown, now)
            val winner = Warzone.stop(territories[0], now + 1_000L).getOrThrow()
            assertEquals(winnerNation, winner)
            assertEquals(winnerTown, territories[0].occupier)
            assertFalse(Warzone.isActive(territories[0]))
            assertFalse(Warzone.hasActiveZones())

            val incomeTerritory = Nodes.towns.values
                .flatMap { town -> town.territories.mapNotNull(Territory::fromId) }
                .firstOrNull { it.income.isNotEmpty() && it.occupier == null }
                ?: return
            val incomeOwner = incomeTerritory.town!!
            val before = IncomeCalculator.calculate()[incomeOwner].orEmpty()
            Warzone.register(listOf(incomeTerritory))
            assertEquals(2.0, Warzone.multiplierFor(incomeTerritory))
            val after = IncomeCalculator.calculate()[incomeOwner].orEmpty()
            incomeTerritory.income.forEach { (material, amount) ->
                assertTrue((after[material] ?: 0.0) >= (before[material] ?: 0.0) + amount - 1.0e-9)
            }
        } finally {
            Warzone.resetForReload()
            Files.deleteIfExists(Nodes.config.pathWarzone)
            Nation.destroy(winnerNation)
            Town.destroy(owner)
            Town.destroy(winnerTown)
        }
    }

    @Test
    fun `nation rally cap prevents further residents from joining`() {
        val territories = Nodes.territories.values.filter { it.town == null }.take(2)
        assertEquals(2, territories.size)
        val suffix = UUID.randomUUID().toString().take(8)
        val firstTown = Town.create("RallyFirst$suffix", territories[0], null).getOrThrow()
        val secondTown = Town.create("RallySecond$suffix", territories[1], null).getOrThrow()
        val nation = Nation.create("RallyNation$suffix", firstTown).getOrThrow()
        Nation.addTown(nation, secondTown).getOrThrow()
        val firstResident = Resident(UUID.randomUUID(), "rally-first-$suffix")
        val secondResident = Resident(UUID.randomUUID(), "rally-second-$suffix")
        Nodes.residents[firstResident.uuid] = firstResident
        Nodes.residents[secondResident.uuid] = secondResident

        try {
            Nation.setRallyCap(nation, 1)
            assertTrue(Town.addResident(firstTown, firstResident))
            assertFalse(Town.addResident(secondTown, secondResident))
            assertEquals("${nation.name} has reached its rally cap of 1 residents", Town.joinRestriction(secondTown, secondResident))
        } finally {
            Nodes.residents.remove(firstResident.uuid)
            Nodes.residents.remove(secondResident.uuid)
            Nation.destroy(nation)
            Town.destroy(firstTown)
            Town.destroy(secondTown)
        }
    }

    @Test
    fun `voluntary leave cooldown prevents joining towns`() {
        val territory = Nodes.territories.values.first { it.town == null }
        val town = Town.create("CooldownTown${UUID.randomUUID().toString().take(8)}", territory, null).getOrThrow()
        val resident = Resident(UUID.randomUUID(), "leave-cooldown")
        Nodes.residents[resident.uuid] = resident

        try {
            resident.lockTownJoining(60_000)
            assertFalse(Town.addResident(town, resident))
            assertTrue(Town.joinRestriction(town, resident)?.contains("cannot join another town") == true)

            assertTrue(Town.addResident(town, resident, bypassJoinRestrictions = true))
            assertEquals(town, resident.town)
            Town.removeResident(town, resident)

            resident.lockTownJoining(60_000)
            resident.clearTownJoinCooldown()
            assertEquals(0, resident.townJoinCooldownRemainingMillis())
            assertTrue(Town.addResident(town, resident))
        } finally {
            Nodes.residents.remove(resident.uuid)
            Town.destroy(town)
        }
    }

    @Test
    fun `town leave penalty toggle controls cooldown application`() {
        val resident = Resident(UUID.randomUUID(), "leave-penalty")
        val previous = Nodes.config.townLeavePenaltyEnabled

        try {
            Nodes.config.townLeavePenaltyEnabled = true
            TownLeaveCommand.applyLeavePenalty(resident)
            assertTrue(resident.townJoinCooldownRemainingMillis() > 0)

            resident.clearTownJoinCooldown()
            Nodes.config.townLeavePenaltyEnabled = false
            TownLeaveCommand.applyLeavePenalty(resident)
            assertEquals(0, resident.townJoinCooldownRemainingMillis())
        } finally {
            Nodes.config.townLeavePenaltyEnabled = previous
        }
    }

    @Test
    fun `can enable war`() {
        FlagWar.enable(canAnnexTerritories = true, canOnlyAttackBorders = false, destructionEnabled = true)
        assertTrue(Nodes.war.enabled, "War should be enabled")
    }

    @AfterAll
    fun tearDown() {
        // if -DkeepRunning=true is set keep server running for manual testing
        if (System.getProperty("keepRunning") == "true") {
            Thread.currentThread().join()
        }
        if (serverInitialized) MinecraftServer.stopCleanly()
        if (::tmpDir.isInitialized) {
            Files.walk(tmpDir).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }
}
