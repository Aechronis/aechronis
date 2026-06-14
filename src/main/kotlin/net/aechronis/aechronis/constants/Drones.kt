package net.aechronis.aechronis.constants

import net.aechronis.combat.objects.Drone
import net.aechronis.combat.objects.Hitbox
import net.aechronis.combat.objects.HitboxPart
import net.kyori.adventure.text.Component
import net.minestom.server.coordinate.Vec

object Drones {
    val droneHitbox =
        Hitbox(
            listOf(
                HitboxPart(
                    offset = Vec(0.0, 0.0, -2.0),
                    size = Vec(1.0, 1.0, 8.0),
                    name = "body",
                ),
                HitboxPart(
                    offset = Vec(0.0, -0.5, 0.0),
                    size = Vec(8.0, 0.5, 2.0),
                    name = "wing",
                ),
            ),
        )

    val scoutDrone =
        Drone(
            name = "scout-drone",
            itemName = Component.text("Scout Drone"),
            scale = 1.5,
            hitbox = droneHitbox,
            maxSpeed = 2F,
            turnSpeed = 5F,
            pitchSpeed = 5F,
            maxRange = 500,
            batteryLifeTicks = 2000,
            buzzPeriodTicks = 5,
        )

    val kamikazeDrone =
        Drone(
            name = "kamikazedrone",
            itemName = Component.text("Kamikaze Drone"),
            scale = 1.5,
            hitbox = droneHitbox,
            maxSpeed = 1F,
            turnSpeed = 5F,
            pitchSpeed = 5F,
            maxRange = 300,
            batteryLifeTicks = 1000,
            projectileModel = "aechronis:rpg-rocket",
            projectileMountOffset = Vec(0.0, 0.5, 0.0),
            projectileScale = 0.5,
            explosionRadius = 1,
            explosionFire = 0.0,
            explosionDamage = 20F,
            buzzPeriodTicks = 5,
        )
}
