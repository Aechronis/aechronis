package net.aechronis.combat.listeners

import net.aechronis.combat.Combat
import net.aechronis.combat.objects.Drone
import net.aechronis.combat.objects.Vehicle
import net.minestom.server.entity.EntityType
import net.minestom.server.entity.Player
import net.minestom.server.event.entity.EntityDamageEvent

object MannequinDamageListener {
    private val forwarding = HashSet<Player>()

    fun onEntityDamage(event: EntityDamageEvent) {
        val entity = event.entity

        // a drone operator clone takes hits on the pilot's behalf: cancel the
        // damage on the clone and apply it to the pilot instead.
        val pilot = entity.let { Drone.mannequinPilot[it] }
        if (pilot != null) {
            event.isCancelled = true
            (event.damage.attacker as? Player)?.let { Combat.recordKiller(pilot, it) }

            if (pilot.health - event.damage.amount <= 0f) {
                (Vehicle.playerVehicle[pilot] as? Drone)?.onExit(pilot)
            }

            forwarding.add(pilot)
            try {
                pilot.damage(event.damage)
            } finally {
                forwarding.remove(pilot)
            }
            return
        }

        // other mannequins (hat preview, corpses) ignore damage entirely
        if (entity.entityType == EntityType.MANNEQUIN) {
            event.isCancelled = true
            return
        }

        // the real body of a flying pilot can't be damaged directly -- only
        // through the clone (forwarded above)
        if (entity is Player && entity !in forwarding && Vehicle.playerVehicle[entity] is Drone) {
            event.isCancelled = true
        }
    }

    fun init() {
        Combat.highPriorityEventNode.addListener(EntityDamageEvent::class.java, MannequinDamageListener::onEntityDamage)
    }
}
