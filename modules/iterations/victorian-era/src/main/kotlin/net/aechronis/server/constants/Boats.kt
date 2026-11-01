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
import kotlin.math.sqrt

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

    // Geometry follows tools/blockbench/boats/build-hms-devastation.cjs; model waterline is Y=0.
    // 307 ft overall and 62 ft 3 in beam; one block represents one metre.
    // https://en.wikipedia.org/wiki/HMS_Devastation_(1871)
    // Uniform scale: 93.5736 metres * 16 / 46 model units.
    private val hmsDevastationScale = Vec(32.547339130434786, 32.547339130434786, 32.547339130434786)
    private val hmsDevastationHalfBeam = 18.9738 * 8 / hmsDevastationScale.x

    // The upper works occupy the 150 ft central breastwork, leaving long clear
    // fore and aft weather decks. Circular mounts retain their diameter.
    private fun hmsDevastationUpperZ(z: Double): Double = 8 + (z - 8) * 0.65

    private val hmsDevastationForeDeckEnd = hmsDevastationUpperZ(-10.0)
    private val hmsDevastationHullStations =
        listOf(
            -15.0 to 0.08,
            -13.0 to 1.5,
            -10.0 to 3.0,
            -6.0 to 4.15,
            0.0 to hmsDevastationHalfBeam,
            18.0 to hmsDevastationHalfBeam,
            24.0 to 4.1,
            28.0 to 2.65,
            30.0 to 1.4,
            31.0 to 0.12,
        )
    private val hmsDevastationBreastworkStations =
        listOf(
            -10.0 to 0.3,
            -9.0 to 2.4,
            -7.0 to 3.95,
            21.5 to 3.95,
            24.0 to 2.4,
            25.2 to 0.15,
        ).map { (z, width) -> hmsDevastationUpperZ(z) to width }
    private val hmsDevastationHurricaneOutline =
        listOf(
            4.4 to -0.8,
            11.6 to -0.8,
            11.6 to 16.0,
            4.4 to 16.0,
        ).map { (x, z) -> x to hmsDevastationUpperZ(z) }

    private fun hmsDevastationHalfWidth(z: Double): Double {
        val (start, end) = hmsDevastationHullStations.zipWithNext().first { (_, end) -> z <= end.first }
        return start.second + (end.second - start.second) * (z - start.first) / (end.first - start.first)
    }

    // The narrow bilge and fine bow/stern follow the visible hull. Water sampling
    // needs only its upper footprint, avoiding repeated samples of submerged courses.
    private val hmsDevastationWaterHull =
        ((0..46).map { -15.0 + it } + hmsDevastationForeDeckEnd).sorted().zipWithNext { z, endZ ->
            val width = hmsDevastationHalfWidth((z + endZ) / 2)
            box(hmsDevastationScale, 8.0 - width, -0.12, z, 8.0 + width, if (z < hmsDevastationForeDeckEnd) 1.3 else 0.7, endZ)
        }
    private val hmsDevastationHull =
        hmsDevastationWaterHull +
            (0 until 23).flatMap { station ->
                val z = -15.0 + station * 2
                val mid = z + 1
                val width = hmsDevastationHalfWidth(mid)
                val fullness = maxOf(0.12, minOf(1.0, (mid + 15) / 9, (31 - mid) / 8))
                // Four collision courses approximate the model's faceted elliptical bilge.
                (0 until 4).map { layer ->
                    val bottom = -3.9 + layer * 3.78 / 4
                    val top = bottom + 3.78 / 4
                    val depth = ((bottom + top) / 2 + 0.12) / 3.78
                    val fraction = sqrt(1 - depth * depth) * 0.97
                    box(hmsDevastationScale, 8 - width * fraction, bottom * fullness, z, 8 + width * fraction, top * fullness, z + 2)
                }
            } +
            listOf(
                box(hmsDevastationScale, 7.91, -3.92, -7.0, 8.09, -3.73, 23.0),
                box(hmsDevastationScale, 7.94, -3.4, 29.5, 8.06, -0.3, 30.8),
            )

    private fun hmsDevastationTurretHousing(z: Double): List<HitboxPart> =
        (0 until 16).map { section ->
            val minZ = z - 2.35 + section * 4.7 / 16
            val maxZ = minZ + 4.7 / 16
            val depth = (minZ + maxZ) / 2 - z
            val width = sqrt(2.35 * 2.35 - depth * depth)
            box(hmsDevastationScale, 8 - width, 1.8, minZ, 8 + width, 3.2, maxZ)
        }

    val hmsDevastation =
        Boat(
            name = "hms-devastation",
            itemName = Component.text("HMS Devastation", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
            itemLore =
                listOf(
                    Component.text("British turret battleship · 1873", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                    Component.text("3 crew seats · 2 twin 12-inch turrets", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                ),
            model = "aechronis:hms-devastation-body",
            scale = hmsDevastationScale.z,
            modelScale = hmsDevastationScale,
            hitbox =
                Hitbox(
                    hmsDevastationHull + hmsDevastationTurretHousing(hmsDevastationUpperZ(-5.5)) +
                        hmsDevastationTurretHousing(hmsDevastationUpperZ(21.0)) +
                        listOf(
                            box(hmsDevastationScale, 4.05, 0.7, hmsDevastationUpperZ(-8.0), 11.95, 1.8, hmsDevastationUpperZ(22.0)),
                            box(hmsDevastationScale, 6.0, 1.8, hmsDevastationUpperZ(0.0), 10.0, 4.0, hmsDevastationUpperZ(15.0)),
                            box(hmsDevastationScale, 6.1, 4.15, hmsDevastationUpperZ(-0.5), 9.9, 5.1, hmsDevastationUpperZ(1.15)),
                            box(hmsDevastationScale, 4.4, 4.0, hmsDevastationUpperZ(-0.8), 11.6, 4.15, hmsDevastationUpperZ(16.0)),
                            box(
                                hmsDevastationScale,
                                7.35,
                                4.15,
                                hmsDevastationUpperZ(3.2) - 0.65,
                                8.65,
                                8.05,
                                hmsDevastationUpperZ(3.2) + 0.65,
                            ),
                            box(
                                hmsDevastationScale,
                                7.35,
                                4.15,
                                hmsDevastationUpperZ(9.8) - 0.65,
                                8.65,
                                8.05,
                                hmsDevastationUpperZ(9.8) + 0.65,
                            ),
                        ),
                ),
            waterHitbox = Hitbox(hmsDevastationWaterHull),
            waterlineOffset = -hmsDevastationScale.y / 2,
            health =
                Health(
                    2400F,
                    mapOf(
                        AmmoTypes.NORMAL to 1F,
                        AmmoTypes.EXPLOSIVE to 75F,
                        AmmoTypes.BOMB to 150F,
                        AmmoTypes.MISSILE to 150F,
                    ),
                ),
            placeTime = 8000,
            // 13.5 knots at twenty simulation ticks per second.
            maxSpeed = 0.34725F,
            acceleration = 0.0015F,
            braking = 0.004F,
            friction = 0.994F,
            turnSpeed = 0.35F,
            maxClimbHeight = 0.5F,
            helmSeat =
                VehicleSeat(
                    "helm",
                    "Helmsman",
                    VehicleSeatRole.DRIVER,
                    point(hmsDevastationScale, 8.0, 5.1, hmsDevastationUpperZ(0.5)),
                ),
            invisibleWhileRiding = false,
            invulnerableWhileRiding = false,
            armament =
                BoatArmament(
                    ammo = Ammo.artilleryShell,
                    // Keep weapon constructors literal for the website catalogue exporter.
                    weapons =
                        listOf(
                            BoatWeapon(
                                name = "Forward twin 12-inch turret",
                                id = "fore-turret",
                                model = "aechronis:hms-devastation-turret-fore",
                                pivotOffset = point(hmsDevastationScale, 8.0, 1.8, hmsDevastationUpperZ(-5.5)),
                                muzzleOffsets =
                                    listOf(
                                        vector(hmsDevastationScale, -0.55, 0.7, 3.52),
                                        vector(
                                            hmsDevastationScale,
                                            0.55,
                                            0.7,
                                            3.52,
                                        ),
                                    ),
                                operatorOffset = point(hmsDevastationScale, 8.0, 1.8, hmsDevastationUpperZ(-5.5)),
                                interactionHitbox =
                                    Hitbox(
                                        listOf(
                                            HitboxPart(
                                                vector(hmsDevastationScale, 0.0, 0.8, 0.0),
                                                vector(hmsDevastationScale, 2.4, 0.85, 2.4),
                                            ),
                                            HitboxPart(
                                                vector(hmsDevastationScale, 0.0, 0.7, 2.9),
                                                vector(hmsDevastationScale, 0.8, 0.35, 0.65),
                                            ),
                                        ),
                                    ),
                                neutralYaw = 0F,
                                maxYaw = 135F,
                                scopeEyeDistance = 3.5,
                                traverseSpeed = 0.65F,
                                minPitch = -10F,
                                maxPitch = 5F,
                                reloadTime = 12000,
                                projectileSpeed = 4.0,
                                projectileExplosionRadius = 4,
                                projectileExplosionDamage = 110F,
                                projectileMaxRange = 288.0,
                            ),
                            BoatWeapon(
                                name = "Aft twin 12-inch turret",
                                id = "aft-turret",
                                model = "aechronis:hms-devastation-turret-aft",
                                pivotOffset = point(hmsDevastationScale, 8.0, 1.8, hmsDevastationUpperZ(21.0)),
                                muzzleOffsets =
                                    listOf(
                                        vector(hmsDevastationScale, -0.55, 0.7, -3.52),
                                        vector(
                                            hmsDevastationScale,
                                            0.55,
                                            0.7,
                                            -3.52,
                                        ),
                                    ),
                                operatorOffset = point(hmsDevastationScale, 8.0, 1.8, hmsDevastationUpperZ(21.0)),
                                interactionHitbox =
                                    Hitbox(
                                        listOf(
                                            HitboxPart(
                                                vector(hmsDevastationScale, 0.0, 0.8, 0.0),
                                                vector(hmsDevastationScale, 2.4, 0.85, 2.4),
                                            ),
                                            HitboxPart(
                                                vector(hmsDevastationScale, 0.0, 0.7, -2.9),
                                                vector(hmsDevastationScale, 0.8, 0.35, 0.65),
                                            ),
                                        ),
                                    ),
                                neutralYaw = 180F,
                                maxYaw = 135F,
                                scopeEyeDistance = 3.5,
                                traverseSpeed = 0.65F,
                                minPitch = -10F,
                                maxPitch = 5F,
                                reloadTime = 12000,
                                projectileSpeed = 4.0,
                                projectileExplosionRadius = 4,
                                projectileExplosionDamage = 110F,
                                projectileMaxRange = 288.0,
                            ),
                        ),
                ),
            animatedParts =
                listOf(6.5, 9.5).map { x ->
                    AnimatedPart.propeller(
                        model = "aechronis:hms-devastation-propeller",
                        offset = point(hmsDevastationScale, x, -2.8, 28.8),
                        radius = 1.31 * hmsDevastationScale.y / 16,
                    )
                },
            collisionHitbox =
                CollisionBuilder(hmsDevastationScale)
                    .apply {
                        val deckBreak = hmsDevastationForeDeckEnd to hmsDevastationHalfWidth(hmsDevastationForeDeckEnd)
                        surface(
                            hmsDevastationOutline(hmsDevastationHullStations.filter { it.first < hmsDevastationForeDeckEnd } + deckBreak),
                            top = 1.3,
                        )
                        surface(
                            hmsDevastationOutline(
                                listOf(deckBreak) + hmsDevastationHullStations.filter { it.first > hmsDevastationForeDeckEnd },
                            ),
                            top = 0.7,
                        )
                        surface(hmsDevastationOutline(hmsDevastationBreastworkStations), top = 1.8)
                        surface(hmsDevastationHurricaneOutline, top = 4.15)
                        // The rotatable turret barrels are excluded from fixed player collision.
                        for (z in listOf(-5.5, 21.0)) {
                            column(hmsDevastationCircle(8.0, hmsDevastationUpperZ(z), 2.35), bottom = 1.8, top = 3.2)
                        }
                        solid(6.0, 1.8, hmsDevastationUpperZ(0.0), 10.0, 4.0, hmsDevastationUpperZ(15.0))
                        for (z in listOf(3.2, 9.8)) {
                            column(hmsDevastationCircle(8.0, hmsDevastationUpperZ(z), 0.65), bottom = 4.15, top = 8.05)
                        }
                        solid(7.88, 4.15, hmsDevastationUpperZ(12.88), 8.12, 12.8, hmsDevastationUpperZ(13.12))
                        solid(7.934, 12.8, hmsDevastationUpperZ(12.934), 8.066, 19.5, hmsDevastationUpperZ(13.066))
                        solid(6.1, 4.15, hmsDevastationUpperZ(-0.5), 9.9, 5.1, hmsDevastationUpperZ(1.15))
                        repeat(4) { step ->
                            surface(
                                6.9,
                                7.6,
                                hmsDevastationUpperZ(2.35 - (step + 1) * 0.3),
                                hmsDevastationUpperZ(2.35 - step * 0.3),
                                top = 4.15 + (step + 1) * 0.2375,
                                maxCubeSize = 0.5,
                            )
                        }
                        for (x in listOf(6.0, 9.3)) {
                            repeat(3) { step ->
                                surface(
                                    x,
                                    x + 0.7,
                                    hmsDevastationUpperZ(-10.9 + step * 0.3),
                                    hmsDevastationUpperZ(-10.6 + step * 0.3),
                                    top = 1.3 + (step + 1) * 0.5 / 3,
                                    maxCubeSize = 0.5,
                                )
                            }
                            surface(x, x + 0.7, hmsDevastationUpperZ(-10.0), hmsDevastationUpperZ(-8.8), top = 1.8)
                        }
                        for (x in listOf(4.4, 10.9)) {
                            surface(x, x + 0.7, hmsDevastationUpperZ(21.4), hmsDevastationUpperZ(24.0), top = 1.8)
                            repeat(6) { step ->
                                surface(
                                    x,
                                    x + 0.7,
                                    hmsDevastationUpperZ(25.8 - (step + 1) * 0.3),
                                    hmsDevastationUpperZ(25.8 - step * 0.3),
                                    top = 0.7 + (step + 1) * 1.1 / 6,
                                    maxCubeSize = 0.5,
                                )
                            }
                        }
                        for (x in listOf(4.8, 10.5)) {
                            repeat(12) { step ->
                                surface(
                                    x,
                                    x + 0.7,
                                    hmsDevastationUpperZ(18.4 - (step + 1) * 0.2),
                                    hmsDevastationUpperZ(18.4 - step * 0.2),
                                    top = 1.8 + (step + 1) * 2.35 / 12,
                                    maxCubeSize = 0.4,
                                )
                            }
                        }
                        for (sign in listOf(-1, 1)) {
                            val perimeter =
                                (0..35).map { index ->
                                    val z = -14.75 + index * 1.3
                                    Vec(
                                        8 + sign * (hmsDevastationHalfWidth(z) - 0.06),
                                        (if (z < hmsDevastationForeDeckEnd) 1.3 else 0.7) + 0.53,
                                        z,
                                    )
                                }
                            perimeter.zipWithNext { from, to -> rail(from, to, thickness = 0.3) }
                        }
                        for (x in listOf(4.5, 11.5)) {
                            rail(Vec(x, 4.65, hmsDevastationUpperZ(-0.8)), Vec(x, 4.65, hmsDevastationUpperZ(15.9)), thickness = 0.3)
                        }
                        rail(Vec(4.5, 4.65, hmsDevastationUpperZ(-0.74)), Vec(11.5, 4.65, hmsDevastationUpperZ(-0.74)), thickness = 0.3)
                        for ((minX, maxX) in listOf(4.4 to 4.8, 5.5 to 10.5, 11.2 to 11.6)) {
                            rail(Vec(minX, 4.65, hmsDevastationUpperZ(16.0)), Vec(maxX, 4.65, hmsDevastationUpperZ(16.0)), thickness = 0.3)
                        }
                        for (x in listOf(4.78, 5.52, 10.48, 11.22)) {
                            rail(Vec(x, 2.3, hmsDevastationUpperZ(18.4)), Vec(x, 4.65, hmsDevastationUpperZ(16.0)), thickness = 0.3)
                        }
                    }.build(),
        )

    private fun hmsDevastationOutline(stations: List<Pair<Double, Double>>): List<Pair<Double, Double>> =
        stations.map { (z, width) -> (8.0 + width) to z } +
            stations.reversed().map { (z, width) -> (8.0 - width) to z }

    private fun hmsDevastationCircle(
        x: Double,
        z: Double,
        radius: Double,
    ): List<Pair<Double, Double>> =
        (0 until 24).map { index ->
            val angle = index * PI / 12
            (x + radius * cos(angle)) to (z + radius * sin(angle))
        }

    val all: List<Boat> = listOf(dolly, pillnitz, hmsDevastation)

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
}
