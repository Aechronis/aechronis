package net.aechronis.server

import net.aechronis.combat.objects.ArmorPiece
import net.aechronis.combat.objects.Gun
import net.aechronis.combat.objects.Item
import net.aechronis.combat.objects.Melee
import net.aechronis.combat.utils.GunHandSkins
import net.aechronis.duels.Kit
import net.aechronis.duels.KitContents
import net.aechronis.duels.Kits
import net.aechronis.duels.MapDef
import net.aechronis.duels.Maps
import net.aechronis.duels.SpawnPos
import net.aechronis.killshop.KillShopModule
import net.aechronis.killshop.ShopConfig
import net.aechronis.nodes.NodesConfig
import net.aechronis.nodes.NodesModule
import net.aechronis.nodes.objects.OreDeposit
import net.aechronis.nodes.objects.TerritoryResources
import net.aechronis.server.constants.Ammo
import net.aechronis.server.constants.Armor
import net.aechronis.server.constants.Boats
import net.aechronis.server.constants.FieldPieces
import net.aechronis.server.constants.Grenades
import net.aechronis.server.constants.Guns
import net.aechronis.server.constants.Melees
import net.aechronis.server.craft.HorseRecipes
import net.aechronis.server.modules.AechronisModule
import net.aechronis.server.modules.ModuleContext
import net.aechronis.server.modules.ModuleStartupTimings.measure
import net.aechronis.server.resourcepack.EmbeddedResourcePack
import net.aechronis.server.tasks.TabManager
import net.aechronis.vanilla.VanillaConfig
import net.aechronis.vanilla.VanillaModule
import net.aechronis.vanilla.config.RecipesConfig
import net.kyori.adventure.resource.ResourcePackInfo
import net.minestom.server.item.ItemStack
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
    override val dependencies = setOf("combat", "vanilla", "recipes", "nodes", "killshop", "duels")

    override fun configure(context: ModuleContext) {
        measure("Item catalogue") { registerItems() }
        measure("Duel kits") { registerDuelsKits() }
        measure("Duel maps") { registerDuelsMaps() }
        val hands by lazy {
            checkNotNull(EmbeddedResourcePack.readAsset(javaClass, GunHandSkins.HAND_TEXTURE)) { "Missing iteration hand atlas" }
        }
        GunHandSkins.registerTemplate { hands }
        KillShopModule.configure(ShopConfig(enabled = false))
        measure("Vanilla configuration") {
            VanillaModule.configure(
                VanillaConfig(
                    horsesEnabled = true,
                    recipesConfig = RecipesConfig(recpies = HorseRecipes.list),
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

    override fun resourcePackChanged(context: ModuleContext) {
        GunHandSkins.replaceTemplate(
            checkNotNull(context.readResourcePackAsset(id, GunHandSkins.HAND_TEXTURE)) { "Missing iteration hand atlas" },
        )
    }

    private fun registerItems() {
        Item.registerItems(
            *Ammo.all.toTypedArray(),
            *Guns.all.toTypedArray(),
            *Grenades.all.toTypedArray(),
            *Melees.all.toTypedArray(),
            *Armor.all.toTypedArray(),
            *Boats.all.toTypedArray(),
            FieldPieces.kruppC64,
            FieldPieces.gatlingGun,
            FieldPieces.maximGun,
        )
    }

    private fun registerDuelsMaps() {
        Maps.register(
            MapDef(
                "victorian-field",
                "Open Field",
                listOf(SpawnPos(-15.5, 64.0, 0.5, -90f), SpawnPos(15.5, 64.0, 0.5, 90f)),
                icon = "grass_block",
            ),
        )
    }

    private fun registerDuelsKits() {
        Kits.register(
            kit(
                "rifleman",
                "Rifleman",
                Guns.dreyseNeedleGun,
                listOf(Guns.dreyseNeedleGun to 64),
                Melees.cavalrySabre,
                listOf(Armor.prussiaJacket, Armor.prussiaTrousers, Armor.prussiaBoots),
            ),
            kit(
                "marksman",
                "Marksman",
                Guns.berdanII,
                listOf(Guns.berdanII to 64, Guns.adamsMkIII to 24),
                null,
                listOf(Armor.franceJacket, Armor.franceTrousers, Armor.franceBoots),
                sidearm = Guns.adamsMkIII,
            ),
            kit(
                "gunslinger",
                "Gunslinger",
                Guns.coltSingleActionArmy,
                listOf(Guns.coltSingleActionArmy to 48, Guns.coachGun to 24),
                null,
                listOf(Armor.britainJacket, Armor.britainTrousers, Armor.britainBoots),
                sidearm = Guns.coachGun,
            ),
            kit(
                "skirmisher",
                "Skirmisher",
                Guns.chassepotCarbine,
                listOf(Guns.chassepotCarbine to 48),
                Melees.navalCutlass,
                listOf(Armor.austriaJacket, Armor.austriaTrousers, Armor.austriaBoots),
            ),
        )
    }

    /** Slot 0 melee (if any), then the guns, then the ammo for each gun; armor is jacket/trousers/boots. */
    private fun kit(
        id: String,
        name: String,
        icon: Gun,
        ammo: List<Pair<Gun, Int>>,
        melee: Melee?,
        armor: List<ArmorPiece>,
        sidearm: Gun? = null,
    ): Kit =
        Kit(id, name, { icon.toItemStack() }) {
            val items = LinkedHashMap<Int, ItemStack>()
            var slot = 0
            melee?.let { items[slot++] = it.toItemStack() }
            items[slot++] = icon.toItemStack()
            sidearm?.let { items[slot++] = it.toItemStack() }
            ammo.map { it.first.ammo to it.second }.distinctBy { it.first.name }.forEach { (a, n) ->
                items[slot++] = a.toItemStack().withAmount(n.coerceAtMost(64))
            }
            items[8] = ItemStack.of(Material.COOKED_BEEF, 16)
            KitContents(
                items,
                chestplate = armor[0].toItemStack(),
                leggings = armor[1].toItemStack(),
                boots = armor[2].toItemStack(),
            )
        }

    override fun shutdown(context: ModuleContext) {
        TabManager.shutdown()
    }
}
