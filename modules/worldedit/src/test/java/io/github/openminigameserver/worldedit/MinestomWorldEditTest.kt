package io.github.openminigameserver.worldedit

import com.sk89q.worldedit.WorldEdit
import com.sk89q.worldedit.extent.clipboard.Clipboard
import com.sk89q.worldedit.function.operation.Operations
import com.sk89q.worldedit.math.BlockVector3
import com.sk89q.worldedit.session.ClipboardHolder
import com.sk89q.worldedit.world.block.BlockTypes
import io.github.openminigameserver.worldedit.event.WorldEditBlockChange
import io.github.openminigameserver.worldedit.event.WorldEditBlockChangesEvent
import io.github.openminigameserver.worldedit.platform.actors.MinestomConsole
import io.github.openminigameserver.worldedit.platform.adapters.MinestomAdapter
import io.github.openminigameserver.worldedit.platform.adapters.MinestomWorld
import io.github.openminigameserver.worldedit.platform.adapters.MinestomWorldNativeAccess
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.nbt.CompoundBinaryTag
import net.kyori.adventure.text.Component
import net.minestom.server.Auth
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.BlockVec
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.GameMode
import net.minestom.server.event.Event
import net.minestom.server.event.EventNode
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent
import net.minestom.server.event.player.PlayerChatEvent
import net.minestom.server.event.player.PlayerSpawnEvent
import net.minestom.server.event.server.ServerTickMonitorEvent
import net.minestom.server.instance.block.Block
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import net.minestom.server.network.packet.server.SendablePacket
import net.minestom.server.network.player.GameProfile
import net.minestom.server.network.player.PlayerConnection
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.lang.ref.WeakReference
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.util.UUID
import kotlin.math.floor
import kotlin.math.min

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MinestomWorldEditTest {
    private lateinit var clipboard: Clipboard

    @BeforeAll
    fun testInit() {
        val minecraftServer = MinecraftServer.init(Auth.Online())
        val instanceManager = MinecraftServer.getInstanceManager()
        val worldEdit = MinestomWorldEdit()
        worldEdit.init()

        val eventNode = EventNode.all("worldedit-test-node").setPriority(0)
        MinecraftServer.getGlobalEventHandler().addChild(eventNode)

        tickBar(eventNode)

        eventNode.addListener(PlayerChatEvent::class.java) { event ->
            val instance = event.player.instance
            val asWorld = MinestomAdapter.asWorld(instance)
            WorldEdit.getInstance().newEditSessionBuilder().world(asWorld).build().use { editSession ->
                val operation =
                    ClipboardHolder(clipboard)
                        .createPaste(editSession)
                        .to(MinestomAdapter.asBlockVector(event.player.position))
                        .build()
                Operations.complete(operation)
            }

            event.player.apply {
                setGameMode(GameMode.CREATIVE)
                inventory.addItemStack(ItemStack.of(Material.WOODEN_AXE, 1))
            }
        }

        eventNode.addListener(AsyncPlayerConfigurationEvent::class.java) { event ->
            val instanceContainer = instanceManager.createInstanceContainer()
            instanceContainer.setGenerator(TestGenerator())

            val player = event.player
            player.permissionLevel = 3
            event.spawningInstance = instanceContainer
            player.respawnPoint = Pos(0.0, 42.0, 0.0)
        }

        minecraftServer.start("0.0.0.0", 25565)
    }

    private fun tickBar(eventNode: EventNode<Event>) {
        val bossBar = BossBar.bossBar(Component.empty(), 1f, BossBar.Color.GREEN, BossBar.Overlay.PROGRESS)

        eventNode.addListener(PlayerSpawnEvent::class.java) { event ->
            event.player.showBossBar(bossBar)
            event.player.setGameMode(GameMode.CREATIVE)
            event.player.isFlying = true
        }

        eventNode.addListener(ServerTickMonitorEvent::class.java) { e ->
            val tickTime = floor(e.tickMonitor.tickTime * 100.0) / 100.0
            val runtime = Runtime.getRuntime()
            val usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
            val maxMemory = runtime.maxMemory() / 1024 / 1024

            bossBar.name(Component.text("MSPT: $tickTime | Mem: ${usedMemory}MB/${maxMemory}MB"))
            bossBar.progress(min(tickTime / MinecraftServer.TICK_MS, 1.0).toFloat())
            bossBar.color(if (tickTime > MinecraftServer.TICK_MS) BossBar.Color.RED else BossBar.Color.GREEN)
        }
    }

    @Test
    fun `placeholder test`() {
        assertTrue(true)
    }

//    @Test
//    fun `wand clicks select both positions`() {
//        val instance =
//            MinecraftServer.getInstanceManager().createInstanceContainer().apply {
//                setGenerator(TestGenerator())
//            }
//        val player =
//            testPlayer().apply {
//                permissionLevel = 3
//                setEquipment(EquipmentSlot.MAIN_HAND, ItemStack.of(Material.WOODEN_AXE))
//                setInstance(instance, Pos(0.0, 42.0, 0.0)).join()
//            }
//        val actor = MinestomAdapter.asActor(player)
//
//        WorldEdit.getInstance().eventBus.post(
//            com.sk89q.worldedit.event.platform
//                .CommandEvent(actor, "//wand"),
//        )
//        WorldEditExecutor.executor.submit {}.get()
//
//        val first = BlockVec(3, 42, 5)
//        val second = BlockVec(8, 42, 11)
//        val leftClick = PlayerStartDiggingEvent(player, instance, Block.STONE, first, BlockFace.TOP)
//        val rightClick =
//            PlayerBlockInteractEvent(
//                player,
//                PlayerHand.MAIN,
//                instance,
//                Block.STONE,
//                second,
//                Pos(0.5, 0.5, 0.5),
//                BlockFace.TOP,
//            )
//
//        MinecraftServer.getGlobalEventHandler().call(leftClick)
//        MinecraftServer.getGlobalEventHandler().call(rightClick)
//
//        val selector =
//            WorldEdit
//                .getInstance()
//                .sessionManager
//                .get(actor)
//                .getRegionSelector(MinestomAdapter.asWorld(instance)) as CuboidRegionSelector
//        assertTrue(leftClick.isCancelled)
//        assertTrue(rightClick.isCancelled)
//        assertEquals(BlockVector3.at(3, 42, 5), selector.primaryPosition)
//        assertEquals(BlockVector3.at(8, 42, 11), selector.region.maximumPoint)
//
//        player.remove()
//        MinecraftServer.getInstanceManager().unregisterInstance(instance)
//    }

    @Test
    fun `native batches complete and are not replayed`() {
        val instance =
            MinecraftServer.getInstanceManager().createInstanceContainer().apply {
                setGenerator(TestGenerator())
            }
        instance.loadChunk(0, 0).join()

        val world = MinestomWorld(instance)
        val nativeAccess = world.nativeAccess
        val position = Pos(2.0, 42.0, 2.0)
        nativeAccess.setBlockState(nativeAccess.getChunk(0, 0), position, Block.DIAMOND_BLOCK)
        Operations.complete(world.commit())
        assertEquals(Block.DIAMOND_BLOCK, instance.getBlock(2, 42, 2))

        instance.setBlock(2, 42, 2, Block.AIR)
        Operations.complete(world.commit())
        assertEquals(Block.AIR, instance.getBlock(2, 42, 2))

        MinecraftServer.getInstanceManager().unregisterInstance(instance)
    }

    @Test
    fun `direct native changes dispatch after the block is applied`() {
        val instance =
            MinecraftServer.getInstanceManager().createInstanceContainer().apply {
                setGenerator(TestGenerator())
            }
        instance.loadChunk(0, 0).join()
        val nativeAccess = MinestomWorldNativeAccess(WeakReference(instance), false)
        nativeAccess.actor = MinestomConsole
        val position = BlockVec(2, 42, 2)

        withBlockChangeEvents { events ->
            nativeAccess.setBlockState(nativeAccess.getChunk(0, 0), Pos(position), Block.DIAMOND_BLOCK)

            assertEquals(1, events.size)
            val event = events.single()
            assertEquals(MinestomConsole.uniqueId, event.actorUuid)
            assertEquals(MinestomConsole.name, event.actorName)
            assertEquals(instance, event.instance)
            assertEquals(Block.DIAMOND_BLOCK, instance.getBlock(position))
            assertEquals(
                WorldEditBlockChange(position, Block.STONE, Block.DIAMOND_BLOCK),
                event.changes.single(),
            )

            nativeAccess.setBlockState(nativeAccess.getChunk(0, 0), Pos(position), Block.DIAMOND_BLOCK)
            assertEquals(1, events.size)
        }

        MinecraftServer.getInstanceManager().unregisterInstance(instance)
    }

    @Test
    fun `batched changes are coalesced and dispatch once after apply`() {
        val instance =
            MinecraftServer.getInstanceManager().createInstanceContainer().apply {
                setGenerator(TestGenerator())
            }
        instance.loadChunk(0, 0).join()
        val world = MinestomWorld(instance)
        world.setActor(MinestomConsole)
        val nativeAccess = world.nativeAccess
        val first = BlockVec(2, 42, 2)
        val reverted = BlockVec(3, 42, 2)
        val second = BlockVec(4, 42, 2)

        withBlockChangeEvents { events ->
            nativeAccess.setBlockState(nativeAccess.getChunk(0, 0), Pos(first), Block.DIAMOND_BLOCK)
            nativeAccess.setBlockState(nativeAccess.getChunk(0, 0), Pos(first), Block.GOLD_BLOCK)
            nativeAccess.setBlockState(nativeAccess.getChunk(0, 0), Pos(reverted), Block.EMERALD_BLOCK)
            nativeAccess.setBlockState(nativeAccess.getChunk(0, 0), Pos(reverted), Block.STONE)
            nativeAccess.setBlockState(nativeAccess.getChunk(0, 0), Pos(second), Block.IRON_BLOCK)

            assertTrue(events.isEmpty())
            assertEquals(Block.STONE, instance.getBlock(first))
            Operations.complete(world.commit())

            assertEquals(1, events.size)
            assertEquals(
                listOf(
                    WorldEditBlockChange(first, Block.STONE, Block.GOLD_BLOCK),
                    WorldEditBlockChange(second, Block.STONE, Block.IRON_BLOCK),
                ),
                events.single().changes,
            )
            assertEquals(Block.GOLD_BLOCK, instance.getBlock(first))
            assertEquals(Block.STONE, instance.getBlock(reverted))
            assertEquals(Block.IRON_BLOCK, instance.getBlock(second))

            Operations.complete(world.commit())
            assertEquals(1, events.size)
        }

        MinecraftServer.getInstanceManager().unregisterInstance(instance)
    }

    @Test
    fun `actorless changes apply without dispatching`() {
        val instance =
            MinecraftServer.getInstanceManager().createInstanceContainer().apply {
                setGenerator(TestGenerator())
            }
        instance.loadChunk(0, 0).join()
        val world = MinestomWorld(instance)
        val position = BlockVec(2, 42, 2)

        withBlockChangeEvents { events ->
            world.nativeAccess.setBlockState(world.nativeAccess.getChunk(0, 0), Pos(position), Block.DIAMOND_BLOCK)
            Operations.complete(world.commit())

            assertTrue(events.isEmpty())
            assertEquals(Block.DIAMOND_BLOCK, instance.getBlock(position))
        }

        MinecraftServer.getInstanceManager().unregisterInstance(instance)
    }

    @Test
    fun `edit session actor is used for change attribution`() {
        val instance =
            MinecraftServer.getInstanceManager().createInstanceContainer().apply {
                setGenerator(TestGenerator())
            }
        instance.loadChunk(0, 0).join()
        val world = MinestomWorld(instance)
        val position = BlockVec(2, 42, 2)

        withBlockChangeEvents { events ->
            WorldEdit
                .getInstance()
                .newEditSessionBuilder()
                .world(world)
                .actor(MinestomConsole)
                .build()
                .use { editSession ->
                    editSession.setBlock(
                        BlockVector3.at(position.blockX(), position.blockY(), position.blockZ()),
                        BlockTypes.DIAMOND_BLOCK!!.defaultState,
                    )
                }

            assertEquals(1, events.size)
            assertEquals(MinestomConsole.uniqueId, events.single().actorUuid)
            assertEquals(MinestomConsole.name, events.single().actorName)
            assertEquals(Block.DIAMOND_BLOCK, instance.getBlock(position))
        }

        MinecraftServer.getInstanceManager().unregisterInstance(instance)
    }

    @Test
    fun `block nbt participates in change equality`() {
        val instance =
            MinecraftServer.getInstanceManager().createInstanceContainer().apply {
                setGenerator(TestGenerator())
            }
        instance.loadChunk(0, 0).join()
        val nativeAccess = MinestomWorldNativeAccess(WeakReference(instance), false)
        nativeAccess.actor = MinestomConsole
        val position = BlockVec(2, 42, 2)
        val blockWithNbt =
            Block.STONE.withNbt(
                CompoundBinaryTag.builder().putString("worldedit-test", "value").build(),
            )

        withBlockChangeEvents { events ->
            nativeAccess.setBlockState(nativeAccess.getChunk(0, 0), Pos(position), blockWithNbt)
            nativeAccess.setBlockState(nativeAccess.getChunk(0, 0), Pos(position), blockWithNbt)

            assertEquals(1, events.size)
            assertEquals(
                Block.STONE,
                events
                    .single()
                    .changes
                    .single()
                    .oldBlock,
            )
            assertEquals(
                blockWithNbt,
                events
                    .single()
                    .changes
                    .single()
                    .newBlock,
            )
        }

        MinecraftServer.getInstanceManager().unregisterInstance(instance)
    }

    @Test
    fun `block change event defensively copies changes`() {
        val instance = MinecraftServer.getInstanceManager().createInstanceContainer()
        val source =
            mutableListOf(
                WorldEditBlockChange(BlockVec(1, 2, 3), Block.STONE, Block.DIAMOND_BLOCK),
            )
        val event =
            WorldEditBlockChangesEvent(
                MinestomConsole.uniqueId,
                MinestomConsole.name,
                instance,
                source,
            )

        source.clear()
        assertEquals(1, event.changes.size)
        assertThrows(UnsupportedOperationException::class.java) {
            (event.changes as MutableList).clear()
        }

        MinecraftServer.getInstanceManager().unregisterInstance(instance)
    }

    private fun withBlockChangeEvents(block: (MutableList<WorldEditBlockChangesEvent>) -> Unit) {
        val events = mutableListOf<WorldEditBlockChangesEvent>()
        val eventNode = EventNode.all("worldedit-block-change-test-${UUID.randomUUID()}")
        eventNode.addListener(WorldEditBlockChangesEvent::class.java) { event -> events.add(event) }
        val globalEventHandler = MinecraftServer.getGlobalEventHandler()
        globalEventHandler.addChild(eventNode)
        try {
            block(events)
        } finally {
            globalEventHandler.removeChild(eventNode)
        }
    }

    private fun testPlayer(): net.minestom.server.entity.Player =
        net.minestom.server.entity.Player(
            object : PlayerConnection() {
                override fun sendPacket(packet: SendablePacket) {
                }

                override fun getRemoteAddress(): SocketAddress = InetSocketAddress("127.0.0.1", 25565)
            },
            GameProfile(UUID.randomUUID(), "worldedit-test"),
        )

    @AfterAll
    fun keepRunning() {
        if (System.getProperty("keepRunning") == "true") {
            Thread.currentThread().join()
        }
    }
}
