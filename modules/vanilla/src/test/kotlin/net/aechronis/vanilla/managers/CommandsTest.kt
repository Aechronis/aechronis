package net.aechronis.vanilla.managers

import net.aechronis.vanilla.ManagerTest
import net.aechronis.vanilla.VanillaTest
import net.aechronis.vanilla.serdes.PlayerDataDeserializer
import net.aechronis.vanilla.serdes.PlayerDataSerializer
import net.kyori.adventure.nbt.BinaryTagTypes
import net.kyori.adventure.nbt.CompoundBinaryTag
import net.kyori.adventure.text.Component
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Pos
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CommandsTest : ManagerTest() {
    @Test
    fun `ender chest is shared by owner and viewer`() {
        val owner = VanillaTest.createPlayer(Pos(62.5, 40.0, 30.5))
        val viewer = VanillaTest.createPlayer(Pos(64.5, 40.0, 30.5))
        val enderChest = Commands.getEnderChest(owner)

        Commands.openEnderChest(viewer, owner)
        enderChest.setItemStack(26, ItemStack.of(Material.DIAMOND, 3))

        assertEquals(27, enderChest.size)
        assertSame(enderChest, viewer.openInventory)
        assertEquals(ItemStack.of(Material.DIAMOND, 3), viewer.openInventory?.getItemStack(26))

        VanillaTest.remove(owner)
        assertNull(viewer.openInventory)
        VanillaTest.remove(viewer)
    }

    @Test
    fun `player data round trips inventory and ender chest separately`() {
        val source = VanillaTest.createPlayer(Pos(66.5, 40.0, 30.5))
        val inventoryItem = ItemStack.of(Material.EMERALD, 5).withCustomName(Component.text("Inventory item"))
        val enderChestItem = ItemStack.of(Material.DIAMOND, 7).withCustomName(Component.text("Ender chest item"))
        val cursorItem = ItemStack.of(Material.GOLD_INGOT, 2)
        source.inventory.setItemStack(4, inventoryItem)
        source.inventory.cursorItem = cursorItem
        Commands.getEnderChest(source).setItemStack(26, enderChestItem)

        val data = PlayerDataSerializer.serialize(source)
        assertTrue(data.contains("EnderChest", BinaryTagTypes.LIST))
        VanillaTest.remove(source)

        val restored = VanillaTest.createPlayer(Pos(68.5, 40.0, 30.5))
        Commands.getEnderChest(restored).setItemStack(0, ItemStack.of(Material.DIRT))
        PlayerDataDeserializer.deserialize(restored, data)

        assertEquals(inventoryItem, restored.inventory.getItemStack(4))
        assertEquals(cursorItem, restored.inventory.cursorItem)
        assertEquals(enderChestItem, Commands.getEnderChest(restored).getItemStack(26))
        assertTrue(Commands.getEnderChest(restored).getItemStack(0).isAir)
        assertTrue(restored.inventory.getItemStack(26).isAir)
        VanillaTest.remove(restored)
    }

    @Test
    fun `legacy player data loads with an empty ender chest`() {
        val player = VanillaTest.createPlayer(Pos(70.5, 40.0, 30.5))

        PlayerDataDeserializer.deserialize(player, CompoundBinaryTag.empty())

        assertTrue(Commands.getEnderChest(player).itemStacks.all { it.isAir })
        VanillaTest.remove(player)
    }

    @Test
    fun `ender chest command and inventory choices are registered`() {
        assertNotNull(MinecraftServer.getCommandManager().getCommand("ec"))
        assertNotNull(MinecraftServer.getCommandManager().getCommand("koth"))
        val invsee = assertNotNull(MinecraftServer.getCommandManager().getCommand("invsee"))
        assertTrue(invsee.syntaxes.any { syntax -> syntax.arguments.any { it.id == "inventory" && it.isOptional } })

        val player = VanillaTest.createPlayer(Pos(72.5, 40.0, 30.5))
        val packet = MinecraftServer.getCommandManager().createDeclareCommandsPacket(player)
        val hasInventoryChoices =
            packet.nodes.any { node ->
                node.name == "player" && node.children.map { packet.nodes[it].name }.containsAll(listOf("inv", "ec"))
            }
        assertTrue(hasInventoryChoices)
        VanillaTest.remove(player)
    }
}
