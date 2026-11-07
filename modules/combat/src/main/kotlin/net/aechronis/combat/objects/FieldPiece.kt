package net.aechronis.combat.objects

import net.aechronis.combat.constants.Tags
import net.aechronis.combat.listeners.KeyPressListener
import net.aechronis.combat.utils.rotatePoint
import net.kyori.adventure.text.Component
import net.minestom.server.coordinate.Point
import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.Entity
import net.minestom.server.entity.EntityType
import net.minestom.server.entity.Player
import net.minestom.server.instance.Instance
import kotlin.math.floor

/**
 * A carriage controlled while standing behind it.
 * Move forward/back to push/pull, left/right to steer, hold right-click to aim from the sight,
 * and sneak to release control. Weapon behavior and the sight are supplied by subclasses.
 */
open class FieldPiece(
    name: String,
    itemName: Component,
    itemLore: List<Component> = emptyList(),
    itemModel: String = "${Tags.NAMESPACE}:$name",
    model: String = "${Tags.NAMESPACE}:$name",
    scale: Double,
    hitbox: Hitbox,
    health: Health?,
    placeTime: Long = 1000,
    seats: List<VehicleSeat> =
        listOf(
            VehicleSeat(
                "operator",
                "Artillery operator",
                VehicleSeatRole.OPERATOR,
                Vec(
                    0.0,
                    -hitbox.getGroundOffset(),
                    (hitbox.parts.minOfOrNull { it.offset.z - it.size.z } ?: 0.0) - 0.5,
                ),
                standing = true,
            ),
        ),
    invisibleWhileRiding: Boolean = false,
    invulnerableWhileRiding: Boolean = false,
    val moveSpeed: Double = 0.04,
    val turnSpeed: Float = 1f,
    animatedParts: List<AnimatedPart> = emptyList(),
    collisionHitbox: ShulkerHitbox = ShulkerHitbox.fromHitbox(hitbox),
) : Vehicle(
        name = name,
        itemName = itemName,
        itemLore = itemLore,
        itemModel = itemModel,
        model = model,
        scale = scale,
        hitbox = hitbox,
        health = health,
        placeTime = placeTime,
        seats = seats,
        invisibleWhileRiding = invisibleWhileRiding,
        invulnerableWhileRiding = invulnerableWhileRiding,
        animatedParts = animatedParts,
        collisionHitbox = collisionHitbox,
    ) {
    init {
        require(moveSpeed.isFinite() && moveSpeed > 0.0 && moveSpeed <= 0.1) {
            "FieldPiece moveSpeed must be in (0, 0.1] blocks per tick"
        }
        require(turnSpeed.isFinite() && turnSpeed > 0f && turnSpeed <= 3f) {
            "FieldPiece turnSpeed must be in (0, 3] degrees per tick"
        }
    }

    private val aimInputs = HashMap<Player, Long>()
    private val aimBlockedUntil = HashMap<Player, Long>()

    override fun onEnter(
        player: Player,
        entity: Entity,
    ) {
        super.onEnter(player, entity)
        // The right-click that mounted the carriage is usually still held. It must not start aiming.
        if (driverEntity(player) === entity) aimBlockedUntil[player] = System.currentTimeMillis() + MOUNT_AIM_COOLDOWN_MILLIS
    }

    override fun onUseInput(player: Player) {
        if (driverEntity(player) == null) return
        val now = System.currentTimeMillis()
        val blockedUntil = aimBlockedUntil[player]
        if (blockedUntil != null) {
            if (now < blockedUntil) {
                // Repeats mean the button is still held; aim only after it has been released.
                aimBlockedUntil[player] = maxOf(blockedUntil, now + AIM_HOLD_MILLIS)
                return
            }
            aimBlockedUntil.remove(player)
        }
        aimInputs[player] = now
    }

    /** Held right-click repeats every four ticks; allow a couple of ticks of latency between repeats. */
    protected fun isAiming(player: Player): Boolean {
        val last = aimInputs[player] ?: return false
        return System.currentTimeMillis() - last <= AIM_HOLD_MILLIS
    }

    override fun cameraEye(
        player: Player,
        entity: Entity,
    ): Point? = if (isAiming(player)) sightEye(entity) else null

    override fun hidesCrosshair(player: Player): Boolean = isAiming(player) && driverEntity(player)?.let(::sightEye) != null

    /** The sight's eye position for this carriage's current barrel, or null when it has none. */
    protected open fun sightEye(entity: Entity): Point? = null

    override fun onExit(player: Player) {
        aimInputs.remove(player)
        aimBlockedUntil.remove(player)
        super.onExit(player)
    }

    override fun onTick(player: Player) {
        if (KeyPressListener.playerInputEvent[player]?.isHoldingShiftKey == true) {
            onExit(player)
            return
        }
        driverEntity(player)?.let { move(player, it) }
        super.onTick(player)
    }

    private fun move(
        player: Player,
        entity: Entity,
    ) {
        val input = KeyPressListener.playerInputEvent[player] ?: return
        val forward = (if (input.isHoldingForwardKey) 1 else 0) - (if (input.isHoldingBackwardKey) 1 else 0)
        val turn = (if (input.isHoldingRightKey) 1 else 0) - (if (input.isHoldingLeftKey) 1 else 0)
        if (forward == 0 && turn == 0) return
        val instance = entity.instance ?: return
        val position = entity.position
        val yaw = position.yaw + turn * turnSpeed
        val movement = rotatePoint(Vec(0.0, 0.0, forward * moveSpeed), yaw, 0f, 0f)
        val candidate = position.add(movement).withView(yaw, 0f)
        val operator = candidate.add(rotatePoint(seats.first { it.role.drives }.offset, yaw, 0f, 0f))
        // The operator's own hitbox is empty while their stand-in occupies the station.
        val operatorBox =
            Hitbox(
                listOf(
                    HitboxPart(
                        Vec(0.0, EntityType.PLAYER.height() / 2, 0.0),
                        Vec(EntityType.PLAYER.width() / 2, EntityType.PLAYER.height() / 2, EntityType.PLAYER.width() / 2),
                    ),
                ),
            )
        // Both the carriage and operator need solid footing; do not roll over a ledge.
        val corners = hitbox.getWorldCorners(candidate, yaw, 0f, 0f).flatten()
        val groundY = candidate.y - hitbox.getGroundOffset()
        if (corners.any { !hasFooting(instance, it.x, groundY, it.z) }) return
        if (!hasFooting(instance, operator.x, operator.y, operator.z)) return
        if (blocked(instance, hitbox, candidate) || blocked(instance, operatorBox, operator.withYaw(0f))) return
        if (VehicleRegistry.all().any { runtime ->
                runtime.entity !== entity &&
                    runtime.entity.instance === instance &&
                    (overlaps(hitbox, candidate, runtime) || overlaps(operatorBox, operator.withYaw(0f), runtime))
            }
        ) {
            return
        }
        entity.teleport(candidate)
    }

    private fun hasFooting(
        instance: Instance,
        x: Double,
        y: Double,
        z: Double,
    ): Boolean {
        val point = Pos(x, y - 0.01, z)
        return instance.isChunkLoaded(point) && instance.getBlock(point).solid()
    }

    private fun overlaps(
        box: Hitbox,
        position: Pos,
        other: VehicleRuntime,
    ): Boolean =
        box.intersects(
            other.vehicle.hitbox,
            position,
            position.yaw,
            0f,
            0f,
            other.entity.position,
            other.entity.position.yaw,
            other.entity.position.pitch,
            other.vehicle.hitboxRoll(other.entity),
        )

    private fun blocked(
        instance: Instance,
        box: Hitbox,
        position: Pos,
    ): Boolean {
        val corners = box.getWorldCorners(position, position.yaw, 0f, 0f).flatten()
        if (corners.isEmpty()) return false
        val blockBox = Hitbox(listOf(HitboxPart(Vec.ZERO, Vec(0.499))))
        for (x in floor(corners.minOf { it.x }).toInt()..floor(corners.maxOf { it.x }).toInt()) {
            for (y in floor(corners.minOf { it.y }).toInt()..floor(corners.maxOf { it.y }).toInt()) {
                for (z in floor(corners.minOf { it.z }).toInt()..floor(corners.maxOf { it.z }).toInt()) {
                    val blockPos = Pos(x + 0.5, y + 0.5, z + 0.5)
                    if (!instance.isChunkLoaded(blockPos)) return true
                    if (instance.getBlock(x, y, z).solid() &&
                        box.intersects(blockBox, position, position.yaw, 0f, 0f, blockPos, 0f, 0f, 0f)
                    ) {
                        return true
                    }
                }
            }
        }
        return false
    }

    private companion object {
        const val AIM_HOLD_MILLIS = 300L
        const val MOUNT_AIM_COOLDOWN_MILLIS = 500L
    }
}
