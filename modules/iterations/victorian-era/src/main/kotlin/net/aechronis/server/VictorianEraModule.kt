package net.aechronis.server

import net.aechronis.combat.objects.Item
import net.aechronis.combat.utils.GunHandSkins
import net.aechronis.nodes.NodesConfig
import net.aechronis.nodes.NodesModule
import net.aechronis.nodes.objects.OreDeposit
import net.aechronis.nodes.objects.TerritoryResources
import net.aechronis.server.constants.Ammo
import net.aechronis.server.constants.Armor
import net.aechronis.server.constants.FieldPieces
import net.aechronis.server.constants.Guns
import net.aechronis.server.constants.Melees
import net.aechronis.server.modules.AechronisModule
import net.aechronis.server.modules.ModuleContext
import net.aechronis.server.modules.ModuleStartupTimings.measure
import net.aechronis.server.resourcepack.EmbeddedResourcePack
import net.aechronis.server.tasks.TabManager
import net.aechronis.vanilla.VanillaConfig
import net.aechronis.vanilla.VanillaModule
import net.kyori.adventure.resource.ResourcePackInfo
import net.minestom.server.item.Material
import java.net.URI

class VictorianEraModule : AechronisModule {
    override val id = "victorian-era"
    override val externalResourcePacks =
        listOf(
            ResourcePackInfo
                .resourcePackInfo()
                .uri(URI("https://cdn.modrinth.com/data/LSmohupN/versions/zewiXtmr/Ashen_16x.zip"))
                .hash("d312836c38143301b7ba6a1247372b3f467116db")
                .build(),
        )
    override val dependencies = setOf("combat", "vanilla", "recipes", "nodes")
    override val reloadTogether = setOf("combat", "vanilla", "nodes")

    override fun configure(context: ModuleContext) {
        measure("Item catalogue") { registerItems() }
        val hands by lazy {
            checkNotNull(EmbeddedResourcePack.readAsset(javaClass, GunHandSkins.HAND_TEXTURE)) { "Missing iteration hand atlas" }
        }
        GunHandSkins.registerTemplate { hands }
        measure("Vanilla configuration") {
            VanillaModule.configure(
                VanillaConfig(
                    shopEnabled = false,
                ),
            )
        }
        measure("Nodes configuration") {
            NodesModule.configure(
                NodesConfig(
                    defaultRespawnPoint = context.spawnPoint,
                    chunkAttackTime = 120_000,
                    chunkAttackFromWastelandMultiplier = 1.25,
                    chunkAttackHomeMultiplier = 1.25,
                    globalResources =
                        TerritoryResources(
                            ores =
                                mutableMapOf(
                                    Material.IRON_ORE to OreDeposit(Material.IRON_ORE, 0.0405, 1, 1),
                                    Material.GOLD_ORE to OreDeposit(Material.GOLD_ORE, 0.0225, 1, 1),
                                    Material.DIAMOND_ORE to OreDeposit(Material.DIAMOND_ORE, 0.015, 1, 1),
                                    Material.COPPER_ORE to OreDeposit(Material.COPPER_ORE, 0.0045, 1, 1),
                                    Material.COAL to OreDeposit(Material.COAL, 0.055, 1, 1),
                                    Material.REDSTONE to OreDeposit(Material.REDSTONE, 0.0125, 1, 1),
                                    Material.BLAZE_POWDER to OreDeposit(Material.BLAZE_POWDER, 0.0125, 1, 1),
                                ),
                        ),
                ),
            )
        }
    }

    override fun initialize(context: ModuleContext) {
        measure("Tab list") { TabManager.start() }
    }

    private fun registerItems() {
        Item.registerItems(
            *Ammo.all.toTypedArray(),
            *Guns.all.toTypedArray(),
            *Melees.all.toTypedArray(),
            *Armor.all.toTypedArray(),
            FieldPieces.kruppC64,
            FieldPieces.gatlingGun,
        )
    }

    override fun shutdown(context: ModuleContext) {
        TabManager.shutdown()
    }
}
