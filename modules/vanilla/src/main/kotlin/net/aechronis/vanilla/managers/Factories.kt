package net.aechronis.vanilla.managers

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import net.aechronis.utils.hasPermission
import net.aechronis.vanilla.Vanilla
import net.aechronis.vanilla.listeners.FactoriesListener
import net.aechronis.vanilla.objects.Factory
import net.aechronis.vanilla.objects.FactoryRecipe
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.minestom.server.MinecraftServer
import net.minestom.server.component.DataComponents
import net.minestom.server.coordinate.BlockVec
import net.minestom.server.entity.Player
import net.minestom.server.event.inventory.InventoryCloseEvent
import net.minestom.server.event.inventory.InventoryPreClickEvent
import net.minestom.server.instance.Instance
import net.minestom.server.instance.block.Block
import net.minestom.server.inventory.Inventory
import net.minestom.server.inventory.InventoryType
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import net.minestom.server.tag.Tag
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

object Factories {
    private const val FACTORY_ITEM_TAG_KEY = "aechronis:factory"
    private const val FACTORY_TIER_TAG_KEY = "aechronis:factory_tier"
    private val factoryItemTag = Tag.String(FACTORY_ITEM_TAG_KEY)
    private val factoryTierTag = Tag.Integer(FACTORY_TIER_TAG_KEY)
    private val factoryBlock = Block.FURNACE
    private val gson = GsonBuilder().setPrettyPrinting().create()

    private data class Location(
        val world: String,
        val x: Int,
        val y: Int,
        val z: Int,
    )

    private data class PlacedFactory(
        val name: String,
        var tier: Int,
        var startedAt: Long? = null,
        var selectedRecipe: String? = null,
    )

    private data class SavedFactory(
        val world: String,
        val x: Int,
        val y: Int,
        val z: Int,
        val name: String,
        val tier: Int,
        val startedAt: Long? = null,
        val selectedRecipe: String? = null,
    )

    private data class LoadableFactory(
        val saved: SavedFactory,
        val factory: Factory,
        val instance: Instance,
    )

    private data class FactoryChunk(
        val instance: Instance,
        val x: Int,
        val z: Int,
    )

    private val definitions = linkedMapOf<String, Factory>()
    private val placed = ConcurrentHashMap<Location, PlacedFactory>()
    private val openRecipeMenus = ConcurrentHashMap<Inventory, Location>()

    // Persistence snapshots are cheap (in-memory map copy); the actual disk write is not, so it
    // runs on a dedicated single thread (preserving write order) instead of blocking whichever
    // interaction thread triggered the save.
    private val saveExecutor =
        Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "Factories-Save").apply { isDaemon = true } }
    private lateinit var file: Path

    fun init(path: Path) {
        require(Vanilla.config.factoryBuildTimeSeconds > 0) { "Factory build time must be positive" }
        file = path
        Files.createDirectories(path.parent)
        definitions.clear()
        Vanilla.config.factories.map(::validate).forEach { factory ->
            require(definitions.put(factory.name, factory) == null) { "Factory names must be unique: ${factory.name}" }
        }
        load()
        FactoriesListener.init()
        println("├─ Factories enabled (${placed.size} placed, ${definitions.size} configured)")
    }

    /** Blocks until every save queued before this call has reached disk, then writes the current state directly. */
    fun saveAll() {
        saveExecutor.submit {}.get()
        writeToDisk(snapshot())
    }

    fun shutdown() {
        saveExecutor.shutdown()
    }

    fun itemFor(
        name: String,
        tier: Int,
    ): ItemStack? {
        val factory = definitions[name] ?: return null
        if (tier !in 1..factory.maxTier) return null
        return ItemStack
            .of(Material.FURNACE)
            .withCustomName(factory.itemName)
            .withLore(listOf(Component.text("Tier $tier", NamedTextColor.GRAY)))
            .withTag(factoryItemTag, factory.name)
            .withTag(factoryTierTag, tier)
            .withMaxStackSize(1)
    }

    fun onPlace(
        player: Player,
        instance: Instance,
        position: BlockVec,
        item: ItemStack,
    ): Boolean {
        if (item.material() != Material.FURNACE) return false
        val factory = definitions[item.getTag(factoryItemTag)] ?: return false
        val tier = item.getTag(factoryTierTag) ?: return false
        if (tier !in 1..factory.maxTier) return false

        val location = location(instance, position)
        placed[location] = PlacedFactory(factory.name, tier)
        save()
        player.sendMessage(Component.text("Placed ${factory.name} factory (tier $tier).", NamedTextColor.GREEN))
        return true
    }

    fun onBreak(
        player: Player,
        instance: Instance,
        position: BlockVec,
    ): Boolean {
        val location = location(instance, position)
        val factory = placed[location] ?: return false
        if (!player.hasPermission("vanilla.factory")) {
            player.sendMessage(Component.text("You do not have permission to break factories.", NamedTextColor.RED))
            return true
        }

        placed.remove(location)
        instance.setBlock(position, Block.AIR)
        itemFor(factory.name, factory.tier)?.let { item ->
            if (!player.inventory.addItemStack(item)) player.dropItem(item)
        }
        save()
        return true
    }

    fun onInteract(
        player: Player,
        instance: Instance,
        position: BlockVec,
    ): Boolean {
        val location = location(instance, position)
        val state = placed[location] ?: return false
        val factory = definitions[state.name] ?: return false
        val startedAt = state.startedAt
        val now = System.currentTimeMillis()

        if (startedAt != null) {
            val completeAt = startedAt + Vanilla.config.factoryBuildTimeSeconds * 1_000L
            if (now < completeAt) {
                val remainingSeconds = (completeAt - now + 999L) / 1_000L
                player.sendMessage(Component.text("Factory is building; $remainingSeconds seconds remaining.", NamedTextColor.YELLOW))
                return true
            }

            val recipe = factory.recipes.getValue(state.tier).firstOrNull { it.name == state.selectedRecipe }
            if (recipe == null) {
                state.startedAt = null
                state.selectedRecipe = null
                save()
                player.sendMessage(Component.text("This factory's recipe is no longer available.", NamedTextColor.RED))
                return true
            }

            recipe.output.forEach { stack ->
                if (!stack.isAir && !player.inventory.addItemStack(stack)) player.dropItem(stack)
            }
            state.startedAt = null
            state.selectedRecipe = null
            save()
            player.sendMessage(Component.text("Factory production complete.", NamedTextColor.GREEN))
            return true
        }

        val recipes = factory.recipes.getValue(state.tier)
        if (recipes.size > 1) {
            openRecipeMenu(player, location, factory, recipes)
            return true
        }

        startRecipe(player, state, recipes[0])
        return true
    }

    private fun startRecipe(
        player: Player,
        state: PlacedFactory,
        recipe: FactoryRecipe,
    ) {
        val missing = missingItems(player, recipe.input)
        if (missing.isNotEmpty()) {
            player.sendMessage(Component.text("Factory requires: ${formatItems(missing)}", NamedTextColor.RED))
            return
        }

        consumeItems(player, recipe.input)
        state.startedAt = System.currentTimeMillis()
        state.selectedRecipe = recipe.name
        save()
        player.sendMessage(
            Component
                .text("Factory building started (")
                .append(recipe.displayName)
                .append(
                    Component.text(
                        "). It will finish in ${Vanilla.config.factoryBuildTimeSeconds / 60} minutes.",
                    ),
                ).color(NamedTextColor.GREEN),
        )
    }

    private fun openRecipeMenu(
        player: Player,
        location: Location,
        factory: Factory,
        recipes: List<FactoryRecipe>,
    ) {
        val inventory = Inventory(InventoryType.CHEST_1_ROW, factory.itemName)
        recipes.forEachIndexed { index, recipe -> inventory.setItemStack(index, recipeDisplay(recipe)) }
        openRecipeMenus[inventory] = location
        player.openInventory(inventory)
    }

    private fun recipeDisplay(recipe: FactoryRecipe): ItemStack {
        val display = recipe.output.firstOrNull { !it.isAir } ?: return ItemStack.AIR
        val lore =
            listOf(Component.text("Requires:", NamedTextColor.GRAY)) +
                recipe.input.map { stack -> Component.text("- ${formatItems(listOf(stack))}", NamedTextColor.GRAY) } +
                listOf(Component.text("Click to build", NamedTextColor.GREEN))
        return display.withLore(lore)
    }

    fun onRecipeMenuClick(event: InventoryPreClickEvent): Boolean {
        val inventory = event.inventory as? Inventory ?: return false
        val location = openRecipeMenus[inventory] ?: return false
        event.isCancelled = true

        val state = placed[location] ?: return true
        val factory = definitions[state.name] ?: return true
        if (state.startedAt != null) return true

        val recipes = factory.recipes.getValue(state.tier)
        val recipe = recipes.getOrNull(event.slot) ?: return true

        startRecipe(event.player, state, recipe)
        event.player.closeInventory()
        return true
    }

    fun onRecipeMenuClose(event: InventoryCloseEvent) {
        val inventory = event.inventory as? Inventory ?: return
        openRecipeMenus.remove(inventory)
    }

    fun promote(
        player: Player,
        tier: Int,
    ): String? {
        val position = player.getTargetBlockPosition(MAX_REACH)?.asBlockVec() ?: return "Look at a factory block."
        val instance = player.instance ?: return "Look at a factory block."
        val state = placed[location(instance, position)] ?: return "Look at a factory block."
        val factory = definitions[state.name] ?: return "That factory type is no longer configured."
        if (tier !in 1..factory.maxTier) return "Tier must be between 1 and ${factory.maxTier}."
        if (state.startedAt != null) return "Cannot promote a factory while it is building."

        state.tier = tier
        save()
        return "Promoted ${factory.name} factory to tier $tier."
    }

    fun isFactory(
        instance: Instance,
        position: BlockVec,
    ): Boolean = placed.containsKey(location(instance, position))

    fun blocksExplosion(
        instance: Instance,
        position: BlockVec,
    ): Boolean = isFactory(instance, position)

    private fun missingItems(
        player: Player,
        required: List<ItemStack>,
    ): List<ItemStack> {
        val available = player.inventory.itemStacks.toMutableList()
        return required.mapNotNull { requiredStack ->
            var remaining = requiredStack.amount()
            available.indices.forEach { index ->
                val availableStack = available[index]
                if (!availableStack.isSimilar(requiredStack) || remaining == 0) return@forEach
                val taken = minOf(remaining, availableStack.amount())
                remaining -= taken
                available[index] = availableStack.consume(taken)
            }
            if (remaining == 0) null else requiredStack.withAmount(remaining)
        }
    }

    private fun consumeItems(
        player: Player,
        required: List<ItemStack>,
    ) {
        required.forEach { requiredStack ->
            var remaining = requiredStack.amount()
            for (slot in 0..<player.inventory.size) {
                if (remaining == 0) break
                val current = player.inventory.getItemStack(slot)
                if (!current.isSimilar(requiredStack)) continue
                val taken = minOf(remaining, current.amount())
                player.inventory.setItemStack(slot, current.consume(taken))
                remaining -= taken
            }
            check(remaining == 0) { "Factory input disappeared while starting production" }
        }
    }

    private fun formatItems(items: List<ItemStack>): String =
        items.joinToString(", ") { stack ->
            val name =
                stack.get(DataComponents.CUSTOM_NAME)?.let { PlainTextComponentSerializer.plainText().serialize(it) }
                    ?: stack
                        .material()
                        .name()
                        .lowercase()
                        .replace('_', ' ')
            "${stack.amount()} $name"
        }

    private fun validate(factory: Factory): Factory {
        require(factory.name.isNotBlank()) { "Factory name cannot be blank" }
        require(factory.maxTier > 0) { "Factory ${factory.name} must have at least one tier" }
        for (tier in 1..factory.maxTier) {
            val recipes = factory.recipes[tier]
            require(!recipes.isNullOrEmpty()) { "Factory ${factory.name} is missing recipes for tier $tier" }
            require(recipes.size <= MAX_RECIPES_PER_TIER) {
                "Factory ${factory.name} tier $tier has ${recipes.size} recipes; the selection menu only holds $MAX_RECIPES_PER_TIER"
            }
            recipes.forEach { recipe ->
                require(recipe.name.isNotBlank()) { "Factory ${factory.name} tier $tier has a blank recipe name" }
                require(recipe.input.isNotEmpty()) { "Factory ${factory.name} recipe ${recipe.name} has no input" }
                require(recipe.output.isNotEmpty()) { "Factory ${factory.name} recipe ${recipe.name} has no output" }
                require(recipe.input.none { it.isAir || it.amount() <= 0 }) {
                    "Factory ${factory.name} recipe ${recipe.name} has an invalid input"
                }
                require(recipe.output.none { it.isAir || it.amount() <= 0 }) {
                    "Factory ${factory.name} recipe ${recipe.name} has an invalid output"
                }
            }
            require(recipes.map { it.name }.toSet().size == recipes.size) {
                "Factory ${factory.name} tier $tier has duplicate recipe names"
            }
        }
        require(factory.recipes.keys.all { it in 1..factory.maxTier }) { "Factory ${factory.name} has an invalid recipe tier" }
        return factory
    }

    private fun location(
        instance: Instance,
        position: BlockVec,
    ) = Location(instance.getDimensionName(), position.blockX(), position.blockY(), position.blockZ())

    private fun load() {
        placed.clear()
        if (!Files.exists(file)) return
        val type = object : TypeToken<List<SavedFactory>>() {}.type
        val saved =
            runCatching {
                Files.newBufferedReader(file).use { reader -> gson.fromJson<List<SavedFactory>>(reader, type).orEmpty() }
            }.getOrElse { error ->
                System.err.println("Failed to load factories: ${error.message}")
                emptyList()
            }

        val instances = MinecraftServer.getInstanceManager().instances.associateBy { it.getDimensionName() }
        val loadable =
            saved.mapNotNull { entry ->
                val factory = definitions[entry.name] ?: return@mapNotNull null
                if (entry.tier !in 1..factory.maxTier) return@mapNotNull null
                val instance = instances[entry.world]
                if (instance == null) {
                    System.err.println("Factory at ${entry.x}, ${entry.y}, ${entry.z} has an unavailable world: ${entry.world}")
                    return@mapNotNull null
                }
                LoadableFactory(entry, factory, instance)
            }

        val chunks =
            loadable
                .map { entry -> FactoryChunk(entry.instance, entry.saved.x shr 4, entry.saved.z shr 4) }
                .distinct()
        val chunkLoads = chunks.map { chunk -> chunk.instance.loadChunk(chunk.x, chunk.z) }
        awaitChunkLoads(chunkLoads)

        loadable.forEach { (entry, factory, instance) ->
            if (!instance.getBlock(entry.x, entry.y, entry.z).compare(factoryBlock)) return@forEach
            val recipeValid = factory.recipes.getValue(entry.tier).any { it.name == entry.selectedRecipe }
            val startedAt = entry.startedAt.takeIf { it != null && recipeValid }
            val selectedRecipe = entry.selectedRecipe.takeIf { startedAt != null }
            placed[Location(entry.world, entry.x, entry.y, entry.z)] = PlacedFactory(entry.name, entry.tier, startedAt, selectedRecipe)
        }
    }

    internal fun awaitChunkLoads(
        loads: Collection<CompletableFuture<*>>,
        timeout: Long = FACTORY_CHUNK_LOAD_TIMEOUT_SECONDS,
        unit: TimeUnit = TimeUnit.SECONDS,
    ) {
        try {
            CompletableFuture.allOf(*loads.toTypedArray()).get(timeout, unit)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("Interrupted while loading saved factory chunks", error)
        } catch (error: TimeoutException) {
            throw IllegalStateException(
                "Saved factory chunks did not load within $timeout ${unit.name.lowercase()}",
                error,
            )
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        }
    }

    private fun snapshot(): List<SavedFactory> =
        placed.map { (location, state) ->
            SavedFactory(
                location.world,
                location.x,
                location.y,
                location.z,
                state.name,
                state.tier,
                state.startedAt,
                state.selectedRecipe,
            )
        }

    private fun writeToDisk(saved: List<SavedFactory>) {
        AtomicFiles.write(file) { writer -> gson.toJson(saved, writer) }
    }

    private fun save() {
        val saved = snapshot()
        saveExecutor.execute { writeToDisk(saved) }
    }

    private const val MAX_REACH = 6
    private const val FACTORY_CHUNK_LOAD_TIMEOUT_SECONDS = 60L
    private const val MAX_RECIPES_PER_TIER = 9 // matches InventoryType.CHEST_1_ROW's slot count
}
