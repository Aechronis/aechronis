package net.aechronis.vanilla.managers

import net.aechronis.vanilla.Vanilla
import net.aechronis.vanilla.listeners.RecipesListener
import net.aechronis.vanilla.objects.Recipe
import net.aechronis.vanilla.objects.RecipeBookRecipe
import net.aechronis.vanilla.objects.RecipesIngredient
import net.aechronis.vanilla.objects.RecipesShapeless
import net.aechronis.vanilla.objects.RecipesWorkspace
import net.aechronis.vanilla.objects.Shaped
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.MinecraftServer
import net.minestom.server.entity.Player
import net.minestom.server.event.inventory.InventoryPreClickEvent
import net.minestom.server.inventory.AbstractInventory
import net.minestom.server.inventory.Inventory
import net.minestom.server.inventory.InventoryType
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import net.minestom.server.recipe.display.RecipeDisplay

object Recipes {
    val recipes: MutableList<Recipe> = ArrayList()
    val workspaces: HashMap<AbstractInventory, RecipesWorkspace> = HashMap()
    private val recipeBookRecipes: MutableList<RecipeBookRecipe> = ArrayList()
    private val recipesByDisplay: MutableMap<RecipeDisplay, Recipe> = HashMap()
    private val recipeBrowsers: MutableMap<Inventory, Int> = HashMap()

    private const val BROWSER_CONTENT_END_SLOT = 44
    private const val BROWSER_PREVIOUS_PAGE_SLOT = 45
    private const val BROWSER_PAGE_SLOT = 47
    private const val BROWSER_NEXT_PAGE_SLOT = 53
    private const val BROWSER_ENTRIES_PER_PAGE = BROWSER_CONTENT_END_SLOT + 1

    /** Resolves a client-selected recipe-book display to its server-side recipe. */
    fun recipeForDisplay(display: RecipeDisplay): Recipe? = recipesByDisplay[display]

    /** Opens a read-only paginated catalogue of configured crafts and converter cycles. */
    fun openRecipeBrowser(player: Player) {
        // The browser can be enabled with block conversion alone, without Recipes.init().
        RecipesListener.init()

        val inventory = Inventory(InventoryType.CHEST_6_ROW, Component.text("Recipes"))
        recipeBrowsers[inventory] = 0
        refreshRecipeBrowser(inventory)
        player.openInventory(inventory)
    }

    /** Cancels interaction with a recipe browser and handles its page controls. */
    internal fun handleRecipeBrowserClick(event: InventoryPreClickEvent): Boolean {
        val browser = event.player.openInventory as? Inventory ?: return false
        if (browser !in recipeBrowsers) return false

        event.isCancelled = true
        if (event.inventory !== browser) return true

        when (event.slot) {
            BROWSER_PREVIOUS_PAGE_SLOT -> setRecipeBrowserPage(browser, (recipeBrowsers[browser] ?: 0) - 1)
            BROWSER_NEXT_PAGE_SLOT -> setRecipeBrowserPage(browser, (recipeBrowsers[browser] ?: 0) + 1)
        }
        return true
    }

    internal fun closeRecipeBrowser(inventory: AbstractInventory) {
        if (inventory is Inventory) recipeBrowsers.remove(inventory)
    }

    private fun setRecipeBrowserPage(
        inventory: Inventory,
        page: Int,
    ) {
        recipeBrowsers[inventory] = page
        refreshRecipeBrowser(inventory)
    }

    private fun refreshRecipeBrowser(inventory: Inventory) {
        val entries =
            recipeBrowserEntries(
                if (Vanilla.config.recipesEnabled) recipes else emptyList(),
                if (Vanilla.config.blocksEnabled) Vanilla.config.blocksConfig.converterCycles else emptyList(),
            )
        val pageCount = maxOf(1, (entries.size + BROWSER_ENTRIES_PER_PAGE - 1) / BROWSER_ENTRIES_PER_PAGE)
        val page = (recipeBrowsers[inventory] ?: 0).coerceIn(0, pageCount - 1)
        recipeBrowsers[inventory] = page

        for (slot in 0..BROWSER_CONTENT_END_SLOT) {
            inventory.setItemStack(slot, entries.getOrNull(page * BROWSER_ENTRIES_PER_PAGE + slot) ?: ItemStack.AIR)
        }
        inventory.setItemStack(
            BROWSER_PREVIOUS_PAGE_SLOT,
            if (page > 0) ItemStack.of(Material.ARROW).withCustomName(Component.text("Previous page")) else ItemStack.AIR,
        )
        inventory.setItemStack(
            BROWSER_PAGE_SLOT,
            ItemStack.of(Material.PAPER).withCustomName(Component.text("Page ${page + 1}/$pageCount")),
        )
        inventory.setItemStack(
            BROWSER_NEXT_PAGE_SLOT,
            if (page < pageCount - 1) ItemStack.of(Material.ARROW).withCustomName(Component.text("Next page")) else ItemStack.AIR,
        )
        for (slot in 46..52) {
            if (slot != BROWSER_PAGE_SLOT) inventory.setItemStack(slot, ItemStack.AIR)
        }
    }

    internal fun recipeBrowserEntries(
        configuredRecipes: List<Recipe>,
        converterCycles: List<List<Material>>,
    ): List<ItemStack> =
        buildList {
            configuredRecipes.mapNotNullTo(this, ::recipeBrowserEntry)
            converterCycles.mapNotNullTo(this, ::converterCycleBrowserEntry)
        }

    private fun recipeBrowserEntry(recipe: Recipe): ItemStack? =
        when (recipe) {
            is Shaped ->
                recipe.output
                    .withCustomName(Component.text("Craft: ${materialName(recipe.output.material())}", NamedTextColor.GOLD))
                    .withLore(
                        listOf(
                            Component.text("Shaped ${recipe.patternWidth}x${recipe.patternHeight}", NamedTextColor.GRAY),
                            Component.text("Ingredients:", NamedTextColor.GRAY),
                        ) + recipe.pattern.filterNotNull().map(::ingredientLine),
                    )
            is RecipesShapeless ->
                recipe.output
                    .withCustomName(Component.text("Craft: ${materialName(recipe.output.material())}", NamedTextColor.GOLD))
                    .withLore(
                        listOf(
                            Component.text("Shapeless", NamedTextColor.GRAY),
                            Component.text("Ingredients:", NamedTextColor.GRAY),
                        ) + recipe.recipesIngredients.map(::ingredientLine),
                    )
            else -> null
        }

    private fun converterCycleBrowserEntry(cycle: List<Material>): ItemStack? {
        val materials = cycle.distinct()
        if (materials.size < 2) return null

        return ItemStack
            .of(materials.first())
            .withCustomName(Component.text("Converter cycle", NamedTextColor.AQUA))
            .withLore(
                listOf(
                    Component.text("Convert any material into another:", NamedTextColor.GRAY),
                ) + materials.map { Component.text(materialName(it), NamedTextColor.WHITE) },
            )
    }

    private fun ingredientLine(ingredient: RecipesIngredient): Component =
        Component.text("- ${ingredient.materials.joinToString(" / ", transform = ::materialName)}", NamedTextColor.WHITE)

    private fun materialName(material: Material): String =
        material
            .name()
            .lowercase()
            .split('_')
            .joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

    fun init() {
        // measure load time
        val timeStart = System.currentTimeMillis()
        RecipesListener.init()

        val recipeManager = MinecraftServer.getRecipeManager()
        recipeBookRecipes.forEach(recipeManager::removeRecipe)
        recipeBookRecipes.clear()
        recipesByDisplay.clear()

        recipes.clear()
        recipes.addAll(Vanilla.config.recipesConfig.recpies)
        recipes.mapTo(recipeBookRecipes, ::RecipeBookRecipe)
        for (recipeBookRecipe in recipeBookRecipes) {
            for (display in recipeBookRecipe.displays) {
                check(recipesByDisplay.put(display, recipeBookRecipe.source) == null) {
                    "Duplicate recipe-book display for ${recipeBookRecipe.source}"
                }
            }
            recipeManager.addRecipe(recipeBookRecipe)
        }

        MinecraftServer.getConnectionManager().onlinePlayers.forEach { it.refreshRecipes() }

        val timeEnd = System.currentTimeMillis()
        val timeLoad = timeEnd - timeStart
        println("├─ Recpies enabled in ${timeLoad}ms")
    }
}
