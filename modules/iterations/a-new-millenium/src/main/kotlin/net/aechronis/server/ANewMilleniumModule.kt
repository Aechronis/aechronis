package net.aechronis.server

import net.aechronis.combat.objects.Item
import net.aechronis.combat.storage.VehiclePersistence
import net.aechronis.nodes.NodesConfig
import net.aechronis.nodes.NodesModule
import net.aechronis.nodes.objects.OreDeposit
import net.aechronis.nodes.objects.TerritoryResources
import net.aechronis.server.constants.Ammo
import net.aechronis.server.constants.Armor
import net.aechronis.server.constants.Boats
import net.aechronis.server.constants.Cars
import net.aechronis.server.constants.Drones
import net.aechronis.server.constants.Factories
import net.aechronis.server.constants.Grenades
import net.aechronis.server.constants.Guns
import net.aechronis.server.constants.Hats
import net.aechronis.server.constants.Melees
import net.aechronis.server.constants.Planes
import net.aechronis.server.constants.Tanks
import net.aechronis.server.craft.Blocks
import net.aechronis.server.craft.Smelting
import net.aechronis.server.craft.Tools
import net.aechronis.server.craft.Vehicles
import net.aechronis.server.craft.Weapons
import net.aechronis.server.listeners.PlayerJoinListener
import net.aechronis.server.modules.AechronisModule
import net.aechronis.server.modules.ModuleContext
import net.aechronis.server.tasks.TabManager
import net.aechronis.vanilla.VanillaConfig
import net.aechronis.vanilla.VanillaModule
import net.aechronis.vanilla.config.BlocksConfig
import net.aechronis.vanilla.config.RecipesConfig
import net.kyori.adventure.resource.ResourcePackInfo
import net.minestom.server.item.Material
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

class ANewMilleniumModule : AechronisModule {
    override val id = "a-new-millenium"
    override val conflicts = setOf("template")
    override val externalResourcePacks =
        listOf(
            ResourcePackInfo
                .resourcePackInfo()
                .uri(URI("https://cdn.modrinth.com/data/LSmohupN/versions/zewiXtmr/Ashen_16x.zip"))
                .hash("d312836c38143301b7ba6a1247372b3f467116db")
                .build(),
        )
    override val dependencies =
        setOf(
            "utils",
            "watchdog",
            "combat",
            "vanilla",
            "worldedit",
            "nodes",
            "logger",
            "guard",
            "gems",
        )

    override fun configure(context: ModuleContext) {
        registerItems()
        VanillaModule.configure(
            VanillaConfig(
                recipesConfig = RecipesConfig(recpies = Blocks.list + Tools.list + Smelting.list + Weapons.list + Vehicles.list),
                blocksConfig = BlocksConfig(converterCycles = Blocks.converterCycles),
                factories = Factories.list,
                shopEnabled = false,
            ),
        )
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

    override fun initialize(context: ModuleContext) {
        TabManager.start()

        initializeVehiclePersistence(context)
        PlayerJoinListener.init(context)
        CraftingStoreIntegration.initialize()
        VotifierIntegration.initialize()
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
            Hats.gasMask,
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
            Boats.ussButler,
        )
    }

    override fun saveState(context: ModuleContext) {
        VehiclePersistence.saveForShutdown()
    }

    override fun prepareForShutdown(context: ModuleContext) {
        shutdownExternalServices()
    }

    override fun shutdown(context: ModuleContext) {
        var failure: Throwable? = null
        listOf(
            ::shutdownExternalServices,
            TabManager::shutdown,
            VehiclePersistence::shutdown,
            Item.registeredItems::clear,
        ).forEach { cleanup ->
            runCatching(cleanup).onFailure { error ->
                failure?.addSuppressed(error) ?: run { failure = error }
            }
        }
        failure?.let { throw it }
    }

    private fun shutdownExternalServices() {
        var failure: Throwable? = null
        listOf(
            VotifierIntegration::shutdown,
            CraftingStoreIntegration::shutdown,
        ).forEach { cleanup ->
            runCatching(cleanup).onFailure { error ->
                failure?.addSuppressed(error) ?: run { failure = error }
            }
        }
        failure?.let { throw it }
    }

    private fun initializeVehiclePersistence(context: ModuleContext) {
        val vehiclePath = Path.of("combat", "vehicles.json")
        val legacyVehiclePath = Path.of("world", "vehicles.json")
        if (!Files.exists(vehiclePath) && Files.exists(legacyVehiclePath)) {
            try {
                Files.createDirectories(vehiclePath.parent)
                Files.move(legacyVehiclePath, vehiclePath)
                println("[Combat] Moved vehicle save from $legacyVehiclePath to $vehiclePath")
            } catch (exception: Exception) {
                System.err.println("[Combat] Failed to move vehicle save to $vehiclePath: ${exception.message}")
            }
        }
        VehiclePersistence.initialize(vehiclePath, context.instance)
    }
}
