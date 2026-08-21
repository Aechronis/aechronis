package net.aechronis.vanilla.managers

import net.aechronis.vanilla.ManagerTest
import net.aechronis.vanilla.VanillaTest
import net.aechronis.vanilla.listeners.RecipesListener
import net.aechronis.vanilla.objects.Recipe
import net.aechronis.vanilla.objects.RecipeBookRecipe
import net.aechronis.vanilla.objects.RecipesGrid
import net.aechronis.vanilla.objects.RecipesIngredient
import net.aechronis.vanilla.objects.RecipesResult
import net.aechronis.vanilla.objects.RecipesShapeless
import net.aechronis.vanilla.objects.Shaped
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.Player
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import net.minestom.server.network.packet.server.SendablePacket
import net.minestom.server.network.packet.server.play.RecipeBookAddPacket
import net.minestom.server.network.player.GameProfile
import net.minestom.server.network.player.PlayerConnection
import net.minestom.server.recipe.display.RecipeDisplay
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class RecipesTest : ManagerTest() {
    @Test
    fun `generation handoff rebuilds online player crafting workspace`() {
        val player = VanillaTest.createPlayer(Pos(90.5, 40.0, 8.5))

        try {
            Recipes.workspaces.remove(player.inventory)
            RecipesListener.attachOnlinePlayers(listOf(player))

            assertNotNull(Recipes.workspaces[player.inventory])
        } finally {
            Recipes.workspaces.remove(player.inventory)
            VanillaTest.remove(player)
        }
    }

    @Test
    fun `initialization does not duplicate configured recipes`() {
        val initial = Recipes.recipes.toList()

        Recipes.init()

        assertEquals(initial, Recipes.recipes)
        assertEquals(initial.size, recipeBookRecipes().size)
    }

    @Test
    fun `initialization publishes configured recipes to the crafting book`() {
        Recipes.init()

        val displays = recipeBookRecipes().flatMap(RecipeBookRecipe::createRecipeDisplays)

        assertEquals(Recipes.recipes.size, displays.size)
        assertEquals(3, displays.count { it is RecipeDisplay.CraftingShaped })
        assertEquals(1, displays.count { it is RecipeDisplay.CraftingShapeless })
    }

    @Test
    fun `recipe browser lists every craft and valid converter cycle`() {
        val entries =
            Recipes.recipeBrowserEntries(
                listOf(
                    RecipesShapeless(
                        listOf(checkNotNull(RecipesIngredient.of(Material.OAK_LOG))),
                        ItemStack.of(Material.OAK_PLANKS, 4),
                    ),
                ),
                listOf(
                    listOf(Material.STONE, Material.COBBLESTONE),
                    listOf(Material.DIRT),
                ),
            )

        assertEquals(2, entries.size)
        assertEquals(Material.OAK_PLANKS, entries[0].material())
        assertEquals(Material.STONE, entries[1].material())
    }

    @Test
    fun `server-only recipes do not prevent recipe-book registration`() {
        val recipe = RecipeBookRecipe(ServerOnlyRecipe)

        assertEquals(emptyList(), recipe.createRecipeDisplays())
        MinecraftServer.getRecipeManager().addRecipe(recipe)
        MinecraftServer.getRecipeManager().removeRecipe(recipe)
    }

    @Test
    fun `recipe refresh unlocks every configured recipe for players`() {
        Recipes.init()
        val connection = PacketConnection()
        val player = Player(connection, GameProfile(UUID.randomUUID(), "test"))

        player.refreshRecipes()

        val recipeBookPacket = connection.packets.filterIsInstance<RecipeBookAddPacket>().single()
        assertEquals(true, recipeBookPacket.replace())
        assertEquals(Recipes.recipes.size, recipeBookPacket.entries().size)
        val expectedRequirementCounts =
            Recipes.recipes
                .mapNotNull { recipe ->
                    when (recipe) {
                        is Shaped -> recipe.pattern.count { it != null }
                        is RecipesShapeless -> recipe.recipesIngredients.size
                        else -> null
                    }
                }.sorted()

        assertEquals(
            expectedRequirementCounts,
            recipeBookPacket.entries().mapNotNull { it.craftingRequirements()?.size }.sorted(),
        )
    }

    private fun recipeBookRecipes(): List<RecipeBookRecipe> =
        MinecraftServer.getRecipeManager().recipes.filterIsInstance<RecipeBookRecipe>()

    private object ServerOnlyRecipe : Recipe {
        override fun match(recipesGrid: RecipesGrid): RecipesResult? = null
    }

    private class PacketConnection : PlayerConnection() {
        val packets = mutableListOf<SendablePacket>()

        override fun sendPacket(packet: SendablePacket) {
            packets += packet
        }

        override fun getRemoteAddress(): SocketAddress = InetSocketAddress(0)
    }
}
