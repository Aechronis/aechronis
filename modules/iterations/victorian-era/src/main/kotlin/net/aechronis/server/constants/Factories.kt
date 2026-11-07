package net.aechronis.server.constants

import net.aechronis.combat.objects.Item
import net.aechronis.nodes.objects.ActiveBuildingDefinition
import net.aechronis.nodes.objects.ActiveBuildingRecipe
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material

/**
 * Active factories turn coal into vehicles and field pieces; finished items drop on the ground
 * at the factory, so enemies can raid them. Costs and tier unlocks follow the Factories sheet.
 */
object Factories {
    // Coal per recipe by factory tier, listed weakest to strongest item. Every factory uses the
    // land factory's values (Gatling Gun, Maxim Gun, Krupp C64 Field Gun).
    private val COAL_COST =
        mapOf(
            1 to listOf(200),
            2 to listOf(150, 400),
            3 to listOf(100, 300, 800),
        )

    private fun recipe(
        item: Item,
        coal: Int,
    ) = ActiveBuildingRecipe(
        name = item.name,
        displayName = item.itemName,
        input = listOf(ItemStack.of(Material.COAL, coal)),
        output = listOf(item.toItemStack()),
    )

    // Items run weakest to strongest; tier N offers the first N, so lower-tier items stay available.
    private fun factory(
        name: String,
        displayName: String,
        vararg items: Item,
    ) = ActiveBuildingDefinition(
        name = name,
        displayName = Component.text(displayName, NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
        recipes = COAL_COST.mapValues { (tier, costs) -> items.take(tier).mapIndexed { index, item -> recipe(item, costs[index]) } },
    )

    val landFactory = factory("land-factory", "Land Factory", FieldPieces.gatlingGun, FieldPieces.maximGun, FieldPieces.kruppC64)

    val seaFactory = factory("sea-factory", "Dockyard", Boats.dolly, Boats.pillnitz, Boats.hmsDevastation)

    val list: List<ActiveBuildingDefinition> = listOf(landFactory, seaFactory)
}
