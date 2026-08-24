package net.aechronis.vanilla.managers

import net.aechronis.vanilla.ManagerTest
import net.aechronis.vanilla.objects.Recipe
import net.aechronis.vanilla.objects.RecipeBookRecipe
import net.aechronis.vanilla.objects.RecipesGrid
import net.aechronis.vanilla.objects.RecipesResult
import net.aechronis.vanilla.objects.RecipesShapeless
import net.aechronis.vanilla.objects.Shaped
import net.minestom.server.MinecraftServer
import net.minestom.server.entity.Player
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

class RecipesTest : ManagerTest() {
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
        assertEquals(1, displays.count { it is RecipeDisplay.CraftingShaped })
        assertEquals(1, displays.count { it is RecipeDisplay.CraftingShapeless })
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
            Recipes.recipes.mapNotNull { recipe ->
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
