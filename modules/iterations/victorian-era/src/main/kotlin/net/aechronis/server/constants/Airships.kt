package net.aechronis.server.constants

import net.aechronis.combat.objects.AmmoTypes
import net.aechronis.combat.objects.CollisionBuilder
import net.aechronis.combat.objects.Health
import net.aechronis.combat.objects.Hitbox
import net.aechronis.combat.objects.HitboxPart
import net.aechronis.combat.objects.VehicleSeat
import net.aechronis.combat.objects.VehicleSeatRole
import net.aechronis.server.objects.Airship
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.minestom.server.coordinate.Vec
import kotlin.math.cos
import kotlin.math.sin

object Airships {
    // Extents below come from the rotated model geometry, not the unrotated element bounds.
    // Scale = metres * 16 / raw extent; Y follows X, so the envelope's section stays circular.

    // Dupuy de Lôme's hand-cranked 1872 airship: 36.12 m long and 14.84 m across, at about 10 km/h.
    // https://en.wikipedia.org/wiki/Henri_Dupuy_de_L%C3%B4me
    // Raw diameter/length: 17.904 / 43.526.
    private val dupuyDeLomeScale = Vec(13.261840, 13.261840, 13.277581)

    // Zeppelin's first rigid airship, 1900: 128 m long and 11.73 m across, at about 27 km/h.
    // https://en.wikipedia.org/wiki/Zeppelin_LZ_1
    // Raw diameter/length: 4.014 / 43.822.
    private val lz1Scale = Vec(46.756353, 46.756353, 46.734517)

    // Model-space floor heights: the Dupuy de Lôme's car planks and LZ 1's gondola soles.
    private const val DUPUY_DE_LOME_FLOOR = -7.69
    private const val LZ1_FLOOR = 4.78

    private val health =
        mapOf(
            AmmoTypes.NORMAL to 2F,
            AmmoTypes.EXPLOSIVE to 50F,
            AmmoTypes.BOMB to 100F,
            AmmoTypes.MISSILE to 100F,
        )

    val dupuyDeLome =
        Airship(
            name = "dupuy-de-lome",
            itemName = Component.text("Dupuy de Lôme", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
            itemLore =
                listOf(
                    lore("Hand-cranked French airship · 2 crew seats"),
                    lore("Helmsman sets the throttle and steers"),
                ),
            model = "aechronis:dupuy-de-lome-body",
            scale = dupuyDeLomeScale.z,
            modelScale = dupuyDeLomeScale,
            // Eight men cranked the two-bladed screw, nearly 30 feet across, about 28 times a minute.
            // https://ui.production.heritage.cvt.dk/book/b6c06313-ea66-44ea-8a30-0744ab526135?page=65
            // The rudder's leading edge is raked 11.3 degrees aft from vertical; mirrored Z makes that -0.2 here.
            animatedParts =
                listOf(
                    Airship.propeller("aechronis:dupuy-de-lome-propeller", point(dupuyDeLomeScale, 8.0, -6.556, 15.958), 28.0),
                    Airship.rudder("aechronis:dupuy-de-lome-rudder", point(dupuyDeLomeScale, 8.0, 12.05, 22.45), Vec(0.0, 1.0, -0.2)),
                ),
            // The 9 m propeller hangs below the car, so a landed airship rests on its blades.
            // Rigging and the bamboo booms do not obstruct.
            hitbox =
                Hitbox(
                    listOf(
                        box(dupuyDeLomeScale, 5.93, -7.93, 3.94, 10.07, -6.29, 12.05),
                        box(dupuyDeLomeScale, 5.74, -11.1, 15.66, 10.26, -2.01, 16.26),
                        box(dupuyDeLomeScale, 7.92, 8.96, 21.8, 8.08, 17.91, 29.1),
                    ) +
                        listOf(
                            Triple(-13.77, -9.0, 3.43),
                            Triple(-9.0, -3.0, 6.44),
                            Triple(-3.0, 3.0, 8.48),
                            Triple(3.0, 13.0, 8.95),
                            Triple(13.0, 19.0, 8.48),
                            Triple(19.0, 25.0, 6.44),
                            Triple(25.0, 29.77, 3.43),
                        ).flatMap { (minZ, maxZ, radius) -> envelope(dupuyDeLomeScale, 18.072, minZ, maxZ, radius) },
                ),
            health = Health(250F, health),
            maxSpeed = 0.139,
            turnSpeed = 1.0f,
            // Both sit on the forward cranking seats: the elevatorman by the ballonet blower,
            // the helmsman by the compass, where the rudder lines reach the car.
            seat = crewSeat(dupuyDeLomeScale, "elevatorman", "Elevatorman", VehicleSeatRole.DRIVER, 9.19, DUPUY_DE_LOME_FLOOR, 5.6),
            helmSeat = crewSeat(dupuyDeLomeScale, "helmsman", "Helmsman", VehicleSeatRole.CREW, 6.81, DUPUY_DE_LOME_FLOOR, 5.6),
            collisionHitbox =
                CollisionBuilder(dupuyDeLomeScale)
                    .apply {
                        car(dupuyDeLomeScale, DUPUY_DE_LOME_FLOOR, Floor(6.12, 9.88, 4.08, 11.92), Floor(6.0, 10.0, 4.0, 12.0))
                    }.build(),
        )

    val lz1 =
        Airship(
            name = "lz1",
            itemName = Component.text("LZ 1", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
            itemLore =
                listOf(
                    lore("Count Zeppelin's first rigid airship · 2 crew seats"),
                    lore("Helmsman sets the throttle and steers"),
                ),
            model = "aechronis:lz1-body",
            scale = lz1Scale.z,
            modelScale = lz1Scale,
            // Each engine drove a four-bladed propeller on either side of the hull. Their speed is not
            // recorded; 300 rpm stays below 45 degrees a tick, past which four blades would seem to turn backward.
            // Steering sails above and below the bow and on either side of the stern, each hinged at its forward upright.
            animatedParts =
                listOf(-4.34, 20.305).flatMap { z ->
                    listOf(5.912, 10.088).map { x -> Airship.propeller("aechronis:lz1-propeller", point(lz1Scale, x, 7.815, z), 300.0) }
                } +
                    listOf(11.02, 6.39).map { y ->
                        Airship.rudder("aechronis:lz1-rudder", point(lz1Scale, 8.0, y, -8.88), forward = true)
                    } +
                    listOf(5.99, 10.01).map { x -> Airship.rudder("aechronis:lz1-rudder", point(lz1Scale, x, 8.84, 23.3)) },
            // The gondola struts, propeller outriggers and steering sails do not obstruct.
            hitbox =
                Hitbox(
                    listOf(
                        box(lz1Scale, 7.62, 4.608, -5.46, 8.38, 5.24, -3.18),
                        box(lz1Scale, 7.62, 4.608, 19.18, 8.38, 5.24, 21.46),
                        box(lz1Scale, 7.83, 4.98, -11.0, 8.17, 5.11, 27.0),
                    ) +
                        listOf(
                            Triple(-13.912, -11.0, 1.64),
                            Triple(-11.0, 25.0, 2.01),
                            Triple(25.0, 29.912, 1.91),
                        ).flatMap { (minZ, maxZ, radius) -> envelope(lz1Scale, 8.739, minZ, maxZ, radius) },
                ),
            health = Health(700F, health),
            maxSpeed = 0.375,
            turnSpeed = 0.5f,
            // Each crew member sits at a gondola's forward tiller: the helmsman forward, the elevatorman aft.
            seat = crewSeat(lz1Scale, "elevatorman", "Elevatorman", VehicleSeatRole.DRIVER, 8.0, LZ1_FLOOR, 19.62),
            helmSeat = crewSeat(lz1Scale, "helmsman", "Helmsman", VehicleSeatRole.CREW, 8.0, LZ1_FLOOR, -5.0),
            collisionHitbox =
                CollisionBuilder(lz1Scale)
                    .apply {
                        for ((floorZ, railZ, engineZ) in listOf(
                            Triple(-5.37 to -3.28, -5.40 to -3.25, -4.87 to -3.83),
                            Triple(19.28 to 21.37, 19.25 to 21.44, 19.77 to 20.81),
                        )) {
                            car(
                                lz1Scale,
                                LZ1_FLOOR,
                                Floor(7.72, 8.28, floorZ.first, floorZ.second),
                                Floor(7.65, 8.35, railZ.first, railZ.second),
                            )
                            solid(7.85, LZ1_FLOOR, engineZ.first, 8.15, 5.24, engineZ.second)
                        }
                        // The walkway between and beyond the gondolas, a step up from their floors. Its
                        // cubes span its full 0.93 m width; any narrower would double them past the limit.
                        for ((minZ, maxZ) in listOf(-11.0 to -5.46, -3.18 to 19.18, 21.46 to 27.0)) {
                            surface(7.84, 8.16, minZ, maxZ, 5.11, 1.0)
                        }
                    }.build(),
        )

    val all: List<Airship> = listOf(dupuyDeLome, lz1)

    private data class Floor(
        val minX: Double,
        val maxX: Double,
        val minZ: Double,
        val maxZ: Double,
    )

    private fun lore(text: String) = Component.text(text, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)

    /** Seated a block above the floor like the Ballon Monté's elevatorman, stepping off 0.3 m above it. */
    private fun crewSeat(
        scale: Vec,
        id: String,
        name: String,
        role: VehicleSeatRole,
        x: Double,
        floor: Double,
        z: Double,
    ) = VehicleSeat(
        id,
        name,
        role,
        point(scale, x, floor, z).add(0.0, 1.0, 0.0),
        exitOffset = point(scale, x, floor, z).add(0.0, 0.3, 0.0),
    )

    /** A floor, and a rail on [rail]'s outline whose top stands 1 m above it, as in the Ballon Monté's basket. */
    private fun CollisionBuilder.car(
        scale: Vec,
        floorTop: Double,
        floor: Floor,
        rail: Floor,
    ) {
        surface(floor.minX, floor.maxX, floor.minZ, floor.maxZ, floorTop, 0.6)
        // The default 0.3 m rail's centre sits 0.85 m above the floor.
        val railY = floorTop + 0.85 * 16 / scale.y
        val corners = listOf(rail.minX to rail.minZ, rail.maxX to rail.minZ, rail.maxX to rail.maxZ, rail.minX to rail.maxZ)
        (corners + corners.first()).zipWithNext { (fromX, fromZ), (toX, toZ) ->
            rail(Vec(fromX, railY, fromZ), Vec(toX, railY, toZ))
        }
    }

    // Item displays turn the model's -Z bow toward vehicle-local +Z and mirror X; the model center is (8, 8, 8).
    private fun point(
        scale: Vec,
        x: Double,
        y: Double,
        z: Double,
    ): Vec = Vec((8.0 - x) * scale.x / 16, (y - 8.0) * scale.y / 16, (8.0 - z) * scale.z / 16)

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
            Vec(maxX - minX, maxY - minY, maxZ - minZ).mul(scale).div(32.0),
        )

    /** Two crossed boxes whose corners lie on the envelope's circular section around (8, [centreY]). */
    private fun envelope(
        scale: Vec,
        centreY: Double,
        minZ: Double,
        maxZ: Double,
        radius: Double,
    ): List<HitboxPart> {
        val long = radius * cos(Math.toRadians(30.0))
        val short = radius * sin(Math.toRadians(30.0))
        return listOf(
            box(scale, 8.0 - long, centreY - short, minZ, 8.0 + long, centreY + short, maxZ),
            box(scale, 8.0 - short, centreY - long, minZ, 8.0 + short, centreY + long, maxZ),
        )
    }
}
