package net.aechronis.server

import net.aechronis.combat.objects.ArmorPiece
import net.aechronis.combat.objects.Gun
import net.aechronis.combat.objects.Item
import net.aechronis.combat.utils.GunHandSkins
import net.aechronis.duels.Kit
import net.aechronis.duels.KitContents
import net.aechronis.duels.MapDef
import net.aechronis.duels.SpawnPos
import net.aechronis.killshop.KillShopModule
import net.aechronis.killshop.ShopConfig
import net.aechronis.nodes.NodesConfig
import net.aechronis.nodes.NodesModule
import net.aechronis.nodes.objects.OreDeposit
import net.aechronis.nodes.objects.TerritoryResources
import net.aechronis.server.constants.Ammo
import net.aechronis.server.constants.Armor
import net.aechronis.server.constants.Cars
import net.aechronis.server.constants.Drones
import net.aechronis.server.constants.Factories
import net.aechronis.server.constants.Grenades
import net.aechronis.server.constants.Guns
import net.aechronis.server.constants.Melees
import net.aechronis.server.constants.Planes
import net.aechronis.server.constants.Tanks
import net.aechronis.server.craft.Vehicles
import net.aechronis.server.craft.Weapons
import net.aechronis.server.listeners.DroneListener
import net.aechronis.server.modules.AechronisModule
import net.aechronis.server.modules.ModuleContext
import net.aechronis.server.modules.ModuleStartupTimings.measure
import net.aechronis.server.objects.Drone
import net.aechronis.server.resourcepack.EmbeddedResourcePack
import net.aechronis.server.tasks.TabManager
import net.aechronis.vanilla.VanillaConfig
import net.aechronis.vanilla.VanillaModule
import net.aechronis.vanilla.config.RecipesConfig
import net.kyori.adventure.resource.ResourcePackInfo
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import java.net.URI
import net.aechronis.duels.Kits as DuelKits
import net.aechronis.duels.Maps as DuelMaps

class ANewMilleniumModule : AechronisModule {
    override val id = "a-new-millenium"
    override val externalResourcePacks =
        listOf(
            ResourcePackInfo
                .resourcePackInfo()
                .uri(URI("https://cdn.modrinth.com/data/LSmohupN/versions/zewiXtmr/Ashen_16x.zip"))
                .hash("d312836c38143301b7ba6a1247372b3f467116db")
                .build(),
        )
    override val dependencies = setOf("combat", "vanilla", "recipes", "nodes", "bundles", "killshop", "duels")

    override fun configure(context: ModuleContext) {
        measure("Item catalogue") { registerItems() }
        measure("Duel kits") { registerDuelKits() }
        measure("Duel maps") { registerDuelMaps() }
        val hands by lazy {
            checkNotNull(EmbeddedResourcePack.readAsset(javaClass, GunHandSkins.HAND_TEXTURE)) { "Missing iteration hand atlas" }
        }
        GunHandSkins.registerTemplate { hands }
        KillShopModule.configure(ShopConfig(enabled = false))
        measure("Vanilla configuration") {
            VanillaModule.configure(
                VanillaConfig(
                    recipesConfig = RecipesConfig(recpies = Weapons.list + Vehicles.list),
                ),
            )
        }
        measure("Nodes configuration") {
            NodesModule.configure(
                NodesConfig(
                    activeBuildings = Factories.list,
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

        measure("Drone listeners") { DroneListener.init(context) }
    }

    override fun resourcePackChanged(context: ModuleContext) {
        GunHandSkins.replaceTemplate(
            checkNotNull(context.readResourcePackAsset(id, GunHandSkins.HAND_TEXTURE)) { "Missing iteration hand atlas" },
        )
    }

    private fun registerItems() {
        Item.registerItems(
            Ammo.ammo762x39mm,
            Ammo.ammo762x39mmExplosive,
            Ammo.ammo9mm,
            Ammo.tankShell,
            Ammo.rocket,
            Guns.ak12,
            Guns.ak74,
            Guns.awp,
            Guns.g3,
            Guns.glock17,
            Guns.m4a1,
            Guns.m9,
            Guns.mg3,
            Guns.mp5,
            Guns.at4,
            Guns.qbz95,
            Guns.vz61,
            Grenades.rgo,
            Melees.baton,
            *Armor.all.toTypedArray(),
            Planes.b2,
            Planes.f16,
            Planes.j20,
            Planes.su34,
            Planes.su57,
            Cars.humvee,
            Tanks.m1a1Abrams,
            Tanks.t90,
            Drones.scoutDrone,
            Drones.kamikazeDrone,
        )
    }

    private fun registerDuelMaps() {
        DuelMaps.register(
            MapDef(
                "flatlands",
                "Flatlands",
                listOf(SpawnPos(-15.5, 64.0, 0.5, -90f), SpawnPos(15.5, 64.0, 0.5, 90f)),
                icon = "grass_block",
            ),
            MapDef("pit", "The Pit", listOf(SpawnPos(-7.5, 64.0, 0.5, -90f), SpawnPos(7.5, 64.0, 0.5, 90f)), icon = "stone"),
        )
    }

    private fun registerDuelKits() {
        val usMarine = listOf(Armor.usMarineJacket, Armor.usMarineTrousers, Armor.usMarineBoots)
        val idf = listOf(Armor.idfJacket, Armor.idfTrousers, Armor.idfBoots)
        DuelKits.register(
            duelKit("m4a1", "M4A1 Rifleman", Guns.m4a1, Guns.glock17, usMarine),
            duelKit("ak12", "AK-12 Rifleman", Guns.ak12, Guns.vz61, idf),
            duelKit("awp", "AWP Sniper", Guns.awp, Guns.glock17, usMarine),
            duelKit("mp5", "MP5 Breacher", Guns.mp5, Guns.vz61, idf),
        )
    }

    /** Slot 0 primary, 1 secondary, 2 baton, then ammo for each; armor is jacket/trousers/boots. */
    private fun duelKit(
        id: String,
        name: String,
        primary: Gun,
        secondary: Gun,
        armor: List<ArmorPiece>,
    ): Kit =
        Kit(id, name, { primary.toItemStack() }) {
            KitContents(
                mapOf(
                    0 to primary.toItemStack(),
                    1 to secondary.toItemStack(),
                    2 to Melees.baton.toItemStack(),
                    3 to primary.ammo.toItemStack().withAmount(64),
                    4 to secondary.ammo.toItemStack().withAmount(32),
                    8 to ItemStack.of(Material.COOKED_BEEF, 16),
                ),
                chestplate = armor[0].toItemStack(),
                leggings = armor[1].toItemStack(),
                boots = armor[2].toItemStack(),
            )
        }

    override fun shutdown(context: ModuleContext) {
        var failure: Throwable? = null
        listOf(
            TabManager::shutdown,
            Drones.scoutDrone::shutdownRuntimeState,
            Drones.kamikazeDrone::shutdownRuntimeState,
            Drone::shutdownCrashStatics,
        ).forEach { cleanup ->
            runCatching(cleanup).onFailure { error ->
                failure?.addSuppressed(error) ?: run { failure = error }
            }
        }
        failure?.let { throw it }
    }
}
