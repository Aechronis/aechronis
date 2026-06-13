package net.aechronis.combat.objects

import net.aechronis.combat.constants.Tags
import net.aechronis.combat.listeners.KeyPressListener
import net.aechronis.combat.utils.Message
import net.aechronis.combat.utils.rotatePoint
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.ShadowColor
import net.kyori.adventure.title.Title
import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.Entity
import net.minestom.server.entity.EntityType
import net.minestom.server.entity.Player
import net.minestom.server.entity.metadata.display.ItemDisplayMeta
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import kotlin.math.max
import kotlin.math.min

class Tank(
    name: String,
    itemName: Component,
    itemLore: List<Component> = emptyList(),
    itemModel: String = "${Tags.NAMESPACE}:$name",
    model: String = "${Tags.NAMESPACE}:$name",
    val bodyModel: String = "$model-body",
    val turretModel: String = "$model-turret",
    val barrelModel: String = "$model-barrel",
    scale: Double,
    hitbox: Hitbox,
    health: Float = 200F,
    placeTime: Long = 1000,
    maxSpeed: Float = 0.2f,
    acceleration: Float = 0.01f,
    braking: Float = 0.02f,
    friction: Float = 0.95f,
    turnSpeed: Float = 2.0f,
    maxClimbHeight: Float = 1.2f,
    val turretTraverseSpeed: Float = 3f,
    val projectileModel: String = "$model-shell",
    val projectileSpeed: Double = 4.0,
    val projectileExplosionRadius: Int = 4,
    val projectileExplosionFire: Double = 0.1,
    val barrelTipOffset: Vec = Vec(0.0, 0.0, 5.0),
    val fireCooldown: Long = 20000,
    seatOffsets: List<Vec> = listOf(Vec.ZERO),
) : Car(
        name,
        itemName,
        itemLore,
        itemModel,
        bodyModel,
        scale,
        hitbox,
        health,
        placeTime,
        maxSpeed,
        acceleration,
        braking,
        friction,
        turnSpeed,
        maxClimbHeight,
        seatOffsets,
    ) {
    override fun spawn(
        player: Player,
        pos: Pos,
    ): Entity {
        // spawn the body via the normal vehicle spawn
        val body = super.spawn(player, pos)

        // spawn the turret as a second item display
        val turret = Entity(EntityType.ITEM_DISPLAY)
        turret.setInstance(body.instance, body.position)

        val turretMeta = turret.entityMeta as ItemDisplayMeta
        turretMeta.itemStack = ItemStack.of(Material.BONE).withItemModel(turretModel)
        turretMeta.posRotInterpolationDuration = 3
        turretMeta.scale = Vec(scale)
        turretMeta.isHasNoGravity = true

        turret.spawn()

        // spawn the barrel as a third item display
        val barrel = Entity(EntityType.ITEM_DISPLAY)
        barrel.setInstance(body.instance, turret.position)

        val barrelMeta = barrel.entityMeta as ItemDisplayMeta
        barrelMeta.itemStack = ItemStack.of(Material.BONE).withItemModel(barrelModel)
        barrelMeta.posRotInterpolationDuration = 3
        barrelMeta.scale = Vec(scale)
        barrelMeta.isHasNoGravity = true

        barrel.spawn()

        entityTurret[body] = turret
        entityBarrel[body] = barrel

        yaw[body] = body.position.yaw
        pitch[body] = 0f

        return body
    }

    override fun onTick(player: Player) {
        // body movement
        super.onTick(player)

        val entity = playerVehicleEntity[player] ?: return

        val turret = entityTurret[entity] ?: return
        val barrel = entityBarrel[entity] ?: return
        val pos = entity.position

        // target where the driver is looking
        val targetYaw = player.position.yaw
        val targetPitch = max(-25F, min(5F, player.position.pitch))

        // turret
        val currentYaw = yaw[entity] ?: pos.yaw
        val currentPitch = pitch[entity] ?: 0f
        val newYaw = approachAngle(currentYaw, targetYaw, turretTraverseSpeed)
        val newPitch = approachAngle(currentPitch, targetPitch, turretTraverseSpeed)
        yaw[entity] = newYaw
        pitch[entity] = newPitch

        turret.teleport(pos.withView(newYaw, 0f))

        // barrel
        barrel.teleport(pos.withView(newYaw, newPitch))

        // fire
        val inputEvent = KeyPressListener.playerInputEvent[player]
        if (inputEvent?.isHoldingJumpKey == true) {
            fire(entity, pos, newYaw, newPitch)
        }

        // progress bar
        val last = lastFireTime[entity]
        if (last != null) {
            val elapsed = System.currentTimeMillis() - last
            if (elapsed < fireCooldown) {
                val progress = (elapsed.toDouble() / fireCooldown.toDouble()).coerceIn(0.0, 1.0)
                player.showTitle(
                    Title.title(
                        Component.empty(),
                        Message.progressBar(progress).shadowColor(ShadowColor.none()),
                        0,
                        3,
                        10,
                    ),
                )
            }
        }
    }

    private fun fire(
        body: Entity,
        barrelPos: Pos,
        yaw: Float,
        pitch: Float,
    ) {
        val now = System.currentTimeMillis()
        val last = lastFireTime[body] ?: 0L
        if (now - last < fireCooldown) return

        val instance = body.instance ?: return

        val tip = rotatePoint(barrelTipOffset, yaw, pitch, 0f)
        val muzzle = barrelPos.add(tip.x, tip.y, tip.z)
        val direction = muzzle.withView(yaw, pitch).direction()

        Projectile(
            instance,
            muzzle,
            projectileModel,
            direction,
            projectileSpeed,
            projectileExplosionRadius,
            projectileExplosionFire,
        )

        lastFireTime[body] = now
    }

    override fun destroy(entity: Entity) {
        entityTurret.remove(entity)?.remove()
        entityBarrel.remove(entity)?.remove()
        yaw.remove(entity)
        pitch.remove(entity)
        lastFireTime.remove(entity)
        super.destroy(entity)
    }

    // steps [current] toward [target] by at most [maxStep] degrees, takes the shortest way around
    private fun approachAngle(
        current: Float,
        target: Float,
        maxStep: Float,
    ): Float {
        var delta = (target - current) % 360f
        if (delta > 180f) delta -= 360f
        if (delta < -180f) delta += 360f
        return when {
            delta > maxStep -> current + maxStep
            delta < -maxStep -> current - maxStep
            else -> current + delta
        }
    }

    companion object {
        val entityTurret = hashMapOf<Entity, Entity>()
        val entityBarrel = hashMapOf<Entity, Entity>()

        // yaw/pitch of the turret/barrel
        val yaw = hashMapOf<Entity, Float>()
        val pitch = hashMapOf<Entity, Float>()

        val lastFireTime = hashMapOf<Entity, Long>()
    }
}
