package net.aechronis.vanilla.managers

import net.aechronis.vanilla.Vanilla
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material

/** The converter's empty-input pages: chain overview, chain members and chat search results. */
object ConverterBrowse {
    private const val LORE_MEMBERS = 10

    internal fun text(
        text: String,
        color: NamedTextColor,
    ): Component = Component.text(text, color).decoration(TextDecoration.ITALIC, false)

    /** Client-translated block name ("Oak Log"), so it matches what players see everywhere else. */
    private fun fancy(
        material: Material,
        color: NamedTextColor = NamedTextColor.WHITE,
    ): Component = Component.translatable(material.translationKey()).color(color).decoration(TextDecoration.ITALIC, false)

    private fun cycles(): List<List<Material>> =
        Vanilla.config.blocksConfig.converterCycles
            .map { it.distinct() }
            .filter { it.size > 1 }

    private val masterIcon = Material.NETHER_STAR

    /** Icons preferred over a chain's first block, e.g. cobblestone (what players actually hold) over stone. */
    private val preferredIcons = listOf(Material.COBBLESTONE)

    /** The block that stands for [cycle]: a preferred icon, else its first block that no other chain also contains. */
    private fun iconOf(
        cycle: List<Material>,
        all: List<List<Material>>,
    ): Material {
        val unique = cycle.filter { m -> all.count { m in it } == 1 }
        return unique.firstOrNull { it in preferredIcons } ?: unique.firstOrNull() ?: cycle.first()
    }

    /** One representative block per chain; any of them converts into any other. */
    internal fun masterChain(configured: List<List<Material>>): List<Material> {
        val all = configured.map { it.distinct() }.filter { it.size > 1 }
        return all.map { iconOf(it, all) }.distinct()
    }

    private fun master(): List<Material> = masterChain(Vanilla.config.blocksConfig.converterCycles)

    /** Every block that shares a chain with [material], in chain order, including itself. */
    private fun group(material: Material): List<Material> =
        (cycles().filter { material in it } + listOf(master()).filter { material in it })
            .flatten()
            .distinct()

    /** Display names for the configured chains, keyed by a block that only that chain contains. */
    private val chainNames =
        listOf(
            "oak_log" to "Logs & Stems",
            "oak_planks" to "Wood Blocks",
            "crimson_planks" to "Nether Wood Blocks",
            "white_dye" to "Dyes",
            "white_wool" to "Colored Blocks",
            "oak_leaves" to "Plants & Foliage",
            "cobblestone" to "Stone",
            "coarse_dirt" to "Dirt & Moss",
            "red_sand" to "Sand & Sandstone",
            "cobbled_deepslate" to "Deepslate",
            "tuff_bricks" to "Tuff",
            "soul_sand" to "Nether Terrain",
            "polished_blackstone" to "Blackstone",
            "quartz_pillar" to "Quartz",
            "end_stone_bricks" to "End Stone",
            "dark_prismarine" to "Prismarine",
            "calcite" to "Clay & Crystals",
            "exposed_copper" to "Copper",
            "packed_ice" to "Snow & Ice",
            "resin_bricks" to "Resin",
            "soul_lantern" to "Lanterns & Metalwork",
            "soul_campfire" to "Campfires & Lights",
            "ochre_froglight" to "Froglights",
            "brain_coral_block" to "Coral",
            "polished_sulfur" to "Sulfur",
            "polished_cinnabar" to "Cinnabar",
        )

    private fun id(material: Material): String = material.name().substringAfter(':').lowercase()

    /** A readable chain title, falling back to the first block's name for unlisted chains. */
    private fun chainName(materials: List<Material>): String {
        val ids = materials.map(::id).toSet()
        return chainNames.firstOrNull { it.first in ids }?.second ?: "${Recipes.materialName(materials.first())} Set"
    }

    internal val helpItem: ItemStack =
        ItemStack.of(Material.BOOK).withCustomName(text("How it works", NamedTextColor.GOLD)).withLore(
            listOf(
                text("1. Put blocks in the top row", NamedTextColor.GRAY),
                text("    or shift-click them in.", NamedTextColor.DARK_GRAY),
                text("2. Click the block you want.", NamedTextColor.GRAY),
                text("3. The whole row converts 1:1.", NamedTextColor.GRAY),
                Component.empty(),
                text("Only blocks from the same chain mix.", NamedTextColor.DARK_GRAY),
                text("The first block you place picks it.", NamedTextColor.DARK_GRAY),
                text("Click any icon to see a full chain.", NamedTextColor.DARK_GRAY),
            ),
        )

    internal val searchItem: ItemStack =
        ItemStack.of(Material.COMPASS).withCustomName(text("Search", NamedTextColor.AQUA)).withLore(
            listOf(
                text("Opens a search bar. Type a block name", NamedTextColor.GRAY),
                text("and see what converts into it.", NamedTextColor.GRAY),
            ),
        )

    internal val backItem: ItemStack =
        ItemStack.of(Material.BARRIER).withCustomName(text("Back to all chains", NamedTextColor.RED))

    private fun memberLines(materials: List<Material>): List<Component> {
        val shown =
            materials.take(LORE_MEMBERS).map {
                Component.text("  ", NamedTextColor.DARK_GRAY).append(fancy(it, NamedTextColor.GRAY))
            }
        val more = materials.size - LORE_MEMBERS
        return shown + if (more > 0) listOf(text("  +$more more", NamedTextColor.DARK_GRAY)) else emptyList()
    }

    /** Home page: the master chain first, then one icon per chain. */
    internal fun chains(): List<ItemStack> {
        val all = cycles()
        val master = master()
        val masterEntry =
            ItemStack.of(masterIcon).withCustomName(text("Master Chain", NamedTextColor.LIGHT_PURPLE)).withLore(
                listOf(
                    text("One block from every chain below,", NamedTextColor.GRAY),
                    text("all interchangeable with each other", NamedTextColor.GRAY),
                    Component.empty(),
                ) + memberLines(master) + listOf(Component.empty(), text("Click to see the full chain", NamedTextColor.YELLOW)),
            )
        return listOf(masterEntry) +
            all.map { materials ->
                ItemStack.of(iconOf(materials, all)).withCustomName(text(chainName(materials), NamedTextColor.GOLD)).withLore(
                    listOf(text("${materials.size} blocks, all interchangeable", NamedTextColor.GRAY), Component.empty()) +
                        memberLines(materials) +
                        listOf(Component.empty(), text("Click to see the full chain", NamedTextColor.YELLOW)),
                )
            }
    }

    /** Every block in the chain(s) of [material]. */
    internal fun members(material: Material): List<ItemStack> {
        if (material == masterIcon) {
            return master().map {
                ItemStack.of(it).withCustomName(fancy(it)).withLore(
                    listOf(
                        text("Master Chain", NamedTextColor.LIGHT_PURPLE),
                        text("Put it in the top row to convert", NamedTextColor.DARK_GRAY),
                    ),
                )
            }
        }
        val materials = group(material)
        val name = chainName(cycles().first { material in it })
        return materials.map {
            ItemStack.of(it).withCustomName(fancy(it)).withLore(
                listOf(text(name, NamedTextColor.GOLD), text("Put it in the top row to convert", NamedTextColor.DARK_GRAY)),
            )
        }
    }

    /** Blocks whose name contains every word of [query], best (prefix, then shortest) match first. */
    internal fun matches(query: String): List<Material> {
        val words = query.lowercase().split(' ').filter(String::isNotBlank)
        if (words.isEmpty()) return emptyList()
        return cycles()
            .flatten()
            .distinct()
            .filter { material ->
                val name = Recipes.materialName(material).lowercase()
                words.all(name::contains)
            }.sortedWith(
                compareBy<Material>(
                    { !Recipes.materialName(it).lowercase().startsWith(words.first()) },
                    { Recipes.materialName(it).length },
                ),
            )
    }

    /** A clickable search result showing how many other blocks convert into [material]. */
    internal fun result(material: Material): ItemStack {
        val sources = group(material).size - 1
        return ItemStack.of(material).withCustomName(fancy(material)).withLore(
            listOf(
                text(chainName(cycles().first { material in it }), NamedTextColor.GOLD),
                text("$sources other blocks convert into this", NamedTextColor.GRAY),
                Component.empty(),
                text("Click to see them", NamedTextColor.YELLOW),
            ),
        )
    }

    internal fun search(query: String): List<ItemStack> = matches(query).map(::result)
}
