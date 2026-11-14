package net.aechronis.server.constants

import net.aechronis.crates.CrateDefaults
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.minestom.server.component.DataComponents
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material

/** Crates this iteration ships with. Weights are percentages; admins can edit the tables in game afterward. */
object Crates {
    /** One key per vote. */
    private val vote =
        CrateDefaults(
            id = "vote",
            title = "Vote Crate",
            rewards =
                listOf(
                    ItemStack.of(Material.HAY_BLOCK, 8) to 20.0,
                    ItemStack.of(Material.COOKED_BEEF, 8) to 20.0,
                    ItemStack.of(Material.BREAD, 32) to 20.0,
                    ItemStack.of(Material.IRON_INGOT, 8) to 20.0,
                    ItemStack.of(Material.GOLD_INGOT, 8) to 10.0,
                    ItemStack.of(Material.DIAMOND, 8) to 9.0,
                    ItemStack
                        .of(Material.DIRT)
                        .with(
                            DataComponents.CUSTOM_NAME,
                            Component.text("Mythical Dirt", NamedTextColor.LIGHT_PURPLE).decoration(TextDecoration.ITALIC, false),
                        ) to 1.0,
                ),
        )

    /** Linked per KOTH with /koth crate set <koth> koth <keys>, so regional and global KOTHs can give different amounts. */
    private val koth =
        CrateDefaults(
            id = "koth",
            title = "KOTH Crate",
            rewards =
                listOf(
                    ItemStack.of(Material.IRON_BLOCK, 16) to 20.0,
                    ItemStack.of(Material.GOLD_BLOCK, 16) to 20.0,
                    ItemStack.of(Material.DIAMOND_BLOCK, 16) to 20.0,
                    ItemStack.of(Material.COAL_BLOCK, 16) to 14.0,
                    ItemStack.of(Material.GUNPOWDER, 64) to 20.0,
                    FieldPieces.maximGun.toItemStack() to 3.0,
                    Boats.hmsDevastation.toItemStack() to 1.0,
                    Airships.lz1.toItemStack() to 1.0,
                    FieldPieces.kruppC64.toItemStack() to 1.0,
                ),
        )

    /** Rank perk: tier 1 vehicles 18% each, tier 2 10% each, tier 3 split the remaining 16%. */
    private val vehicle =
        CrateDefaults(
            id = "vehicle",
            title = "Vehicle Crate",
            rewards =
                listOf(
                    Boats.dolly.toItemStack() to 18.0,
                    FieldPieces.gatlingGun.toItemStack() to 18.0,
                    Balloons.ballonMonte.toItemStack() to 18.0,
                    Boats.pillnitz.toItemStack() to 10.0,
                    FieldPieces.maximGun.toItemStack() to 10.0,
                    Airships.dupuyDeLome.toItemStack() to 10.0,
                    Boats.hmsDevastation.toItemStack() to 16.0 / 3,
                    FieldPieces.kruppC64.toItemStack() to 16.0 / 3,
                    Airships.lz1.toItemStack() to 16.0 / 3,
                ),
        )

    val all = listOf(vote, koth, vehicle)
}
