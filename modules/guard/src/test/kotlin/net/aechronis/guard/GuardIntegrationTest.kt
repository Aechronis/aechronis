package net.aechronis.guard

import io.github.openminigameserver.worldedit.MinestomWorldEdit
import io.github.openminigameserver.worldedit.platform.adapters.MinestomAdapter
import io.github.openminigameserver.worldedit.platform.config.WorldEditConfig
import net.aechronis.combat.events.ExplosionBlockDamageEvent
import net.aechronis.guard.flags.BooleanFlagValue
import net.aechronis.guard.flags.FlagName
import net.aechronis.guard.objects.WorldEditSelection
import net.aechronis.guard.objects.Zone
import net.aechronis.guard.objects.ZoneBounds
import net.aechronis.guard.storage.ZoneStorage
import net.aechronis.utils.createTestServer
import net.minestom.server.Auth
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.BlockVec
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.Player
import net.minestom.server.entity.PlayerHand
import net.minestom.server.entity.damage.Damage
import net.minestom.server.entity.damage.DamageType
import net.minestom.server.event.entity.EntityDamageEvent
import net.minestom.server.event.player.PlayerBlockBreakEvent
import net.minestom.server.event.player.PlayerBlockInteractEvent
import net.minestom.server.event.player.PlayerBlockPlaceEvent
import net.minestom.server.event.player.PlayerMoveEvent
import net.minestom.server.instance.block.Block
import net.minestom.server.instance.block.BlockFace
import net.minestom.server.network.player.GameProfile
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GuardIntegrationTest {
    private lateinit var instance: net.minestom.server.instance.Instance
    private lateinit var zone: Zone
    private lateinit var worldEdit: MinestomWorldEdit

    @BeforeAll
    fun setUp() {
        instance =
            createTestServer(
                address = "127.0.0.1",
                port = 0,
                auth = Auth.Offline(),
            )
        worldEdit = MinestomWorldEdit()
        worldEdit.init(WorldEditConfig(dataFolder = Files.createTempDirectory("worldedit-test").toFile()))
        zone =
            Zone(
                "protected",
                instance.uuid,
                ZoneBounds(0, 0, 0, 10, 100, 10),
                flags = FlagName.entries.associateWith { BooleanFlagValue(false) },
            )
        val dataPath = Files.createTempDirectory("guard-test").resolve("zones.json")
        ZoneStorage().save(dataPath, listOf(zone))
        Guard.init(GuardConfig(dataPath = dataPath))
    }

    @Test
    fun `reads normalized corners from the WorldEdit selection`() {
        val player = playerAt(5.0, 40.0, 5.0)
        val actor = MinestomAdapter.asActor(player)
        val session =
            com.sk89q.worldedit.WorldEdit
                .getInstance()
                .sessionManager
                .get(actor)
        val world = MinestomAdapter.asWorld(instance)
        session.setRegionSelector(
            world,
            com.sk89q.worldedit.regions.selector.CuboidRegionSelector(
                world,
                com.sk89q.worldedit.math.BlockVector3
                    .at(8, 60, 8),
                com.sk89q.worldedit.math.BlockVector3
                    .at(2, 40, 2),
            ),
        )

        val selection = WorldEditSelection.read(player)

        assertEquals(instance.uuid, selection.instanceId)
        assertEquals(ZoneBounds(2, 40, 2, 8, 60, 8), selection.bounds)
    }

    @Test
    fun `high priority guard cancels protected actions`() {
        val player = playerAt(5.0, 40.0, 5.0)
        val position = BlockVec(5, 40, 5)

        val place =
            PlayerBlockPlaceEvent(
                player,
                instance,
                Block.DIRT,
                BlockFace.TOP,
                position,
                Pos.ZERO,
                PlayerHand.MAIN,
            )
        val breakEvent = PlayerBlockBreakEvent(player, instance, Block.DIRT, Block.AIR, position, BlockFace.TOP)
        val interact = PlayerBlockInteractEvent(player, PlayerHand.MAIN, instance, Block.CHEST, position, Pos.ZERO, BlockFace.TOP)
        val move = PlayerMoveEvent(player, Pos(6.0, 40.0, 6.0), true)

        MinecraftServer.getGlobalEventHandler().call(place)
        MinecraftServer.getGlobalEventHandler().call(breakEvent)
        MinecraftServer.getGlobalEventHandler().call(interact)
        MinecraftServer.getGlobalEventHandler().call(move)

        assertTrue(place.isCancelled)
        assertTrue(breakEvent.isCancelled)
        assertTrue(interact.isCancelled)
        assertTrue(move.isCancelled)
    }

    @Test
    fun `damage is denied inside a protected zone`() {
        val attacker = playerAt(5.0, 40.0, 5.0)
        val victim = playerAt(6.0, 40.0, 6.0)
        val damage = Damage.fromPlayer(attacker, 1f)
        val event = EntityDamageEvent(victim, damage, damage.getSound(victim))

        MinecraftServer.getGlobalEventHandler().call(event)

        assertTrue(event.isCancelled)
    }

    @Test
    fun `other damage is denied inside a protected zone`() {
        val victim = playerAt(6.0, 40.0, 6.0)
        val damage = Damage(DamageType.FALL, null, null, victim.position, 1f)
        val event = EntityDamageEvent(victim, damage, damage.getSound(victim))

        MinecraftServer.getGlobalEventHandler().call(event)

        assertTrue(event.isCancelled)
    }

    @Test
    fun `explosions are denied inside a protected zone`() {
        val event =
            ExplosionBlockDamageEvent(
                instance = instance,
                position = Pos(5.0, 40.0, 5.0),
                sourceUuid = null,
                sourceName = null,
                changes = emptyList(),
            )

        MinecraftServer.getGlobalEventHandler().call(event)

        assertTrue(event.isCancelled)
    }

    private fun playerAt(
        x: Double,
        y: Double,
        z: Double,
    ): Player =
        Player(
            GuardTestConnection(),
            GameProfile(UUID.randomUUID(), "GuardTest"),
        ).also {
            it.setInstance(instance, Pos(x, y, z)).join()
        }

    @AfterAll
    fun tearDown() {
        worldEdit.shutdown()
        MinecraftServer.stopCleanly()
    }
}
