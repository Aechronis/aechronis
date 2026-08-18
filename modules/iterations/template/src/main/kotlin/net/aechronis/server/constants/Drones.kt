package net.aechronis.server.constants

import net.aechronis.combat.objects.Drone
import net.aechronis.combat.objects.Hitbox
import net.aechronis.combat.objects.HitboxPart
import net.kyori.adventure.key.Key
import net.kyori.adventure.sound.Sound
import net.kyori.adventure.text.Component
import net.minestom.server.coordinate.Vec

object Drones {
    private val droneHitbox =
        Hitbox(
            listOf(
                HitboxPart(
                    offset = Vec(0.0, 0.0, -2.0),
                    size = Vec(1.0, 1.0, 8.0),
                ),
                HitboxPart(
                    offset = Vec(0.0, -0.5, 0.0),
                    size = Vec(8.0, 0.5, 2.0),
                ),
            ),
        )

    val scoutDrone =
        Drone(
            name = "scout-drone",
            itemName = Component.text("Scout Drone"),
            itemModel = "aechronis:drone",
            model = "aechronis:drone",
            scale = 1.5,
            hitbox = droneHitbox,
            maxSpeed = 3F,
            turnSpeed = 5F,
            pitchSpeed = 5F,
            maxRange = 3000,
            batteryLifeTicks = 10000,
            buzzSound = Sound.sound(Key.key("aechronis:drone.buzz"), Sound.Source.PLAYER, 1f, 1f),
            buzzPeriodTicks = 5,
        )

    val kamikazeDrone =
        Drone(
            name = "kamikaze-drone",
            itemName = Component.text("Kamikaze Drone"),
            itemModel = "aechronis:drone",
            model = "aechronis:drone",
            scale = 1.5,
            hitbox = droneHitbox,
            maxSpeed = 2F,
            turnSpeed = 5F,
            pitchSpeed = 5F,
            maxRange = 1500,
            batteryLifeTicks = 3000,
            projectileModel = "aechronis:rpg-rocket",
            projectileMountOffset = Vec(0.0, 0.5, 0.0),
            projectileScale = 0.5,
            explosionRadius = 3,
            explosionFire = 0.3,
            explosionDamage = 750F,
            buzzSound = Sound.sound(Key.key("aechronis:drone.buzz"), Sound.Source.PLAYER, 1f, 1f),
            buzzPeriodTicks = 5,
        )
}
