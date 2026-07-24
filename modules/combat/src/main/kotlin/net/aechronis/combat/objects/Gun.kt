package net.aechronis.combat.objects

import net.aechronis.combat.Combat
import net.aechronis.combat.constants.Tags
import net.aechronis.combat.utils.CombatDamageKind
import net.aechronis.combat.utils.Message
import net.aechronis.combat.utils.Particles
import net.aechronis.combat.utils.Ray
import net.aechronis.combat.utils.withCombatAttribution
import net.kyori.adventure.key.Key
import net.kyori.adventure.sound.Sound
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.ShadowColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.title.Title
import net.minestom.server.MinecraftServer
import net.minestom.server.component.DataComponents
import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.Entity
import net.minestom.server.entity.LivingEntity
import net.minestom.server.entity.Player
import net.minestom.server.entity.RelativeFlags
import net.minestom.server.entity.damage.Damage
import net.minestom.server.instance.Instance
import net.minestom.server.item.Material
import net.minestom.server.network.packet.server.play.PlayerPositionAndLookPacket
import net.minestom.server.particle.Particle
import net.minestom.server.timer.TaskSchedule
import kotlin.math.roundToInt
import kotlin.random.Random

class Gun(
    name: String,
    itemName: Component,
    itemLore: List<Component> = emptyList(),
    itemModel: String = "${Tags.NAMESPACE}:$name",
    val ammo: Ammo,
    val maxAmmo: Int,
    val damage: Float,
    val automatic: Boolean,
    val sniper: Boolean,
    val cooldown: Long,
    val reloadTime: Long,
    val recoilMin: Float,
    val recoilMax: Float,
    val spreadMin: Float,
    val spreadMax: Float,
    val soundFire: Sound = Sound.sound(Key.key("${Tags.NAMESPACE}:$name.fire"), Sound.Source.PLAYER, 5f, 1f),
    val soundReload: Sound = Sound.sound(Key.key("${Tags.NAMESPACE}:$name.reload"), Sound.Source.PLAYER, 3f, 1f),
    val itemModelEmpty: String = "$itemModel-empty",
    val itemModelReloading: String = "$itemModel-reloading",
    val itemModelAiming: String = "$itemModel-aiming",
    val bulletTrailParticle: Particle? = null,
) : Item(
        name,
        itemName,
        itemLore,
        itemModel,
        Material.WARPED_FUNGUS_ON_A_STICK,
    ) {
    // ===============
    // AMMO FUNCTIONS
    // ===============
    // we use item damage for storing ammo, reasons being:
    // 1. player can see how much ammo a gun has without hovering over it
    // 2. when damage changes (e.g.) after firing/reloading the item swap animation doesn't show
    // like it would if we changed a tag, this making shooting allot smoother
    fun ammoText(ammo: Int): Component = Component.text(" [$ammo/$maxAmmo]").color(NamedTextColor.GRAY)

    fun ammoText(player: Player): Component = ammoText(getAmmo(player))

    fun setAmmo(
        player: Player,
        amount: Int,
    ) {
        player.itemInMainHand = player.itemInMainHand.with(DataComponents.DAMAGE, ammoToDamage(amount))
    }

    fun addAmmo(
        player: Player,
        amount: Int,
    ) {
        setAmmo(player, getAmmo(player) + amount)
    }

    fun getAmmo(player: Player): Int = damageToAmmo(player.itemInMainHand.get(DataComponents.DAMAGE) ?: 0)

    fun hasAmmo(player: Player): Boolean = getAmmo(player) > 0

    private fun damageToAmmo(damage: Int): Int = ((99 - damage) * maxAmmo.toDouble() / 98).roundToInt().coerceIn(0, maxAmmo)

    private fun ammoToDamage(amount: Int): Int = (99 - (amount * 98.0 / maxAmmo).roundToInt()).coerceIn(1, 99)

    // ================
    // RELOAD FUNCTIONS
    // ================
    fun reload(player: Player): Boolean {
        // check player has ammo
        if (ammo.get(player) == 0) {
            player.showTitle(
                Title.title(
                    Component.empty(),
                    Component.text("✕").color(TextColor.color(0.5F, 0F, 0F)).shadowColor(ShadowColor.none()),
                    0,
                    10,
                    10,
                ),
            )
            return false
        }

        if (Combat.reloadTasks[player] != null) return false // already reloading

        // create task
        runReloadTask(player)

        // play sound
        player.instance.playSound(soundReload, player.position.x, player.position.y, player.position.z)

        return true
    }

    private fun runReloadTask(player: Player) {
        var time = reloadTime

        Combat.reloadTasks[player] =
            MinecraftServer
                .getSchedulerManager()
                .buildTask {
                    time -= 100
                    val progress: Double = 1.0 - (time.toDouble() / reloadTime.toDouble())

                    // cancel reload if player changes item their holding
                    // or has no ammo in inventory
                    if (player.itemInMainHand.getTag(Tags.name) != name || ammo.get(player) == 0) {
                        player.showTitle(
                            Title.title(
                                Component.empty(),
                                Component.text("✕").color(TextColor.color(0.5F, 0F, 0F)).shadowColor(ShadowColor.none()),
                                0,
                                2,
                                10,
                            ),
                        )
                        Combat.reloadTasks[player]?.cancel()
                        Combat.reloadTasks.remove(player)
                        return@buildTask
                    }

                    // successful reload
                    if (time <= 0) {
                        setAmmo(player, maxAmmo)
                        ammo[player] -= 1

                        Combat.reloadTasks[player]!!.cancel()
                        Combat.reloadTasks.remove(player)
                    } else {
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
                }.delay(TaskSchedule.millis(100))
                .repeat(TaskSchedule.millis(100))
                .schedule()
    }

    // ==============
    // FIRE FUNCTIONS
    // ==============
    fun fire(
        player: Player,
        firePos: Pos? = null,
        ignoreCooldown: Boolean = false,
        ignoreAmmo: Boolean = false,
    ) {
        val now = System.currentTimeMillis()
        if (now - (Combat.playerLastActionTimes[player] ?: 0L) < cooldown && !ignoreCooldown) return
        if (Combat.reloadTasks[player] != null) return
        Combat.playerLastActionTimes[player] = now
        if (!hasAmmo(player) && !ignoreAmmo) return

        // calculate position to fire bullet (ray) from
        val speed = Combat.playerSpeeds[player] ?: 0F
        val offsetYaw = (firePos?.yaw ?: player.position.yaw) + spread(speed)
        val offsetPitch = (firePos?.pitch ?: player.position.pitch) + spread(speed)

        val offsetPos =
            if (firePos != null) {
                firePos.withView(offsetYaw, offsetPitch)
            } else {
                player.position
                    .withView(offsetYaw, offsetPitch)
                    .add(0.0, player.eyeHeight, 0.0)
            }

        // play fire sound
        player.instance.playSound(soundFire, offsetPos.x, offsetPos.y, offsetPos.z)

        // create ray with random offsets generated
        val ray = Ray(offsetPos, offsetPos.direction().mul(player.instance.viewDistance() * 16.0))

        val blockHit = ray.firstBlock(player.instance!!)
        val entityHit =
            ray.firstEntity(
                player.instance.entities
                    .filterIsInstance<LivingEntity>()
                    .filter { it != player },
            )
        val vehicleHit = checkVehicleHit(player.instance, offsetPos, offsetPos.direction(), ray.distance)

        val blockHitDistance = blockHit?.t ?: 999.9
        val entityHitDistance = entityHit?.t ?: 999.9
        val vehicleHitDistance = vehicleHit?.first ?: 999.9

        // determine which is hit first
        val trailEndPoint: Pos
        if (blockHitDistance == 999.9 && entityHitDistance == 999.9 && vehicleHitDistance == 999.9) { // no hit
            trailEndPoint = offsetPos.add(ray.direction.mul(ray.distance))
        } else if (vehicleHitDistance < blockHitDistance && vehicleHitDistance < entityHitDistance) { // vehicle hit
            val vehicleEntity = vehicleHit!!.second
            val vehicle = vehicleHit.third

            // ding sound
            player.playSound(Sound.sound(Key.key("entity.experience_orb.pickup"), Sound.Source.PLAYER, 1.0f, 1.0f))

            // dust particle
            val hitPoint = offsetPos.add(offsetPos.direction().mul(vehicleHitDistance))
            Particles.dustParticle(player.instance, hitPoint)

            vehicle.takeDamage(vehicleEntity, damage, player, itemName)
            trailEndPoint = hitPoint
        } else if (blockHitDistance > entityHitDistance) { // entity hit
            val target = entityHit!!.obj

            // ding sound
            player.playSound(Sound.sound(Key.key("entity.experience_orb.pickup"), Sound.Source.PLAYER, 1.0f, 1.0f))

            // blood
            Particles.bloodParticle(player.instance, entityHit.point.asPos())

            val damageSource =
                Damage
                    .fromProjectile(player, null, damage)
                    .withCombatAttribution(CombatDamageKind.PROJECTILE, itemName)
            Combat.applyDamage(target, damageSource)
            trailEndPoint = entityHit.point.asPos()
        } else { // block hit
            Particles.dustParticle(player.instance, blockHit!!.point.asPos())
            trailEndPoint = blockHit.point.asPos()
        }

        // draw bullet trail particle if set
        if (bulletTrailParticle != null) {
            Particles.particleLine(player.instance, bulletTrailParticle, offsetPos, trailEndPoint)
        }

        // send recoil packet to player
        recoil(player)

        // decrement ammo
        addAmmo(player, -1)

        return
    }

    fun spread(speed: Float): Float {
        val max = spreadMin + speed / 7 * (spreadMax - spreadMin)
        return Random.nextFloat() * max * 2 - max
    }

    fun recoil(player: Player) {
        player.sendPacket(
            PlayerPositionAndLookPacket(
                -1,
                Pos.ZERO,
                Pos.ZERO,
                0F,
                -(Random.nextFloat() * (recoilMax - recoilMin) + recoilMin),
                RelativeFlags.VIEW or RelativeFlags.COORD or RelativeFlags.DELTA_COORD,
            ),
        )
    }

    private fun checkVehicleHit(
        instance: Instance,
        origin: Pos,
        direction: Vec,
        maxDistance: Double,
    ): Triple<Double, Entity, Vehicle>? {
        val step = 0.25
        var distance = 0.0

        while (distance <= maxDistance) {
            val checkPoint =
                Vec(
                    origin.x + direction.x * distance,
                    origin.y + direction.y * distance,
                    origin.z + direction.z * distance,
                )

            for ((entity, vehicle) in Vehicle.entityVehicle) {
                if (entity.instance != instance) continue
                val vehiclePos = entity.position

                // for planes get the roll; for other vehicles just use 0
                val roll = if (vehicle is Plane) Plane.playerRoll.values.firstOrNull() ?: 0f else 0f

                val hitPart =
                    vehicle.hitbox.containsPoint(
                        checkPoint,
                        vehiclePos,
                        vehiclePos.yaw,
                        vehiclePos.pitch,
                        roll,
                    )

                if (hitPart != null) {
                    return Triple(distance, entity, vehicle)
                }
            }

            distance += step
        }
        return null
    }
}
