package net.aechronis.elevators

import net.aechronis.server.modules.AechronisModule
import net.aechronis.server.modules.ModuleContext

class ElevatorsModule : AechronisModule {
    override val id = "elevators"
    override val dependencies = setOf("vanilla")

    override fun initialize(context: ModuleContext) = Elevator.init(takeConfiguration())

    override fun shutdown(context: ModuleContext) = Elevator.shutdown()

    companion object {
        private var configured: ElevatorConfig? = null

        fun configure(config: ElevatorConfig) {
            check(configured == null) { "Elevators was configured by more than one active composition module" }
            configured = config
        }

        private fun takeConfiguration(): ElevatorConfig = (configured ?: ElevatorConfig()).also { configured = null }
    }
}
