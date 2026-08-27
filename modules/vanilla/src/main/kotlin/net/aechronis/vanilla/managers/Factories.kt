package net.aechronis.vanilla.managers

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import net.aechronis.utils.hasPermission
import net.aechronis.vanilla.Vanilla
import net.aechronis.vanilla.listeners.FactoriesListener
import net.aechronis.vanilla.objects.Factory
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.BlockVec
import net.minestom.server.entity.Player
import net.minestom.server.instance.Instance
import net.minestom.server.instance.block.Block
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import net.minestom.server.tag.Tag
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

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
    )

    private data class SavedFactory(
        val world: String,
        val x: Int,
        val y: Int,
        val z: Int,
        val name: String,
        val tier: Int,
        val startedAt: Long? = null,
    )

    private val definitions = linkedMapOf<String, Factory>()
    private val placed = ConcurrentHashMap<Location, PlacedFactory>()
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

    fun saveAll() = save()

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
        val state = placed[location(instance, position)] ?: return false
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

            factory.output.getValue(state.tier).forEach { stack ->
                if (!stack.isAir && !player.inventory.addItemStack(stack)) player.dropItem(stack)
            }
            state.startedAt = null
            save()
            player.sendMessage(Component.text("Factory production complete.", NamedTextColor.GREEN))
            return true
        }

        val required = factory.input.getValue(state.tier)
        val missing = missingItems(player, required)
        if (missing.isNotEmpty()) {
            player.sendMessage(Component.text("Factory requires: ${formatItems(missing)}", NamedTextColor.RED))
            return true
        }

        consumeItems(player, required)
        state.startedAt = now
        save()
        player.sendMessage(
            Component.text(
                "Factory building started. It will finish in ${Vanilla.config.factoryBuildTimeSeconds / 60} minutes.",
                NamedTextColor.GREEN,
            ),
        )
        return true
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
        items.joinToString(", ") { stack -> "${stack.amount()} ${stack.material().name().lowercase().replace('_', ' ')}" }

    private fun validate(factory: Factory): Factory {
        require(factory.name.isNotBlank()) { "Factory name cannot be blank" }
        require(factory.maxTier > 0) { "Factory ${factory.name} must have at least one tier" }
        for (tier in 1..factory.maxTier) {
            require(factory.input.containsKey(tier)) { "Factory ${factory.name} is missing input for tier $tier" }
            require(factory.output.containsKey(tier)) { "Factory ${factory.name} is missing output for tier $tier" }
            require(factory.input.getValue(tier).none { it.isAir || it.amount() <= 0 }) {
                "Factory ${factory.name} has an invalid input for tier $tier"
            }
            require(factory.output.getValue(tier).none { it.isAir || it.amount() <= 0 }) {
                "Factory ${factory.name} has an invalid output for tier $tier"
            }
        }
        require(factory.input.keys.all { it in 1..factory.maxTier }) { "Factory ${factory.name} has an invalid input tier" }
        require(factory.output.keys.all { it in 1..factory.maxTier }) { "Factory ${factory.name} has an invalid output tier" }
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

        saved.forEach { entry ->
            val factory = definitions[entry.name] ?: return@forEach
            if (entry.tier !in 1..factory.maxTier) return@forEach
            val instance = MinecraftServer.getInstanceManager().instances.firstOrNull { it.getDimensionName() == entry.world }
            if (instance == null) {
                System.err.println("Factory at ${entry.x}, ${entry.y}, ${entry.z} has an unavailable world: ${entry.world}")
                return@forEach
            }
            // getBlock may only be called after this chunk has been loaded. Loading saved factories runs during startup.
            runCatching { instance.loadChunk(entry.x shr 4, entry.z shr 4).join() }
                .onFailure { error ->
                    System.err.println("Failed to load factory chunk at ${entry.x}, ${entry.z}: ${error.message}")
                    return@forEach
                }
            if (!instance.getBlock(entry.x, entry.y, entry.z).compare(factoryBlock)) return@forEach
            placed[Location(entry.world, entry.x, entry.y, entry.z)] = PlacedFactory(entry.name, entry.tier, entry.startedAt)
        }
    }

    private fun save() {
        val saved =
            placed.map { (location, state) ->
                SavedFactory(location.world, location.x, location.y, location.z, state.name, state.tier, state.startedAt)
            }
        Files.newBufferedWriter(file).use { writer -> gson.toJson(saved, writer) }
    }

    private const val MAX_REACH = 6
}
