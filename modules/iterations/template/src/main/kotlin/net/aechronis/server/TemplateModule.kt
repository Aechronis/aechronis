package net.aechronis.server

import net.aechronis.combat.objects.Item
import net.aechronis.combat.storage.VehiclePersistence
import net.aechronis.server.constants.Ammo
import net.aechronis.server.constants.Armor
import net.aechronis.server.constants.Boats
import net.aechronis.server.constants.Cars
import net.aechronis.server.constants.Drones
import net.aechronis.server.constants.Grenades
import net.aechronis.server.constants.Guns
import net.aechronis.server.constants.Hats
import net.aechronis.server.constants.Planes
import net.aechronis.server.constants.Tanks
import net.aechronis.server.listeners.PlayerJoinListener
import net.aechronis.server.modules.AechronisModule
import net.aechronis.server.modules.ModuleContext
import net.aechronis.server.tasks.TabManager
import net.kyori.adventure.resource.ResourcePackInfo
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

class TemplateModule : AechronisModule {
    override val id = "template"
    override val externalResourcePacks =
        listOf(
            ResourcePackInfo
                .resourcePackInfo()
                .uri(URI("https://cdn.modrinth.com/data/LSmohupN/versions/zewiXtmr/Ashen_16x.zip"))
                .hash("d312836c38143301b7ba6a1247372b3f467116db")
                .build(),
        )
    override val dependencies = setOf("combat", "vanilla", "nodes")
    override val reloadTogether = setOf("combat")

    override fun configure(context: ModuleContext) {
        registerItems()
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
            Ammo.tankShell,
            Guns.ak47,
            Grenades.frag,
            Armor.jacket,
            Armor.trousers,
            Armor.boots,
            Hats.gasMask,
            Planes.fighter,
            Planes.bomber,
            Cars.truck,
            Tanks.m1a1Abrams,
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
