package net.aechronis.vanilla.objects

import net.minestom.server.entity.Player
import net.minestom.server.inventory.Inventory
import net.minestom.server.inventory.InventoryType
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import net.minestom.server.network.packet.server.SendablePacket
import net.minestom.server.network.player.GameProfile
import net.minestom.server.network.player.PlayerConnection
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecipesWorkspaceTest {
    @Test
    fun `recipe book placement fills a shaped grid and refreshes its result`() {
        val player = player()
        val inventory = Inventory(InventoryType.CRAFTING, "Crafting")
        val recipe =
            Shaped(
                2,
                2,
                Array(4) { RecipesIngredient.of(Material.OAK_PLANKS) },
                ItemStack.of(Material.CRAFTING_TABLE),
            )
        val workspace = workspace(inventory, listOf(recipe))
        player.inventory.setItemStack(0, ItemStack.of(Material.OAK_PLANKS, 4))

        assertTrue(workspace.fillFromRecipeBook(player, recipe, makeAll = false))
        assertEquals(ItemStack.AIR, player.inventory.getItemStack(0))
        assertEquals(1, inventory.getItemStack(1).amount())
        assertEquals(1, inventory.getItemStack(2).amount())
        assertEquals(1, inventory.getItemStack(4).amount())
        assertEquals(1, inventory.getItemStack(5).amount())
        assertEquals(Material.CRAFTING_TABLE, inventory.getItemStack(0).material())
    }

    @Test
    fun `recipe book placement is atomic when inputs are missing`() {
        val player = player()
        val inventory = Inventory(InventoryType.CRAFTING, "Crafting")
        val recipe = RecipesShapeless(listOf(ingredient(Material.OAK_LOG)), ItemStack.of(Material.OAK_PLANKS, 4))
        val workspace = workspace(inventory, listOf(recipe))
        player.inventory.setItemStack(0, ItemStack.of(Material.STONE))
        inventory.setItemStack(1, ItemStack.of(Material.DIRT))

        assertFalse(workspace.fillFromRecipeBook(player, recipe, makeAll = false))
        assertEquals(Material.STONE, player.inventory.getItemStack(0).material())
        assertEquals(Material.DIRT, inventory.getItemStack(1).material())
        assertTrue(inventory.getItemStack(0).isAir)
    }

    @Test
    fun `make all fills the largest craftable batch`() {
        val player = player()
        val inventory = Inventory(InventoryType.CRAFTING, "Crafting")
        val recipe = RecipesShapeless(listOf(ingredient(Material.OAK_LOG)), ItemStack.of(Material.OAK_PLANKS, 4))
        val workspace = workspace(inventory, listOf(recipe))
        player.inventory.setItemStack(0, ItemStack.of(Material.OAK_LOG, 12))

        assertTrue(workspace.fillFromRecipeBook(player, recipe, makeAll = true))
        assertTrue(player.inventory.getItemStack(0).isAir)
        assertEquals(12, inventory.getItemStack(1).amount())
        assertEquals(Material.OAK_PLANKS, inventory.getItemStack(0).material())
    }

    private fun workspace(
        inventory: Inventory,
        recipes: List<Recipe>,
    ) = RecipesWorkspace(inventory, 0, intArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9), 3, 3, recipes)

    private fun ingredient(material: Material): RecipesIngredient = checkNotNull(RecipesIngredient.of(material))

    private fun player(): Player = Player(TestConnection(), GameProfile(UUID.randomUUID(), "test"))

    private class TestConnection : PlayerConnection() {
        override fun sendPacket(packet: SendablePacket) = Unit

        override fun getRemoteAddress(): SocketAddress = InetSocketAddress(0)
    }
}
