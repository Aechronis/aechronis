package net.aechronis.server.constants

import net.aechronis.combat.objects.CollisionBuilder
import net.aechronis.combat.objects.ShulkerHitbox
import net.minestom.server.coordinate.Vec
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

object Boats {
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

                // Passenger benches leave the middle aisle and the gaps between rows open.
                val benchRows = (0..4).map { -5.35 + it * 2.05 } + (0..5).map { 15.9 + it * 1.95 }
                for ((minX, maxX) in listOf(6.34 to 7.08, 8.92 to 9.66)) {
                    for (z in benchRows) {
                        surface(minX, maxX, z, z + 0.55, 1.65, 0.5)
                        rail(Vec(minX, 2.0, z + 0.515), Vec(maxX, 2.0, z + 0.515), 0.18)
                    }
                }
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
