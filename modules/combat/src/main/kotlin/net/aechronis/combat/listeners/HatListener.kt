package net.aechronis.combat.listeners

import net.aechronis.combat.Combat
import net.aechronis.combat.storage.HatCollection
import net.minestom.server.event.player.PlayerSpawnEvent

object HatListener {
    private fun onPlayerSpawn(event: PlayerSpawnEvent) {
        if (!event.isFirstSpawn) return
        HatCollection.load(event.player.uuid)
    }

    fun init() {
        Combat.eventNode.addListener(PlayerSpawnEvent::class.java, HatListener::onPlayerSpawn)
    }
}
