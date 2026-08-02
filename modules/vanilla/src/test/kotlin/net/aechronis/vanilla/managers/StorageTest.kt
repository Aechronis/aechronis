package net.aechronis.vanilla.managers

import net.aechronis.vanilla.ManagerTest
import net.aechronis.vanilla.VanillaTest
import net.aechronis.vanilla.listeners.StorageListener
import net.aechronis.vanilla.objects.StorageContents
import net.aechronis.vanilla.serdes.StorageDeserializer
import net.aechronis.vanilla.serdes.StorageSerializer
import net.kyori.adventure.key.Key
import net.kyori.adventure.nbt.BinaryTagIO
import net.kyori.adventure.nbt.BinaryTagTypes
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.BlockVec
import net.minestom.server.entity.PlayerHand
import net.minestom.server.event.player.PlayerBlockBreakEvent
import net.minestom.server.instance.anvil.AnvilLoader
import net.minestom.server.instance.block.Block
import net.minestom.server.instance.block.BlockFace
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import net.minestom.server.listener.BlockPlacementListener
import net.minestom.server.network.packet.client.play.ClientPlayerBlockPlacementPacket
import java.nio.file.Files
import java.util.AbstractMap.SimpleImmutableEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StorageTest : ManagerTest() {
    @Test
    fun `storage init does not create a sidecar directory`() {
        assertFalse(VanillaTest.storageDirectoryCreatedOnInit)
    }

    @Test
    fun `legacy sidecar migrates all 54 slots into barrel nbt`() {
        val pos = BlockVec(20, 40, 20)
        val key = Storage.keyFor(VanillaTest.instance, pos)
        val legacyContents = StorageContents()
        val expected = ItemStack.of(Material.DIAMOND, 7)
        legacyContents.inventory.setItemStack(53, expected)
        val file =
            VanillaTest.pluginRoot
                .resolve("storage")
                .resolve(VanillaTest.instance.uuid.toString())
                .resolve("20_40_20.dat")
        Files.createDirectories(file.parent)
        Files.newOutputStream(file).use { output ->
            BinaryTagIO.writer().writeNamed(
                SimpleImmutableEntry("", StorageSerializer.serialize(legacyContents.inventory)),
                output,
                BinaryTagIO.Compression.GZIP,
            )
        }
        VanillaTest.instance.setBlock(pos, Block.BARREL)

        val migrated = Storage.loadOrCreate(key)

        assertEquals(expected, migrated.inventory.getItemStack(53))
        assertTrue(
            VanillaTest.instance
                .getBlock(pos)
                .nbtOrEmpty()
                .contains(StorageSerializer.ITEMS_KEY, BinaryTagTypes.LIST),
        )
        assertFalse(Files.exists(file))
        assertTrue(Files.exists(file.resolveSibling("${file.fileName}.migrated")))
        Storage.remove(key)
        VanillaTest.instance.setBlock(pos, Block.AIR)
    }

    @Test
    fun `open barrel changes update block nbt before close`() {
        val pos = BlockVec(24, 40, 20)
        val key = Storage.keyFor(VanillaTest.instance, pos)
        VanillaTest.instance.setBlock(pos, Block.BARREL)
        val contents = Storage.loadOrCreate(key)
        val expected = ItemStack.of(Material.GOLD_INGOT, 9)

        contents.inventory.setItemStack(42, expected)

        val saved = StorageDeserializer.deserialize(VanillaTest.instance.getBlock(pos).nbtOrEmpty())
        assertEquals(expected, saved.inventory.getItemStack(42))
        Storage.remove(key)
        VanillaTest.instance.setBlock(pos, Block.AIR)
    }

    @Test
    fun `corrupt legacy storage is ignored without archiving the source`() {
        val pos = BlockVec(28, 40, 20)
        val key = Storage.keyFor(VanillaTest.instance, pos)
        val file =
            VanillaTest.pluginRoot
                .resolve("storage")
                .resolve(VanillaTest.instance.uuid.toString())
                .resolve("28_40_20.dat")
        Files.createDirectories(file.parent)
        Files.writeString(file, "not nbt")
        VanillaTest.instance.setBlock(pos, Block.BARREL)

        val contents = Storage.loadOrCreate(key)

        assertTrue(contents.inventory.itemStacks.all { it.isAir })
        assertTrue(Files.exists(file))
        assertFalse(Files.exists(file.resolveSibling("${file.fileName}.migrated")))
        Storage.remove(key)
        VanillaTest.instance.setBlock(pos, Block.AIR)
    }

    @Test
    fun `breaking an open barrel closes viewers and removes storage`() {
        val pos = BlockVec(76, 40, 30)
        val key = Storage.keyFor(VanillaTest.instance, pos)
        val breaker =
            VanillaTest.createPlayer(
                net.minestom.server.coordinate
                    .Pos(76.5, 40.0, 30.5),
            )
        val viewer =
            VanillaTest.createPlayer(
                net.minestom.server.coordinate
                    .Pos(78.5, 40.0, 30.5),
            )
        VanillaTest.instance.setBlock(pos, Block.BARREL)

        val contents = Storage.loadOrCreate(key)
        contents.inventory.setItemStack(0, ItemStack.of(Material.DIAMOND, 3))
        breaker.openInventory(contents.inventory)
        viewer.openInventory(contents.inventory)

        val event = PlayerBlockBreakEvent(breaker, VanillaTest.instance, Block.BARREL, Block.AIR, pos, BlockFace.TOP)
        StorageListener.onBreak(event)

        assertTrue(event.isCancelled)
        assertNull(breaker.openInventory)
        assertNull(viewer.openInventory)
        assertTrue(contents.inventory.viewers.isEmpty())
        assertTrue(VanillaTest.instance.getBlock(pos).isAir)
        assertFalse(Storage.barrels.containsKey(key))
        assertFalse(Storage.inventoryToKey.containsKey(contents.inventory))

        VanillaTest.remove(breaker)
        VanillaTest.remove(viewer)
    }

    @Test
    fun `anvil reload preserves barrel nbt handler state and high slots`() {
        val worldRoot = Files.createTempDirectory("vanilla-anvil-test-")
        val dimension = Key.key("minecraft:overworld")
        val instanceManager = MinecraftServer.getInstanceManager()
        var first: net.minestom.server.instance.InstanceContainer? = null
        var second: net.minestom.server.instance.InstanceContainer? = null
        try {
            first = instanceManager.createInstanceContainer(AnvilLoader(worldRoot, dimension))
            first.loadChunk(0, 0).join()
            val pos = BlockVec(4, 40, 4)
            val key = Storage.keyFor(first, pos)
            val contents = StorageContents()
            val expected = ItemStack.of(Material.EMERALD, 11)
            contents.inventory.setItemStack(53, expected)
            first.setBlock(pos, Block.BARREL.withProperty("facing", "east"))
            Storage.register(key, contents)
            Storage.save(key)
            first.saveChunksToStorage().join()
            Storage.remove(key)
            instanceManager.unregisterInstance(first)
            first = null

            second = instanceManager.createInstanceContainer(AnvilLoader(worldRoot, dimension))
            second.loadChunk(0, 0).join()
            val block = second.getBlock(pos)
            val reloaded = Storage.loadOrCreate(Storage.keyFor(second, pos))

            assertTrue(block.compare(Block.BARREL))
            assertEquals("east", block.getProperty("facing"))
            assertEquals("minecraft:barrel", block.handler()?.key?.asString())
            assertTrue(block.nbtOrEmpty().contains(StorageSerializer.ITEMS_KEY, BinaryTagTypes.LIST))
            assertEquals(expected, reloaded.inventory.getItemStack(53))
            Storage.remove(Storage.keyFor(second, pos))
        } finally {
            first?.let(instanceManager::unregisterInstance)
            second?.let(instanceManager::unregisterInstance)
            worldRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `stations consume normal block placement for both hands`() {
        val stations = listOf(Block.BARREL, Block.STONECUTTER, Block.CRAFTING_TABLE)
        for ((index, station) in stations.withIndex()) {
            assertStationInteraction(station, BlockVec(30 + index * 4, 40, 30), PlayerHand.MAIN, false, false)
            assertStationInteraction(station, BlockVec(30 + index * 4, 40, 34), PlayerHand.OFF, false, false)
        }
    }

    @Test
    fun `sneaking with an item places against stations`() {
        val stations = listOf(Block.BARREL, Block.STONECUTTER, Block.CRAFTING_TABLE)
        for ((index, station) in stations.withIndex()) {
            assertStationInteraction(station, BlockVec(50 + index * 4, 40, 30), PlayerHand.MAIN, true, true)
        }
    }

    private fun assertStationInteraction(
        station: Block,
        pos: BlockVec,
        hand: PlayerHand,
        sneaking: Boolean,
        places: Boolean,
    ) {
        VanillaTest.instance.setBlock(pos, station)
        VanillaTest.instance.setBlock(pos.add(0, 1, 0), Block.AIR)
        val player =
            VanillaTest.createPlayer(
                net.minestom.server.coordinate
                    .Pos(pos.x() + 0.5, 41.0, pos.z() + 2.5),
            )
        val held = ItemStack.of(Material.DIRT, 2)
        player.setItemInHand(hand, held)
        player.isSneaking = sneaking

        BlockPlacementListener.listener(
            ClientPlayerBlockPlacementPacket(hand, pos, BlockFace.TOP, 0.5f, 1f, 0.5f, false, false, 1),
            player,
        )

        if (places) {
            assertTrue(VanillaTest.instance.getBlock(pos.add(0, 1, 0)).compare(Block.DIRT))
            assertEquals(1, player.getItemInHand(hand).amount())
            assertNull(player.openInventory)
        } else {
            assertTrue(VanillaTest.instance.getBlock(pos.add(0, 1, 0)).isAir)
            assertEquals(held, player.getItemInHand(hand))
            assertNotNull(player.openInventory)
        }
        player.closeInventory()
        VanillaTest.remove(player)
        VanillaTest.instance.setBlock(pos, Block.AIR)
        VanillaTest.instance.setBlock(pos.add(0, 1, 0), Block.AIR)
        Storage.remove(Storage.keyFor(VanillaTest.instance, pos))
    }
}
