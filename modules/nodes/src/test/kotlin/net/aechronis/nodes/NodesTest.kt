package net.aechronis.nodes

import net.aechronis.nodes.commands.arguments.matchingResidents
import net.aechronis.nodes.constants.PermissionsGroup
import net.aechronis.nodes.constants.TownPermissions
import net.aechronis.nodes.objects.MinimapPosition
import net.aechronis.nodes.objects.Plot
import net.aechronis.nodes.objects.Resident
import net.aechronis.nodes.objects.Territory
import net.aechronis.nodes.objects.TerritoryId
import net.aechronis.nodes.objects.TestTownSide
import net.aechronis.nodes.objects.Town
import net.aechronis.nodes.objects.Trains
import net.aechronis.nodes.objects.testTownLockedSide
import net.aechronis.nodes.war.FlagWar
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.BlockVec
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.GameMode
import net.minestom.server.event.EventNode
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent
import net.minestom.server.event.player.PlayerBlockInteractEvent
import net.minestom.server.event.player.PlayerSpawnEvent
import net.minestom.server.event.server.ServerTickMonitorEvent
import net.minestom.server.instance.InstanceContainer
import net.minestom.server.instance.block.Block
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
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
        )

        // initialize nodes with test config
        Nodes.initialize(config)
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
    fun `territories are loaded`() {
        assertTrue(Territory.count() > 0, "Should have loaded territories")
    }

    @Test
    fun `towns are loaded`() {
        assertTrue(Town.count() > 0, "Should have loaded towns")
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
    fun `resident suggestions are case insensitive and sorted`() {
        val alpha = Resident(UUID.randomUUID(), "SuggestionAlpha")
        val beta = Resident(UUID.randomUUID(), "suggestionBeta")
        Nodes.residents[alpha.uuid] = alpha
        Nodes.residents[beta.uuid] = beta

        try {
            assertEquals(listOf("SuggestionAlpha", "suggestionBeta"), matchingResidents("SuGgEsTiOn").map { it.name })
        } finally {
            Nodes.residents.remove(alpha.uuid)
            Nodes.residents.remove(beta.uuid)
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
