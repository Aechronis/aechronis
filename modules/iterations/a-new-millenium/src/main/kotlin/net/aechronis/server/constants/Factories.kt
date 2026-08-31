package net.aechronis.server.constants

import net.aechronis.combat.objects.Item
import net.aechronis.vanilla.objects.Factory
import net.aechronis.vanilla.objects.FactoryRecipe
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material

object Factories {
    // Vehicle fuel costs are the wiki's oil costs (guides/src/buildings.md) x16: the wiki's
    // "starter"/"mid"/"top" oil price points (16/32/48, 8 at tier 3) become fixed fuel price
    // points (256/512/768, 128 at tier 3) that never change - only which vehicle sits at a
    // given price point changes as tiers unlock new (or cheaper) options.
    private const val STARTER_FUEL_COST = 256
    private const val STARTER_FUEL_COST_DISCOUNTED = 128
    private const val MID_FUEL_COST = 512
    private const val TOP_FUEL_COST = 768

    private fun recipe(
        vehicle: Item,
        fuelCost: Int,
    ) = FactoryRecipe(
        name = vehicle.name,
        displayName = vehicle.itemName,
        input = listOf(Resources.fuel.withAmount(fuelCost)),
        output = listOf(vehicle.toItemStack()),
    )

    // Rates taken from the website (guides/src/buildings.md); expected to change once the
    // active-factory rework lands.
    val oilRefinery =
        Factory(
            name = "Oil Refinery",
            itemName = Component.text("Oil Refinery", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
            maxTier = 3,
            recipes =
                mapOf(
                    1 to
                        listOf(
                            FactoryRecipe(
                                name = "fuel",
                                displayName = Component.text("Fuel"),
                                input = listOf(ItemStack.of(Material.DRAGON_BREATH, 64)),
                                output = listOf(Resources.fuel.withAmount(32)),
                            ),
                        ),
                    2 to
                        listOf(
                            FactoryRecipe(
                                name = "fuel",
                                displayName = Component.text("Fuel"),
                                input = listOf(ItemStack.of(Material.DRAGON_BREATH, 128)),
                                output = listOf(Resources.fuel.withAmount(96)),
                            ),
                        ),
                    3 to
                        listOf(
                            FactoryRecipe(
                                name = "fuel",
                                displayName = Component.text("Fuel"),
                                input = listOf(ItemStack.of(Material.DRAGON_BREATH, 320)),
                                output = listOf(Resources.fuel.withAmount(288)),
                            ),
                        ),
                ),
        )

    // Vehicles cascade: everything unlocked at a lower tier stays available (cheaper, for the
    // starter vehicle) at every higher tier of the same factory.
    val landFactory =
        Factory(
            name = "Land Factory",
            itemName = Component.text("Land Factory", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
            maxTier = 3,
            recipes =
                mapOf(
                    1 to listOf(recipe(Cars.humvee, STARTER_FUEL_COST)),
                    2 to
                        listOf(
                            recipe(Cars.humvee, STARTER_FUEL_COST),
                            recipe(Tanks.t90, MID_FUEL_COST),
                        ),
                    3 to
                        listOf(
                            recipe(Cars.humvee, STARTER_FUEL_COST_DISCOUNTED),
                            recipe(Tanks.t90, MID_FUEL_COST),
                            recipe(Tanks.m1a1Abrams, TOP_FUEL_COST),
                        ),
                ),
        )

    val airFactory =
        Factory(
            name = "Air Factory",
            itemName = Component.text("Air Factory", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
            maxTier = 3,
            recipes =
                mapOf(
                    1 to listOf(recipe(Planes.f16, STARTER_FUEL_COST)),
                    2 to
                        listOf(
                            recipe(Planes.f16, STARTER_FUEL_COST),
                            recipe(Planes.j20, MID_FUEL_COST),
                            recipe(Planes.su34, TOP_FUEL_COST),
                        ),
                    3 to
                        listOf(
                            recipe(Planes.f16, STARTER_FUEL_COST_DISCOUNTED),
                            recipe(Planes.j20, MID_FUEL_COST),
                            recipe(Planes.su34, TOP_FUEL_COST),
                            recipe(Planes.su57, MID_FUEL_COST),
                            recipe(Planes.b2, TOP_FUEL_COST),
                        ),
                ),
        )

    val list: List<Factory> = listOf(oilRefinery, landFactory, airFactory)
}
