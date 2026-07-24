package net.aechronis.vanilla

import net.aechronis.utils.createTestServer
import net.aechronis.vanilla.listeners.ItemListener
import net.aechronis.vanilla.listeners.MannequinListener
import net.aechronis.vanilla.managers.EnvironmentalDamage
import net.aechronis.vanilla.managers.Mannequin
import net.aechronis.vanilla.managers.Storage
import net.aechronis.vanilla.objects.StorageContents
import net.aechronis.vanilla.serdes.StorageDeserializer
import net.aechronis.vanilla.serdes.StorageSerializer
import net.kyori.adventure.key.Key
import net.kyori.adventure.nbt.BinaryTagIO
import net.kyori.adventure.nbt.BinaryTagTypes
import net.kyori.adventure.text.Component
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.BlockVec
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.EntityCreature
import net.minestom.server.entity.EntityType
import net.minestom.server.entity.EquipmentSlot
import net.minestom.server.entity.GameMode
import net.minestom.server.entity.ItemEntity
import net.minestom.server.entity.Player
import net.minestom.server.entity.PlayerHand
import net.minestom.server.entity.PlayerSkin
import net.minestom.server.event.item.PickupItemEvent
import net.minestom.server.event.player.PlayerDeathEvent
import net.minestom.server.instance.InstanceContainer
import net.minestom.server.instance.anvil.AnvilLoader
import net.minestom.server.instance.block.Block
import net.minestom.server.instance.block.BlockFace
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import net.minestom.server.listener.BlockPlacementListener
import net.minestom.server.network.packet.client.play.ClientPlayerBlockPlacementPacket
import net.minestom.server.network.packet.server.SendablePacket
import net.minestom.server.network.player.GameProfile
import net.minestom.server.network.player.PlayerConnection
import net.minestom.server.potion.Potion
import net.minestom.server.potion.PotionEffect
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.AbstractMap.SimpleImmutableEntry
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VanillaTest {
    private lateinit var instance: InstanceContainer
    private lateinit var pluginRoot: Path
    private var storageDirectoryCreatedOnInit = false

    @BeforeAll
    fun testInit() {
        instance = createTestServer()
        pluginRoot = Files.createTempDirectory("vanilla-test-")

        // init main
        Vanilla.init(VanillaConfig(path = pluginRoot.toString(), playerDataEnabled = false))
        storageDirectoryCreatedOnInit = Files.exists(pluginRoot.resolve("storage"))
    }

    @Test
    fun `fire damages immediately then every ten ticks`() {
        val player = createPlayer(Pos(0.5, 40.0, 0.5))
        instance.setBlock(0, 40, 0, Block.FIRE)

        EnvironmentalDamage.tickPlayer(player)
        assertEquals(19f, player.health)

        repeat(9) { EnvironmentalDamage.tickPlayer(player) }
        assertEquals(19f, player.health)

        EnvironmentalDamage.tickPlayer(player)

        assertEquals(18f, player.health)
        assertEquals(160, player.fireTicks)
        instance.setBlock(0, 40, 0, Block.AIR)
    }

    @Test
    fun `drowning damages after vanilla air supply expires`() {
        val player = createPlayer(Pos(2.5, 40.0, 2.5))
        instance.setBlock(2, 41, 2, Block.WATER)

        repeat(320) { EnvironmentalDamage.tickPlayer(player) }

        assertEquals(18f, player.health)
        assertEquals(0, player.entityMeta.airTicks)
        instance.setBlock(2, 41, 2, Block.AIR)
    }

    @Test
    fun `fire resistance and creative mode prevent environmental damage`() {
        val fireResistant = createPlayer(Pos(4.5, 40.0, 4.5))
        instance.setBlock(4, 40, 4, Block.FIRE)
        fireResistant.addEffect(Potion(PotionEffect.FIRE_RESISTANCE, 0, 100))

        EnvironmentalDamage.tickPlayer(fireResistant)

        assertEquals(20f, fireResistant.health)
        assertEquals(160, fireResistant.fireTicks)

        val creative = createPlayer(Pos(6.5, 40.0, 6.5))
        instance.setBlock(6, 40, 6, Block.FIRE)
        creative.entityMeta.airTicks = 10
        creative.setGameMode(GameMode.CREATIVE)

        EnvironmentalDamage.tickPlayer(creative)

        assertEquals(20f, creative.health)
        assertEquals(0, creative.fireTicks)
        assertEquals(300, creative.entityMeta.airTicks)
        instance.setBlock(4, 40, 4, Block.AIR)
        instance.setBlock(6, 40, 6, Block.AIR)
    }

    @Test
    fun `player pickup adds the complete item stack`() {
        val player = createPlayer(Pos(8.5, 40.0, 4.5))
        val stack = ItemStack.of(Material.DIAMOND, 4).withCustomName(Component.text("Pickup test"))
        val event = PickupItemEvent(player, ItemEntity(stack))

        ItemListener.onPickup(event)

        assertFalse(event.isCancelled)
        assertEquals(stack, player.inventory.getItemStack(0))
        player.remove()
    }

    @Test
    fun `pickup is cancelled when the complete stack does not fit`() {
        val player = createPlayer(Pos(10.5, 40.0, 4.5))
        repeat(36) { player.inventory.setItemStack(it, ItemStack.of(Material.DIRT, 64)) }
        val existing = ItemStack.of(Material.DIAMOND, 63)
        player.inventory.setItemStack(0, existing)
        val event = PickupItemEvent(player, ItemEntity(ItemStack.of(Material.DIAMOND, 2)))

        ItemListener.onPickup(event)

        assertTrue(event.isCancelled)
        assertEquals(existing, player.inventory.getItemStack(0))
        player.remove()
    }

    @Test
    fun `spectators and non-player entities cannot pick up items`() {
        val spectator = createPlayer(Pos(12.5, 40.0, 4.5))
        spectator.setGameMode(GameMode.SPECTATOR)
        val spectatorEvent = PickupItemEvent(spectator, ItemEntity(ItemStack.of(Material.DIAMOND)))
        val creatureEvent =
            PickupItemEvent(
                EntityCreature(EntityType.ZOMBIE),
                ItemEntity(ItemStack.of(Material.EMERALD)),
            )

        ItemListener.onPickup(spectatorEvent)
        ItemListener.onPickup(creatureEvent)

        assertTrue(spectatorEvent.isCancelled)
        assertTrue(spectator.inventory.getItemStack(0).isAir)
        assertTrue(creatureEvent.isCancelled)
        spectator.remove()
    }

    @Test
    fun `death transfers equipped items to the corpse`() {
        val player = createPlayer(Pos(8.5, 40.0, 8.5))
        player.skin = PlayerSkin("textures", "signature")
        val armor =
            mapOf(
                EquipmentSlot.HELMET to ItemStack.of(Material.DIAMOND_HELMET),
                EquipmentSlot.CHESTPLATE to ItemStack.of(Material.DIAMOND_CHESTPLATE),
                EquipmentSlot.LEGGINGS to ItemStack.of(Material.DIAMOND_LEGGINGS),
                EquipmentSlot.BOOTS to ItemStack.of(Material.DIAMOND_BOOTS),
            )
        armor.forEach(player::setEquipment)
        val offhand = ItemStack.of(Material.SHIELD)
        player.setEquipment(EquipmentSlot.OFF_HAND, offhand)
        val existingCorpses = Mannequin.inventories.keys.toSet()

        MannequinListener.onDeath(PlayerDeathEvent(player, Component.empty(), null))

        val corpse = (Mannequin.inventories.keys - existingCorpses).single()
        val loot = Mannequin.inventories.getValue(corpse)
        for ((slot, item) in armor) {
            assertEquals(item, loot.getItemStack(slot.armorSlot()))
            assertEquals(item, corpse.getEquipment(slot))
            assertEquals(ItemStack.AIR, player.getEquipment(slot))
        }
        assertEquals(offhand, loot.getItemStack(45))
        assertEquals(ItemStack.AIR, player.getEquipment(EquipmentSlot.OFF_HAND))

        Mannequin.unregister(corpse)
        corpse.remove()
        player.remove()
    }

    @Test
    fun `corpse armor follows armor remaining in its inventory`() {
        val corpse = EntityCreature(EntityType.MANNEQUIN)
        val loot = Mannequin.newLootInventory("test")
        val equippedHelmet = ItemStack.of(Material.DIAMOND_HELMET)
        val spareHelmet = ItemStack.of(Material.GOLDEN_HELMET)
        loot.setItemStack(EquipmentSlot.HELMET.armorSlot(), equippedHelmet)
        loot.setItemStack(0, spareHelmet)
        Mannequin.register(corpse, loot)

        assertEquals(equippedHelmet, corpse.getEquipment(EquipmentSlot.HELMET))

        loot.setItemStack(EquipmentSlot.HELMET.armorSlot(), ItemStack.AIR)
        assertEquals(spareHelmet, corpse.getEquipment(EquipmentSlot.HELMET))

        loot.setItemStack(0, ItemStack.AIR)
        assertEquals(ItemStack.AIR, corpse.getEquipment(EquipmentSlot.HELMET))

        loot.setItemStack(10, equippedHelmet)
        assertEquals(equippedHelmet, corpse.getEquipment(EquipmentSlot.HELMET))

        Mannequin.unregister(corpse)
    }

    @Test
    fun `storage init does not create a sidecar directory`() {
        assertFalse(storageDirectoryCreatedOnInit)
    }

    @Test
    fun `legacy sidecar migrates all 54 slots into barrel nbt`() {
        val pos = BlockVec(20, 40, 20)
        val key = Storage.keyFor(instance, pos)
        val legacyContents = StorageContents()
        val expected = ItemStack.of(Material.DIAMOND, 7)
        legacyContents.inventory.setItemStack(53, expected)
        val file =
            pluginRoot
                .resolve("storage")
                .resolve(instance.uuid.toString())
                .resolve("20_40_20.dat")
        Files.createDirectories(file.parent)
        Files.newOutputStream(file).use { output ->
            BinaryTagIO.writer().writeNamed(
                SimpleImmutableEntry("", StorageSerializer.serialize(legacyContents.inventory)),
                output,
                BinaryTagIO.Compression.GZIP,
            )
        }
        instance.setBlock(pos, Block.BARREL)

        val migrated = Storage.loadOrCreate(key)

        assertEquals(expected, migrated.inventory.getItemStack(53))
        assertTrue(instance.getBlock(pos).nbtOrEmpty().contains(StorageSerializer.ITEMS_KEY, BinaryTagTypes.LIST))
        assertFalse(Files.exists(file))
        assertTrue(Files.exists(file.resolveSibling("${file.fileName}.migrated")))
        Storage.remove(key)
        instance.setBlock(pos, Block.AIR)
        instance.setBlock(pos, Block.BARREL)
        val replacement = Storage.loadOrCreate(key)
        assertTrue(replacement.inventory.getItemStack(53).isAir)
        Storage.remove(key)
        instance.setBlock(pos, Block.AIR)
    }

    @Test
    fun `open barrel changes update block nbt before close`() {
        val pos = BlockVec(24, 40, 20)
        val key = Storage.keyFor(instance, pos)
        instance.setBlock(pos, Block.BARREL)
        val contents = Storage.loadOrCreate(key)
        val expected = ItemStack.of(Material.GOLD_INGOT, 9)

        contents.inventory.setItemStack(42, expected)

        val saved = StorageDeserializer.deserialize(instance.getBlock(pos).nbtOrEmpty())
        assertEquals(expected, saved.inventory.getItemStack(42))
        Storage.remove(key)
        instance.setBlock(pos, Block.AIR)
    }

    @Test
    fun `anvil reload preserves barrel nbt handler state and high slots`() {
        val worldRoot = Files.createTempDirectory("vanilla-anvil-test-")
        val dimension = Key.key("minecraft:overworld")
        val instanceManager = MinecraftServer.getInstanceManager()
        var first: InstanceContainer? = null
        var second: InstanceContainer? = null
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
            val x = 30 + index * 4
            assertStationInteraction(station, BlockVec(x, 40, 30), PlayerHand.MAIN, sneaking = false, places = false)
            assertStationInteraction(station, BlockVec(x, 40, 34), PlayerHand.OFF, sneaking = false, places = false)
        }
    }

    @Test
    fun `sneaking with an item places against stations`() {
        val stations = listOf(Block.BARREL, Block.STONECUTTER, Block.CRAFTING_TABLE)
        for ((index, station) in stations.withIndex()) {
            assertStationInteraction(station, BlockVec(50 + index * 4, 40, 30), PlayerHand.MAIN, sneaking = true, places = true)
        }
    }

    private fun assertStationInteraction(
        station: Block,
        pos: BlockVec,
        hand: PlayerHand,
        sneaking: Boolean,
        places: Boolean,
    ) {
        instance.setBlock(pos, station)
        instance.setBlock(pos.add(0, 1, 0), Block.AIR)
        val player = createPlayer(Pos(pos.x() + 0.5, 41.0, pos.z() + 2.5))
        val held = ItemStack.of(Material.DIRT, 2)
        player.setItemInHand(hand, held)
        player.isSneaking = sneaking

        BlockPlacementListener.listener(
            ClientPlayerBlockPlacementPacket(
                hand,
                pos,
                BlockFace.TOP,
                0.5f,
                1f,
                0.5f,
                false,
                false,
                1,
            ),
            player,
        )

        if (places) {
            assertTrue(instance.getBlock(pos.add(0, 1, 0)).compare(Block.DIRT))
            assertEquals(1, player.getItemInHand(hand).amount())
            assertNull(player.openInventory)
        } else {
            assertTrue(instance.getBlock(pos.add(0, 1, 0)).isAir)
            assertEquals(held, player.getItemInHand(hand))
            assertNotNull(player.openInventory)
        }
        player.closeInventory()
        player.remove()
        instance.setBlock(pos, Block.AIR)
        instance.setBlock(pos.add(0, 1, 0), Block.AIR)
        Storage.remove(Storage.keyFor(instance, pos))
    }

    private fun createPlayer(position: Pos): Player {
        val player = Player(TestConnection(), GameProfile(UUID.randomUUID(), "test"))
        player.setInstance(instance, position).join()
        player.health = 20f
        return player
    }

    private class TestConnection : PlayerConnection() {
        override fun sendPacket(packet: SendablePacket) = Unit

        override fun getRemoteAddress(): SocketAddress = InetSocketAddress(0)
    }

    @AfterAll
    fun keepRunning() {
        // if -DkeepRunning=true is set keep server running for manual testing
        if (System.getProperty("keepRunning") == "true") {
            Thread.currentThread().join()
        }
        pluginRoot.toFile().deleteRecursively()
    }
}
