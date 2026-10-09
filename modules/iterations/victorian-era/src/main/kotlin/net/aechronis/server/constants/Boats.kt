package net.aechronis.server.constants

import net.aechronis.combat.objects.AmmoTypes
import net.aechronis.combat.objects.AnimatedPart
import net.aechronis.combat.objects.Boat
import net.aechronis.combat.objects.BoatArmament
import net.aechronis.combat.objects.BoatWeapon
import net.aechronis.combat.objects.CollisionBuilder
import net.aechronis.combat.objects.Health
import net.aechronis.combat.objects.Hitbox
import net.aechronis.combat.objects.HitboxPart
import net.aechronis.combat.objects.ShulkerHitbox
import net.aechronis.combat.objects.VehicleSeat
import net.aechronis.combat.objects.VehicleSeatRole
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.minestom.server.coordinate.Vec
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

object Boats {
    // Weapon selection boxes enclose the scaled, rotated component geometry with 0.1 m padding.
    // One world block is one metre. Bounds include every rotated model element,
    // including the stem and rudder; per-axis scaling preserves the historical beam.
    // Dolly: 12.50 m overall, 1.98 m beam. Its recorded depth is not its draught.
    // https://www.nationalhistoricships.org.uk/register/19/dolly
    // Raw beam/length: 7.537130392999 / 45.822939290557. Scale = metres * 16 / raw extent; Y follows Z.
    private val dollyScale = Vec(4.203191181279, 4.364626169697, 4.364626169697)

    // The 1884 Pillnitz II (now Diesbar), not the later ship renamed Pillnitz:
    // 52.72 m overall and 10.25 m across paddle boxes (5.07 m hull beam).
    // https://urn.dsm.museum/DSA/DSA03_1980_069114_Rindt.pdf (printed page 91)
    // Raw beam/length: 9.07365 / 46.578731517043. Scale = metres * 16 / raw extent; Y follows Z.
    private val pillnitzScale = Vec(18.074314085291, 18.109552847985, 18.109552847985)

    // Tempete: 76 m overall and 17.6 m beam; 73.6 m is between perpendiculars.
    // https://navypedia.org/ships/france/fr_bb_tempete.htm
    // Rounded trial draught of 5.4 m; draught at maximum load varies.
    // https://cnum.cnam.fr/pgi/sresrech.php?PTFSA2.2/0061= (trial table: 5.32-5.38 m)
    // Raw beam/draught/length: 10.700001108395 / 3.25 / 47.621556445726. Scale = metres * 16 / raw extent.
    private val tempeteScale = Vec(26.317754283134, 26.584615384615, 25.534654697518)

    // Hulls follow the model's changing beam; spars and rigging do not obstruct water travel.
    private val dollyHull =
        listOf(
            box(dollyScale, 7.6, -2.65, -14.3, 8.4, 2.3, -12.0),
            box(dollyScale, 6.1, -2.65, -12.0, 9.9, 2.3, -3.0),
            box(dollyScale, 4.65, -2.65, -3.0, 11.35, 2.0, 23.0),
            box(dollyScale, 6.1, -2.65, 23.0, 9.9, 2.1, 28.5),
            box(dollyScale, 7.6, -1.0, 28.5, 8.4, 2.1, 31.0),
        )

    private val pillnitzHull =
        listOf(
            box(pillnitzScale, 7.65, -0.9, -15.2, 8.35, 1.18, -12.0),
            box(pillnitzScale, 6.65, -0.9, -12.0, 9.35, 1.18, -7.0),
            box(pillnitzScale, 5.85, -0.9, -7.0, 10.15, 1.18, 26.0),
            box(pillnitzScale, 6.5, -0.9, 26.0, 9.5, 1.18, 29.0),
            box(pillnitzScale, 7.5, -0.6, 29.0, 8.5, 1.18, 31.2),
            box(pillnitzScale, 7.91, -0.91, -11.0, 8.09, -0.79, 28.8),
            box(pillnitzScale, 7.96, -0.91, 30.05, 8.04, 0.58, 31.45),
        )

    private val tempeteHull =
        listOf(
            box(tempeteScale, 7.5, -2.7, -15.2, 8.5, 0.9, -11.5),
            box(tempeteScale, 5.4, -2.7, -11.5, 10.6, 0.9, -5.4),
            box(tempeteScale, 3.7, -2.7, -5.4, 12.3, 0.9, 2.2),
            box(tempeteScale, 2.7, -2.7, 2.2, 13.3, 0.9, 20.0),
            box(tempeteScale, 3.5, -2.7, 20.0, 12.5, 0.9, 25.0),
            box(tempeteScale, 5.5, -2.7, 25.0, 10.5, 0.9, 28.5),
            box(tempeteScale, 7.3, -2.7, 28.5, 8.7, 0.9, 31.0),
            // The lower bilge reaches the full draught and tapers inside the wider upper hull.
            box(tempeteScale, 7.856, -3.25, -12.7, 8.144, -2.62, -9.64),
            box(tempeteScale, 6.488, -3.25, -9.64, 9.512, -2.62, -4.06),
            box(tempeteScale, 5.084, -3.25, -4.06, 10.916, -2.62, 2.78),
            box(tempeteScale, 4.148, -3.25, 2.78, 11.852, -2.62, 18.8),
            box(tempeteScale, 4.616, -3.25, 18.8, 11.384, -2.62, 23.21),
            box(tempeteScale, 5.912, -3.25, 23.21, 10.088, -2.62, 26.45),
            box(tempeteScale, 7.496, -3.25, 26.45, 8.504, -2.62, 28.52),
            // Rotated ram bounds and the narrow rudder contribute to overall length.
            box(tempeteScale, 7.86, -1.357440219327, -15.691556445726, 8.14, 0.757440219327, -14.708443554274),
            box(tempeteScale, 7.92, -2.85, 31.46, 8.08, -0.35, 31.93),
        )

    val dolly =
        Boat(
            name = "dolly",
            itemName = Component.text("SL Dolly", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
            itemLore = listOf(Component.text("Steam launch · 1 crew seat", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)),
            model = "aechronis:dolly-body",
            scale = dollyScale.z,
            modelScale = dollyScale,
            hitbox =
                Hitbox(
                    dollyHull +
                        listOf(
                            box(dollyScale, 5.4, 2.0, -2.7, 10.6, 5.8, 7.7),
                            box(dollyScale, 7.5, 2.0, 11.4, 8.5, 9.7, 12.7),
                        ),
                ),
            waterHitbox = Hitbox(dollyHull),
            waterlineOffset = -dollyScale.y / 2,
            health =
                Health(
                    250F,
                    mapOf(
                        AmmoTypes.NORMAL to 3F,
                        AmmoTypes.EXPLOSIVE to 75F,
                        AmmoTypes.BOMB to 150F,
                        AmmoTypes.MISSILE to 150F,
                    ),
                ),
            placeTime = 2000,
            maxSpeed = 0.33528F,
            acceleration = 0.008F,
            braking = 0.015F,
            friction = 0.98F,
            turnSpeed = 2.0F,
            maxClimbHeight = 0.5F,
            helmSeat = VehicleSeat("helm", "Helmsman", VehicleSeatRole.DRIVER, point(dollyScale, 9.8, 0.7, 15.4)),
            invisibleWhileRiding = false,
            invulnerableWhileRiding = false,
            animatedParts =
                listOf(
                    AnimatedPart.propeller(
                        model = "aechronis:dolly-propeller",
                        offset = point(dollyScale, 8.0, -1.5, 27.0),
                        radius = 0.88 * dollyScale.y / 16,
                    ),
                ),
            collisionHitbox = dollyCollision(dollyScale),
        )

    val pillnitz =
        Boat(
            name = "pillnitz",
            itemName = Component.text("PS Pillnitz", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
            itemLore =
                listOf(
                    Component
                        .text(
                            "Armed paddle transport · 2 crew seats · 1 gun station",
                            NamedTextColor.GRAY,
                        ).decoration(TextDecoration.ITALIC, false),
                    Component.text("1 × 37 mm Hotchkiss", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                ),
            model = "aechronis:pillnitz-body",
            scale = pillnitzScale.z,
            modelScale = pillnitzScale,
            hitbox =
                Hitbox(
                    pillnitzHull +
                        listOf(
                            box(pillnitzScale, 3.5, -0.7, 9.5, 12.5, 2.7, 14.0),
                            box(pillnitzScale, 6.5, 1.18, 7.4, 9.5, 3.15, 11.8),
                            box(pillnitzScale, 7.5, 2.6, 11.0, 8.5, 8.8, 12.5),
                        ),
                ),
            waterHitbox = Hitbox(pillnitzHull),
            waterlineOffset = -pillnitzScale.y / 2,
            health =
                Health(
                    700F,
                    mapOf(
                        AmmoTypes.NORMAL to 2F,
                        AmmoTypes.EXPLOSIVE to 75F,
                        AmmoTypes.BOMB to 150F,
                        AmmoTypes.MISSILE to 150F,
                    ),
                ),
            placeTime = 4000,
            maxSpeed = 0.54166668F,
            acceleration = 0.004F,
            braking = 0.008F,
            friction = 0.99F,
            turnSpeed = 0.9F,
            maxClimbHeight = 0.5F,
            helmSeat = VehicleSeat("helm", "Helmsman", VehicleSeatRole.DRIVER, point(pillnitzScale, 8.0, 1.22, 29.6)),
            invisibleWhileRiding = false,
            invulnerableWhileRiding = false,
            armament =
                BoatArmament(
                    ammo = Ammo.artilleryShell,
                    weapons =
                        listOf(
                            BoatWeapon(
                                name = "37 mm deck gun",
                                id = "deck-gun",
                                model = "aechronis:pillnitz-deck-gun",
                                pivotOffset = point(pillnitzScale, 8.55, 1.98, -7.2),
                                muzzleOffsets = listOf(vector(pillnitzScale, 0.0, 0.705, 1.08)),
                                operatorOffset = point(pillnitzScale, 8.55, 1.3, -5.6),
                                interactionHitbox = Hitbox(listOf(HitboxPart(Vec(-0.154, 0.505, 0.103), Vec(0.67, 0.62, 1.21)))),
                                maxYaw = 55F,
                                traverseSpeed = 3F,
                                reloadTime = 1500,
                                projectileSpeed = 4.0,
                                projectileExplosionRadius = 1,
                                projectileExplosionDamage = 24F,
                                projectileMaxRange = 192.0,
                            ),
                        ),
                ),
            animatedParts =
                listOf(
                    AnimatedPart.rollingWheel(
                        model = "aechronis:pillnitz-paddle-left",
                        offset = point(pillnitzScale, 4.61, 0.7, 11.8),
                        radius = 1.8 * pillnitzScale.y / 16,
                        rotationDirection = -1.0,
                    ),
                    AnimatedPart.rollingWheel(
                        model = "aechronis:pillnitz-paddle-right",
                        offset = point(pillnitzScale, 11.39, 0.7, 11.8),
                        radius = 1.8 * pillnitzScale.y / 16,
                        rotationDirection = -1.0,
                    ),
                ),
            collisionHitbox = pillnitzCollision(pillnitzScale),
        )

    val tempete =
        Boat(
            name = "tempete",
            itemName = Component.text("Tempête", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
            itemLore =
                listOf(
                    Component
                        .text(
                            "Coastal ironclad · 6 crew seats · 5 gun stations",
                            NamedTextColor.GRAY,
                        ).decoration(TextDecoration.ITALIC, false),
                    Component.text("2 × 274 mm · 4 × 37 mm", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                ),
            model = "aechronis:tempete-body",
            scale = tempeteScale.z,
            modelScale = tempeteScale,
            hitbox =
                Hitbox(
                    tempeteHull +
                        listOf(
                            box(tempeteScale, 4.4, 0.9, -1.4, 11.6, 1.96, 20.1),
                            box(tempeteScale, 4.7, 1.96, -2.8, 11.3, 4.425, 3.8),
                            box(tempeteScale, 7.25, 1.96, 5.1, 8.75, 4.68, 20.3),
                            box(tempeteScale, 5.0, 4.52, 4.4, 11.0, 4.74, 21.4),
                            box(tempeteScale, 7.3, 4.74, 6.0, 8.7, 8.1, 7.2),
                        ),
                ),
            waterHitbox = Hitbox(tempeteHull),
            waterlineOffset = -tempeteScale.y / 2,
            health =
                Health(
                    1800F,
                    mapOf(
                        AmmoTypes.NORMAL to 1F,
                        AmmoTypes.EXPLOSIVE to 75F,
                        AmmoTypes.BOMB to 150F,
                        AmmoTypes.MISSILE to 150F,
                    ),
                ),
            placeTime = 6000,
            maxSpeed = 0.90285F,
            acceleration = 0.002F,
            braking = 0.005F,
            friction = 0.992F,
            turnSpeed = 0.5F,
            maxClimbHeight = 0.5F,
            helmSeat = VehicleSeat("helm", "Helmsman", VehicleSeatRole.DRIVER, point(tempeteScale, 8.0, 4.74, 10.0)),
            invisibleWhileRiding = false,
            invulnerableWhileRiding = false,
            armament =
                BoatArmament(
                    ammo = Ammo.artilleryShell,
                    weapons =
                        listOf(
                            BoatWeapon(
                                name = "Twin 274 mm turret",
                                id = "main-turret",
                                model = "aechronis:tempete-turret",
                                pivotOffset = point(tempeteScale, 8.0, 1.96, 0.5),
                                // Both barrels share one central firing point so the optical seat stays steady between shots.
                                muzzleOffsets = listOf(vector(tempeteScale, 0.0, 0.76, 4.032), vector(tempeteScale, 0.0, 0.76, 4.032)),
                                operatorOffset = point(tempeteScale, 8.0, 1.5, 0.5),
                                interactionHitbox = Hitbox(listOf(HitboxPart(Vec(0.0, 2.098, 0.571), Vec(5.53, 2.1, 5.94)))),
                                maxYaw = 180F,
                                scopeEyeDistance = 3.5,
                                traverseSpeed = 0.8F,
                                reloadTime = 10000,
                                projectileSpeed = 4.0,
                                projectileExplosionRadius = 4,
                                projectileExplosionDamage = 100F,
                                projectileMaxRange = 256.0,
                            ),
                            BoatWeapon(
                                name = "Port forward 37 mm gun",
                                id = "port-forward",
                                model = "aechronis:tempete-light-gun-port-fore",
                                pivotOffset = point(tempeteScale, 5.7, 5.49, 8.3),
                                muzzleOffsets = listOf(vector(tempeteScale, 0.704, 0.3, 0.0)),
                                operatorOffset = point(tempeteScale, 6.6, 4.8, 8.3),
                                interactionHitbox = Hitbox(listOf(HitboxPart(Vec(0.274, 0.328, 0.114), Vec(0.97, 0.44, 0.5)))),
                                neutralYaw = -90F,
                                maxYaw = 45F,
                                traverseSpeed = 3F,
                                reloadTime = 1800,
                                projectileSpeed = 4.0,
                                projectileExplosionRadius = 1,
                                projectileExplosionDamage = 24F,
                                projectileMaxRange = 192.0,
                            ),
                            BoatWeapon(
                                name = "Port aft 37 mm gun",
                                id = "port-aft",
                                model = "aechronis:tempete-light-gun-port-aft",
                                pivotOffset = point(tempeteScale, 5.7, 5.49, 20.7),
                                muzzleOffsets = listOf(vector(tempeteScale, 0.704, 0.3, 0.0)),
                                operatorOffset = point(tempeteScale, 6.6, 4.8, 20.7),
                                interactionHitbox = Hitbox(listOf(HitboxPart(Vec(0.274, 0.328, 0.114), Vec(0.97, 0.44, 0.5)))),
                                neutralYaw = -90F,
                                maxYaw = 45F,
                                traverseSpeed = 3F,
                                reloadTime = 1800,
                                projectileSpeed = 4.0,
                                projectileExplosionRadius = 1,
                                projectileExplosionDamage = 24F,
                                projectileMaxRange = 192.0,
                            ),
                            BoatWeapon(
                                name = "Starboard forward 37 mm gun",
                                id = "starboard-forward",
                                model = "aechronis:tempete-light-gun-starboard-fore",
                                pivotOffset = point(tempeteScale, 10.3, 5.49, 8.3),
                                muzzleOffsets = listOf(vector(tempeteScale, -0.704, 0.3, 0.0)),
                                operatorOffset = point(tempeteScale, 9.4, 4.8, 8.3),
                                interactionHitbox = Hitbox(listOf(HitboxPart(Vec(-0.274, 0.328, -0.114), Vec(0.97, 0.44, 0.5)))),
                                neutralYaw = 90F,
                                maxYaw = 45F,
                                traverseSpeed = 3F,
                                reloadTime = 1800,
                                projectileSpeed = 4.0,
                                projectileExplosionRadius = 1,
                                projectileExplosionDamage = 24F,
                                projectileMaxRange = 192.0,
                            ),
                            BoatWeapon(
                                name = "Starboard aft 37 mm gun",
                                id = "starboard-aft",
                                model = "aechronis:tempete-light-gun-starboard-aft",
                                pivotOffset = point(tempeteScale, 10.3, 5.49, 20.7),
                                muzzleOffsets = listOf(vector(tempeteScale, -0.704, 0.3, 0.0)),
                                operatorOffset = point(tempeteScale, 9.4, 4.8, 20.7),
                                interactionHitbox = Hitbox(listOf(HitboxPart(Vec(-0.274, 0.328, -0.114), Vec(0.97, 0.44, 0.5)))),
                                neutralYaw = 90F,
                                maxYaw = 45F,
                                traverseSpeed = 3F,
                                reloadTime = 1800,
                                projectileSpeed = 4.0,
                                projectileExplosionRadius = 1,
                                projectileExplosionDamage = 24F,
                                projectileMaxRange = 192.0,
                            ),
                        ),
                ),
            animatedParts =
                listOf(
                    AnimatedPart.propeller(
                        model = "aechronis:tempete-propeller",
                        offset = point(tempeteScale, 8.0, -1.75, 31.08),
                        radius = 1.33 * tempeteScale.y / 16,
                    ),
                ),
            collisionHitbox = tempeteCollision(tempeteScale),
        )

    val all: List<Boat> = listOf(dolly, pillnitz, tempete)

    // Item displays turn the model's -Z bow toward vehicle-local +Z and mirror X.
    // All editable asset coordinates have their waterline at Y=0 and model center at (8, 8, 8).
    private fun point(
        scale: Vec,
        x: Double,
        y: Double,
        z: Double,
    ): Vec = vector(scale, 8.0 - x, y - 8.0, 8.0 - z)

    /** Scale a vehicle-local displacement, already oriented with +Z toward the bow. */
    private fun vector(
        scale: Vec,
        x: Double,
        y: Double,
        z: Double,
    ): Vec = Vec(x * scale.x / 16, y * scale.y / 16, z * scale.z / 16)

    private fun box(
        scale: Vec,
        minX: Double,
        minY: Double,
        minZ: Double,
        maxX: Double,
        maxY: Double,
        maxZ: Double,
    ): HitboxPart =
        HitboxPart(
            point(scale, (minX + maxX) / 2, (minY + maxY) / 2, (minZ + maxZ) / 2),
            vector(scale, (maxX - minX) / 2, (maxY - minY) / 2, (maxZ - minZ) / 2),
        )

    private fun dollyCollision(scale: Vec): ShulkerHitbox =
        CollisionBuilder(scale)
            .apply {
                // Recessed internal sole, including the tapered cockpit and machinery well.
                surface(
                    symmetricOutline(
                        listOf(
                            7.8784 to -12.0,
                            6.86 to -8.73588,
                            5.986 to -2.81694,
                            5.378 to 3.27608,
                            5.231168 to 10.23954,
                            5.53 to 16.33256,
                            6.062 to 20.68472,
                            6.746 to 24.16644,
                            7.43 to 27.3,
                        ),
                    ),
                    0.43,
                    0.9,
                )
                surface(
                    symmetricOutline(listOf(7.85 to -14.15, 6.67 to -10.8, 6.84 to -10.6)),
                    2.29,
                    0.65,
                )
                surface(
                    symmetricOutline(listOf(6.04 to 23.85, 5.9 to 24.06, 6.45 to 27.0, 7.3 to 30.6)),
                    2.05,
                    0.8,
                    edgeCubeSize = 0.2,
                )
                surface(6.34, 9.66, 18.1, 24.15, 0.62, 0.7)

                // The closed saloon is too low for a standing player; its cambered roof
                // is a separate surface extending past the cabin sides.
                solid(5.2, 0.43, -2.5, 10.8, 5.3, 7.5)
                surface(4.957754, 6.2, -2.74, 7.74, 5.4, 0.3)
                surface(6.2, 9.8, -2.74, 7.74, 5.7, 0.8)
                surface(9.8, 11.042246, -2.74, 7.74, 5.4, 0.3)

                // Cockpit seats and the low, exposed boiler and engine.
                surface(6.07, 6.62, -9.3, -3.3, 1.13, 0.15)
                surface(9.38, 9.93, -9.3, -3.3, 1.13, 0.15)
                surface(5.94, 10.06, -3.33, -2.75, 1.13, 0.16)
                solid(6.78, 0.43, 8.61, 9.22, 2.16, 12.79)
                solid(7.3, 0.43, 14.7, 8.7, 3.0, 15.9)
                rail(Vec(8.0, 2.16, 11.67), Vec(8.0, 9.57, 12.42), 0.28)

                // Actual gunwale centerline; the low sides remain naturally stepable.
                val gunwale =
                    listOf(
                        Vec(7.84, 2.76, -14.55),
                        Vec(6.5, 2.487438, -10.8),
                        Vec(5.35, 2.15102, -4.0),
                        Vec(4.55, 1.963414, 3.0),
                        Vec(4.3568, 1.88624, 11.0),
                        Vec(4.75, 1.913538, 18.0),
                        Vec(5.45, 1.989858, 23.0),
                        Vec(6.35, 2.094118, 27.0),
                        Vec(7.25, 2.23, 30.6),
                    )
                val perimeter = gunwale + gunwale.asReversed().map { Vec(16.0 - it.x, it.y, it.z) }
                (perimeter + perimeter.first()).zipWithNext { from, to -> rail(from, to, 0.12) }

                // Well coamings meet the visible raised hatch edges.
                rail(Vec(6.39, 1.85, 8.15), Vec(9.61, 1.85, 8.15), 0.12)
                for (x in listOf(6.39, 9.61)) {
                    rail(Vec(x, 1.85, 8.15), Vec(x, 1.85, 13.82), 0.12)
                    rail(Vec(x, 1.87, 18.0), Vec(x, 1.87, 24.2), 0.12)
                }
                rail(Vec(6.39, 1.87, 24.2), Vec(9.61, 1.87, 24.2), 0.12)
                surface(6.16, 7.0, 18.05, 20.75, 2.2, 0.2)
                surface(7.0, 9.0, 18.05, 20.75, 2.65, 0.5)
                surface(9.0, 9.84, 18.05, 20.75, 2.2, 0.2)
            }.build()

    private fun pillnitzCollision(scale: Vec): ShulkerHitbox =
        CollisionBuilder(scale)
            .apply {
                // continuous-timber-deck outline, including the full bow and stern.
                surface(
                    symmetricOutline(
                        listOf(
                            7.89 to -15.0,
                            7.38 to -13.2,
                            6.52 to -9.0,
                            5.98 to -4.0,
                            5.788126 to 0.0,
                            5.788126 to 23.5,
                            6.29 to 27.0,
                            7.23 to 29.6,
                            7.86 to 31.0,
                        ),
                    ),
                    1.18,
                )

                solid(6.55, 1.18, 7.45, 9.45, 2.83, 11.65)
                solid(7.33, 1.18, 4.9, 8.67, 2.05, 6.45)
                surface(7.23, 8.77, 4.75, 6.58, 2.05, 0.6)
                surface(6.55, 9.45, 7.45, 10.35, 3.09)
                // These thin overhangs preserve standing clearance beside the trunk.
                surface(5.76, 6.55, 7.2, 10.35, 3.09, 0.3)
                surface(9.45, 10.24, 7.2, 10.35, 3.09, 0.3)
                surface(6.55, 9.45, 7.2, 7.45, 3.09, 0.25)
                rail(Vec(8.0, 2.65, 11.69), Vec(8.0, 9.3, 12.39), 0.75)

                // The two paddle housings close the intentional gaps in promenade rails.
                // The six levels follow curved-paddle-sponson internal fill elements.
                val sponsonSections =
                    listOf(
                        Triple(6.7, 7.5, 2.87),
                        Triple(7.5, 10.6, 3.02),
                        Triple(10.6, 12.3, 3.01),
                        Triple(12.3, 13.1, 2.81),
                        Triple(13.1, 13.72, 2.39),
                        Triple(13.72, 14.2, 1.72),
                    )
                for ((minX, maxX) in listOf(3.515175 to 5.813126, 10.186874 to 12.484825)) {
                    for ((minZ, maxZ, top) in sponsonSections) {
                        solid(minX, 1.1, minZ, maxX, top, maxZ)
                    }
                }

                val foreRail =
                    listOf(
                        7.291667 to -12.5,
                        6.575 to -9.0,
                        6.035 to -4.0,
                        5.843126 to 0.0,
                        5.843126 to 6.6,
                    )
                val aftRail =
                    listOf(
                        5.843126 to 14.35,
                        5.843126 to 23.5,
                        6.345 to 27.0,
                        7.285 to 29.6,
                        7.735 to 30.6,
                    )
                for (outline in listOf(foreRail, aftRail)) {
                    for (mirror in listOf(false, true)) {
                        outline
                            .map { (x, z) -> Vec(if (mirror) 16.0 - x else x, 2.09, z) }
                            .zipWithNext { from, to -> rail(from, to, 0.3) }
                    }
                }
                for (x in listOf(5.95, 10.05)) rail(Vec(x, 3.71, 7.2), Vec(x, 3.71, 10.35), 0.3)

                solid(7.86, 1.18, 28.78, 8.14, 1.96, 29.02)
            }.build()

    private fun symmetricOutline(port: List<Pair<Double, Double>>): List<Pair<Double, Double>> =
        port + port.asReversed().map { (x, z) -> 16.0 - x to z }

    private fun tempeteCollision(scale: Vec): ShulkerHitbox =
        CollisionBuilder(scale)
            .apply {
                surface(
                    mirroredOutline(
                        -14.931 to 0.197,
                        -11.5412 to 2.0685,
                        -5.3598 to 3.98925,
                        2.2174 to 5.26975,
                        19.964 to 5.26975,
                        24.8493 to 4.6295,
                        28.4385 to 2.8565,
                        30.7316 to 0.6895,
                    ),
                    top = 0.9,
                )

                // The rounded stern of the breastwork is the landing for both aft stairs.
                surface(
                    mirroredOutline(
                        -3.7882 to 0.297,
                        -2.989 to 2.7225,
                        -1.3906 to 3.564,
                        20.0879 to 3.564,
                        22.0859 to 2.7225,
                        23.2847 to 0.99,
                    ),
                    top = 1.96,
                )

                // Preserve standing clearance beneath the cantilevered hurricane deck.
                surface(
                    mirroredOutline(
                        3.9164 to 2.4625,
                        4.4144 to 2.955,
                        21.3464 to 2.955,
                        21.8444 to 2.4625,
                    ),
                    top = 4.73,
                    maxCubeSize = 2.5,
                )

                for (minX in listOf(5.57, 9.67)) {
                    repeat(6) { step ->
                        surface(
                            minX,
                            minX + 0.76,
                            24.35 - step * 0.43,
                            24.65 - step * 0.43,
                            top = 1.065 + step * 0.177,
                            maxCubeSize = 0.5,
                        )
                    }
                }
                for (x in listOf(5.53, 10.47)) {
                    rail(Vec(x, 1.68, 24.7), Vec(x, 2.7, 22.1), thickness = 0.3)
                }

                // Turret armour is circular; the offset sighting hood and barrels
                // traverse independently and cannot use collision fixed to the hull.
                column(circle(8.0, 0.5, 3.3), bottom = 1.96, top = 3.715)
                solid(7.25, 1.96, 5.1, 8.75, 4.52, 20.3)
                column(circle(8.0, 20.3, 0.75), bottom = 1.96, top = 4.52)
                column(circle(8.0, 6.6, 0.61), bottom = 4.73, top = 8.105)
                // Main mast, bridge instruments, and the two ventilators occupy the deck.
                solid(7.91, 4.73, 13.01, 8.09, 13.5, 13.19)
                solid(7.74, 4.73, 4.39, 8.26, 5.39, 4.91)
                for (z in listOf(10.2, 17.7)) {
                    solid(7.83, 4.73, z - 0.17, 8.17, 6.13, z + 0.17)
                }

                surface(7.25, 8.75, 26.7, 28.0, top = 1.11)
                solid(7.65, 0.9, -8.85, 8.35, 1.455, -8.15)

                val sheer =
                    mirroredOutline(
                        -14.77 to 0.1956,
                        -11.404 to 2.0538,
                        -5.266 to 3.9609,
                        2.258 to 5.2323,
                        19.88 to 5.2323,
                        24.731 to 4.5966,
                        28.295 to 2.8362,
                        30.572 to 0.6846,
                    )
                for (index in sheer.indices) {
                    val from = sheer[index]
                    val to = sheer[(index + 1) % sheer.size]
                    rail(Vec(from.first, 1.65, from.second), Vec(to.first, 1.65, to.second), thickness = 0.4)
                }

                // Side handrails and the solid forward/aft screens form one closed boundary.
                for (x in listOf(5.05, 10.95)) {
                    rail(Vec(x, 5.54, 4.65), Vec(x, 5.54, 21.35), thickness = 0.35)
                }
                rail(Vec(5.55, 5.335, 3.97), Vec(10.45, 5.335, 3.97), thickness = 0.35)
                rail(Vec(5.55, 5.335, 21.83), Vec(10.45, 5.335, 21.83), thickness = 0.35)
                rail(Vec(5.05, 5.335, 4.65), Vec(5.55, 5.335, 3.97), thickness = 0.35)
                rail(Vec(10.95, 5.335, 4.65), Vec(10.45, 5.335, 3.97), thickness = 0.35)
                rail(Vec(5.05, 5.335, 21.35), Vec(5.55, 5.335, 21.83), thickness = 0.35)
                rail(Vec(10.95, 5.335, 21.35), Vec(10.45, 5.335, 21.83), thickness = 0.35)
            }.build()

    /** Stations are (Z, half beam), with the model's centerline at X=8. */
    private fun mirroredOutline(vararg stations: Pair<Double, Double>): List<Pair<Double, Double>> =
        stations.map { (z, halfWidth) -> (8.0 + halfWidth) to z } +
            stations.reversed().map { (z, halfWidth) -> (8.0 - halfWidth) to z }

    private fun circle(
        x: Double,
        z: Double,
        radius: Double,
    ): List<Pair<Double, Double>> =
        (0 until 16).map { index ->
            val angle = index * PI / 8
            (x + radius * cos(angle)) to (z + radius * sin(angle))
        }
}
