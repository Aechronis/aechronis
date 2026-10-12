package net.aechronis.server.constants

import net.aechronis.combat.objects.AmmoTypes
import net.aechronis.combat.objects.Health
import net.aechronis.combat.objects.HitboxPart
import net.aechronis.combat.objects.ShulkerHitboxPart
import net.aechronis.combat.objects.VehicleSeat
import net.aechronis.combat.objects.VehicleSeatRole
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.minestom.server.coordinate.Vec
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt

// Shared building blocks for the balloon, airship and zeppelin definitions.

/** Vehicle damage is flat per hit by ammo type; Victorian rifles do 44-50, so 47 on average. */
internal const val RIFLE_HIT = 47F

/** Item name in the gold, non-italic style used by every flying vehicle. */
internal fun vehicleTitle(name: String) = Component.text(name, NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false)

/** Health that dies to [rifleShots] rifle hits, [shells] artillery shells or [bombs] bombs/missiles. */
internal fun vehicleHealth(
    rifleShots: Int,
    shells: Int,
    bombs: Int,
): Health {
    val total = rifleShots * RIFLE_HIT
    return Health(
        total,
        mapOf(
            AmmoTypes.NORMAL to RIFLE_HIT,
            AmmoTypes.EXPLOSIVE to total / shells,
            AmmoTypes.BOMB to total / bombs,
            AmmoTypes.MISSILE to total / bombs,
        ),
    )
}

/** A seat for a gunner with a mounted Maxim; [id] is `starboard` or `port`. */
internal fun maximSeat(
    id: String,
    name: String,
    at: Vec,
) = VehicleSeat(id, name, VehicleSeatRole.GUNNER, at, weaponId = "maxim-$id")

/** A thin floor of shulker cubes covering the given rectangle, with its top surface at [top]. */
internal fun deckFloor(
    minX: Double,
    maxX: Double,
    minZ: Double,
    maxZ: Double,
    top: Double,
    cube: Double,
): List<ShulkerHitboxPart> {
    fun centers(
        min: Double,
        max: Double,
    ): List<Double> {
        val count = max(ceil((max - min) / cube).toInt(), 1)
        val step = if (count > 1) (max - min - cube) / (count - 1) else 0.0
        return List(count) { min + cube / 2 + it * step }
    }
    return centers(minX, maxX).flatMap { x -> centers(minZ, maxZ).map { z -> ShulkerHitboxPart(Vec(x, top - cube / 2, z), cube) } }
}

/** A waist-high wall of shulker cubes around the edge of a square deck, [height] tall above [floorTop]. */
internal fun railing(
    halfWidth: Double,
    floorTop: Double,
    cube: Double,
    height: Double,
): List<ShulkerHitboxPart> {
    val ring = List(ceil(2 * halfWidth / cube).toInt()) { -halfWidth + cube / 2 + it * cube }
    val edge = halfWidth - cube / 2
    val cells = ring.flatMap { a -> ring.filter { b -> abs(a) == edge || abs(b) == edge }.map { b -> a to b } }
    return (0 until ceil(height / cube).toInt()).flatMap { layer ->
        cells.map { (x, z) -> ShulkerHitboxPart(Vec(x, floorTop + cube / 2 + layer * cube, z), cube) }
    }
}

/**
 * A cigar-shaped hitbox: [slices] boxes laid end to end along z, each as wide and tall as an
 * ellipsoid with the given [half] extents is at the widest edge of that slice.
 */
internal fun ellipsoid(
    center: Vec,
    half: Vec,
    slices: Int,
): List<HitboxPart> =
    List(slices) { index ->
        val middle = (index + 0.5) / slices * 2 - 1
        val widestEdge = max(abs(index.toDouble() / slices * 2 - 1), abs((index + 1.0) / slices * 2 - 1))
        // 0.98 keeps the end slices from shrinking to nothing
        val taper = sqrt(1 - 0.98 * widestEdge * widestEdge)
        HitboxPart(
            offset = center.add(0.0, 0.0, middle * half.z()),
            size = Vec(half.x() * taper, half.y() * taper, half.z() / slices),
        )
    }
