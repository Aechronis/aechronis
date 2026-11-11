package net.aechronis.treefeller

import net.aechronis.server.modules.AechronisModule
import net.aechronis.server.modules.ModuleContext
import net.aechronis.settings.PlayerSetting
import net.aechronis.settings.PlayerSettings
import net.minestom.server.item.Material

class TreeFellerModule : AechronisModule {
    override val id = "treefeller"
    override val dependencies = setOf("vanilla", "settings")

    private val setting =
        PlayerSetting.toggle(
            "Tree felling",
            Material.IRON_AXE,
            { !TreeFellerOptOut.disabled(it.uuid) },
            { player, enabled -> TreeFellerOptOut.set(player.uuid, enabled) },
        )

    override fun initialize(context: ModuleContext) {
        val config = takeConfiguration()
        TreeFeller.init(config)
        if (config.enabled) {
            TreeFellerOptOut.initialize()
            PlayerSettings.register(setting)
        }
    }

    override fun shutdown(context: ModuleContext) {
        PlayerSettings.unregister(setting)
        TreeFeller.shutdown()
    }

    companion object {
        private var configured: TreeFellerConfig? = null

        fun configure(config: TreeFellerConfig) {
            check(configured == null) { "Tree felling was configured by more than one active composition module" }
            configured = config
        }

        private fun takeConfiguration(): TreeFellerConfig = (configured ?: TreeFellerConfig()).also { configured = null }
    }
}
