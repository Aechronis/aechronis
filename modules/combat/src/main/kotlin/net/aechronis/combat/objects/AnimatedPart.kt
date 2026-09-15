package net.aechronis.combat.objects

import net.aechronis.combat.utils.rotatePoint
import net.aechronis.combat.utils.rotatePointInverse
import net.aechronis.combat.utils.setRoll
import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.Entity
import net.minestom.server.entity.EntityType
import net.minestom.server.entity.Player
import net.minestom.server.entity.metadata.display.ItemDisplayMeta
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A display model centered on its pivot. Offsets and translations use vehicle-local
 * world blocks, independently of model scale; rotation angles use radians.
 * Definitions can be shared: the previous pose is stored separately for each spawned part.
 * Motion callbacks should keep mutable animation state in the returned [Pose], not a closure.
 */
class AnimatedPart(
    val model: String,
    val offset: Vec = Vec.ZERO,
    val motion: Motion,
    val initialPose: Pose = Pose(),
) {
    data class Pose(
        val translation: Vec = Vec.ZERO,
        val axis: Vec = Vec(1.0, 0.0, 0.0),
        val angle: Double = 0.0,
    ) {
        init {
            require(translation.isFinite() && axis.isFinite() && axis.lengthSquared() > 0.0 && axis.lengthSquared().isFinite())
            require(angle.isFinite()) { "Animated part angle must be finite" }
        }
    }

    /** One simulation tick, sampled after vehicle movement, including when unoccupied. */
    data class Context(
        val vehicle: Vehicle,
        val entity: Entity,
        val driver: Player?,
        val previousPosition: Pos,
        val position: Pos,
        val roll: Float,
        val ageTicks: Long,
    )

    fun interface Motion {
        fun update(
            context: Context,
            previous: Pose,
        ): Pose
    }

    init {
        require(model.isNotBlank()) { "Animated part model must not be blank" }
        require(offset.isFinite()) { "Animated part offset must be finite" }
    }

    companion object {
        /** Rolls around the local X axle from actual signed travel, including differential steering. */
        fun rollingWheel(
            model: String,
            offset: Vec,
            radius: Double,
        ): AnimatedPart {
            require(radius.isFinite() && radius > 0.0) { "Wheel radius must be positive and finite" }
            return AnimatedPart(
                model,
                offset,
                Motion { context, previous ->
                    val movement = context.position.asVec().sub(context.previousPosition)
                    val local = rotatePointInverse(movement, context.position.yaw, context.position.pitch, context.roll)
                    val yawDelta = ((context.position.yaw - context.previousPosition.yaw + 540f) % 360f + 360f) % 360f - 180f
                    val distance = local.z + Math.toRadians(yawDelta.toDouble()) * offset.x
                    // A 4-pi period preserves quaternion signs through a full revolution.
                    previous.copy(angle = (previous.angle + distance / radius) % (4.0 * PI))
                },
            )
        }

        /** Constant rotation; use a custom Motion to condition it on a driver or other state. */
        fun spinning(
            radiansPerTick: Double,
            axis: Vec = Vec(0.0, 0.0, 1.0),
        ): Motion {
            require(radiansPerTick.isFinite())
            Pose(axis = axis)
            return Motion { _, previous ->
                previous.copy(axis = axis, angle = (previous.angle + radiansPerTick) % (4.0 * PI))
            }
        }

        private fun Vec.isFinite(): Boolean = x.isFinite() && y.isFinite() && z.isFinite()
    }
}

/** Display and animation state owned by a single VehicleRuntime. */
internal class AnimatedPartRuntime(
    private val part: AnimatedPart,
    private val owner: VehicleRuntime,
) {
    private val display = Entity(EntityType.ITEM_DISPLAY)
    private var pose = part.initialPose

    init {
        val body = owner.entity
        val roll = owner.vehicle.hitboxRoll(body)
        display.setInstance(requireNotNull(body.instance), position(body.position, roll))
        val meta = display.entityMeta as ItemDisplayMeta
        meta.itemStack = ItemStack.of(Material.BONE).withItemModel(part.model)
        meta.posRotInterpolationDuration = 3
        meta.transformationInterpolationDuration = 1
        meta.scale = Vec(owner.vehicle.scale)
        meta.isHasNoGravity = true
        applyRotation(meta, roll)
        display.spawn()
    }

    fun update(context: AnimatedPart.Context) {
        val previous = pose
        pose = part.motion.update(context, previous)
        val target = position(context.position, context.roll)
        val instance = owner.entity.instance ?: return
        if (display.instance !== instance) {
            display.setInstance(instance, target)
        } else if (display.position != target) {
            display.teleport(target)
        }
        val meta = display.entityMeta as ItemDisplayMeta
        val roll = setRoll(Math.toRadians(context.roll.toDouble()).toFloat())
        if (pose != previous || !meta.leftRotation.contentEquals(roll)) {
            meta.setNotifyAboutChanges(false)
            try {
                applyRotation(meta, context.roll)
                meta.transformationInterpolationStartDelta = 0
            } finally {
                meta.setNotifyAboutChanges(true)
            }
        }
    }

    private fun position(
        body: Pos,
        roll: Float,
    ): Pos = body.add(rotatePoint(part.offset.add(pose.translation), body.yaw, body.pitch, roll))

    private fun applyRotation(
        meta: ItemDisplayMeta,
        roll: Float,
    ) {
        meta.leftRotation = setRoll(Math.toRadians(roll.toDouble()).toFloat())
        val axis = pose.axis.normalize()
        val sine = sin(pose.angle / 2.0)
        meta.rightRotation =
            floatArrayOf(
                (axis.x * sine).toFloat(),
                (axis.y * sine).toFloat(),
                (axis.z * sine).toFloat(),
                cos(pose.angle / 2.0).toFloat(),
            )
    }

    fun remove() = display.remove()
}
