package net.aechronis.combat.objects

import net.aechronis.combat.Combat
import net.aechronis.combat.constants.Tags
import net.aechronis.combat.listeners.WeaponLoreListener
import net.aechronis.combat.tasks.BlockRestoreManager
import net.aechronis.combat.tasks.ModelManager
import net.aechronis.combat.utils.CombatDamageKind
import net.aechronis.combat.utils.GUN_FIRE_ANIMATION_TICKS
import net.aechronis.combat.utils.GUN_MINING_TOOL
import net.aechronis.combat.utils.GUN_SWING_ANIMATION
import net.aechronis.combat.utils.GUN_USE_COOLDOWN
import net.aechronis.combat.utils.GunAnimation
import net.aechronis.combat.utils.GunAnimationAction
import net.aechronis.combat.utils.LagCompensation
import net.aechronis.combat.utils.Message
import net.aechronis.combat.utils.Particles
import net.aechronis.combat.utils.Ray
import net.aechronis.combat.utils.gunClientTrail
import net.aechronis.combat.utils.prepareGunPacketBundle
import net.aechronis.combat.utils.withCombatAttribution
import net.aechronis.server.modules.ModuleScheduler
import net.kyori.adventure.key.Key
import net.kyori.adventure.sound.Sound
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.ShadowColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.title.Title
import net.minestom.server.MinecraftServer
import net.minestom.server.component.DataComponents
import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.Entity
import net.minestom.server.entity.EquipmentSlot
import net.minestom.server.entity.LivingEntity
import net.minestom.server.entity.MainHand
import net.minestom.server.entity.Player
import net.minestom.server.entity.RelativeFlags
import net.minestom.server.entity.damage.Damage
import net.minestom.server.instance.Instance
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import net.minestom.server.network.ConnectionState
import net.minestom.server.network.packet.server.SendablePacket
import net.minestom.server.network.packet.server.ServerPacket
import net.minestom.server.network.packet.server.play.EntityEquipmentPacket
import net.minestom.server.network.packet.server.play.PlayerPositionAndLookPacket
import net.minestom.server.network.packet.server.play.SetPlayerInventorySlotPacket
import net.minestom.server.network.player.PlayerSocketConnection
import net.minestom.server.particle.Particle
import net.minestom.server.timer.TaskSchedule
import java.math.BigDecimal
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
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
    val maxRange: Double = 128.0,
    val bulletTrailParticle: Particle? = null,
    val bulletTrailOffset: Vec = Vec.ZERO,
    /** Allocated by the Blockbench plugin and shared with the shader atlas. */
    val animatedViewModelProfile: Int = 0,
    /** Must match the saved model's fire_ticks (manual actions can outlast recoil). */
    val fireAnimationTicks: Int = GUN_FIRE_ANIMATION_TICKS,
) : Item(
        name,
        itemName,
        itemLore +
            gunStatsLore(
                ammo = ammo,
                maxAmmo = maxAmmo,
                damage = damage,
                automatic = automatic,
                sniper = sniper,
                cooldown = cooldown,
                reloadTime = reloadTime,
                recoilMin = recoilMin,
                recoilMax = recoilMax,
                spreadMin = spreadMin,
                spreadMax = spreadMax,
                maxRange = maxRange,
            ),
        itemModel,
        Material.WARPED_FUNGUS_ON_A_STICK,
    ) {
    init {
        require(cooldown > 0 && cooldown % 50L == 0L) { "Gun cooldown must be a positive multiple of 50 ms" }
        require(animatedViewModelProfile in 0..15) { "Gun animation profile must fit the four-bit protocol" }
        require(fireAnimationTicks in 1..255) { "Gun fire animation must last 1–255 ticks" }
        require(maxRange.isFinite() && maxRange > 0.0) { "Gun maxRange must be a positive finite number" }
    }

    override fun toItemStack(): ItemStack {
        val item = super.toItemStack().with(DataComponents.TOOL, GUN_MINING_TOOL)
        return item
            .withItemModel("$itemModel-equip")
            .with(DataComponents.SWING_ANIMATION, GUN_SWING_ANIMATION)
            .with(DataComponents.USE_COOLDOWN, GUN_USE_COOLDOWN)
    }

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

    fun getAmmo(player: Player): Int = getAmmo(player.itemInMainHand)

    internal fun getAmmo(item: ItemStack): Int = damageToAmmo(item.get(DataComponents.DAMAGE) ?: 0)

    fun hasAmmo(player: Player): Boolean = getAmmo(player) > 0

    fun toEmptyItemStack(): ItemStack {
        val item = toItemStack().with(DataComponents.DAMAGE, ammoToDamage(0))
        return item.withItemModel("$itemModelEmpty-equip")
    }

    private fun damageToAmmo(damage: Int): Int = ((99 - damage) * maxAmmo.toDouble() / 98).roundToInt().coerceIn(0, maxAmmo)

    private fun ammoToDamage(amount: Int): Int = (99 - (amount * 98.0 / maxAmmo).roundToInt()).coerceIn(1, 99)

    // ================
    // RELOAD FUNCTIONS
    // ================
    fun reload(player: Player): Boolean {
        if (!GunAnimation.canUse(player, this)) return false
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
        GunAnimation.start(player, this, GunAnimationAction.RELOAD, ((reloadTime + 49) / 50).coerceIn(1, 255).toInt())

        // play sound
        player.instance.playSound(soundReload, player.position.x, player.position.y, player.position.z)

        return true
    }

    private fun runReloadTask(player: Player) {
        var time = reloadTime
        val reloadSlot = player.heldSlot
        val reloadInstance = player.instance

        Combat.reloadTasks[player] =
            ModuleScheduler
                .buildTask {
                    time -= 100
                    val progress: Double = 1.0 - (time.toDouble() / reloadTime.toDouble())

                    // cancel reload if player changes item their holding
                    // or has no ammo in inventory
                    if (player.itemInMainHand.getTag(Tags.name) != name ||
                        ammo.get(player) == 0 ||
                        player.heldSlot != reloadSlot ||
                        player.instance !== reloadInstance ||
                        player.isDead ||
                        !player.isOnline
                    ) {
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
                        GunAnimation.clear(player)
                        return@buildTask
                    }

                    // successful reload
                    if (time <= 0) {
                        setAmmo(player, maxAmmo)
                        ammo[player] -= 1

                        Combat.reloadTasks[player]!!.cancel()
                        Combat.reloadTasks.remove(player)
                        GunAnimation.clear(player)
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
        lagCompensate: Boolean = firePos == null,
    ): Boolean {
        if (firePos == null && !GunAnimation.canUse(player, this)) return false
        val firedAtNanos = System.nanoTime()
        val now = System.currentTimeMillis()
        val lastAction = Combat.playerLastActionTimes[player] ?: 0L
        if (now - lastAction < cooldown && !ignoreCooldown) return false
        if (Combat.reloadTasks[player] != null) return false
        Combat.playerLastActionTimes[player] = now
        if (!hasAmmo(player) && !ignoreAmmo) return false

        // Calculate position to fire bullet (ray) from. ADS only affects handheld shots,
        // matching the state which displays the aiming animation.
        val speed = Combat.playerSpeeds[player] ?: 0F
        val aimingMultiplier = aimingMultiplier(firePos == null && Combat.playerAiming[player] == true)
        val offsetYaw = (firePos?.yaw ?: player.position.yaw) + spread(speed) * aimingMultiplier
        val offsetPitch = (firePos?.pitch ?: player.position.pitch) + spread(speed) * aimingMultiplier

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
        val ray = Ray(offsetPos, offsetPos.direction().mul(maxRange))

        val blockHit = ray.firstBlock(player.instance!!)
        val entityHit =
            if (lagCompensate) {
                LagCompensation.firstEntityHit(ray, player, player.instance, firedAtNanos)
            } else {
                ray.firstEntity(
                    player.instance.entities
                        .filterIsInstance<LivingEntity>()
                        .filter { it != player },
                )
            }
        val vehicleHit = checkVehicleHit(player.instance, offsetPos, offsetPos.direction(), ray.distance)

        val blockHitDistance = blockHit?.t ?: Double.POSITIVE_INFINITY
        val entityHitDistance = entityHit?.t ?: Double.POSITIVE_INFINITY
        val vehicleHitDistance = vehicleHit?.first ?: Double.POSITIVE_INFINITY

        // determine which is hit first
        val trailEndPoint: Pos
        if (blockHit == null && entityHit == null && vehicleHit == null) { // no hit
            trailEndPoint = offsetPos.add(ray.direction.mul(ray.distance))
        } else if (vehicleHitDistance < blockHitDistance && vehicleHitDistance < entityHitDistance) { // vehicle hit
            val vehicleEntity = vehicleHit!!.second
            val vehicle = vehicleHit.third

            // ding sound
            player.playSound(Sound.sound(Key.key("entity.experience_orb.pickup"), Sound.Source.PLAYER, 1.0f, 1.0f))

            // dust particle
            val hitPoint = offsetPos.add(offsetPos.direction().mul(vehicleHitDistance))
            Particles.dustParticle(player.instance, hitPoint)

            vehicle.takeDamage(vehicleEntity, ammo.ammoType, damage, player, itemName)
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
            Combat.applyDamageWithoutImmunity(target, damageSource)
            trailEndPoint = entityHit.point.asPos()
        } else { // block hit
            Particles.dustParticle(player.instance, blockHit!!.point.asPos())
            BlockRestoreManager.temporarilyBreakLeaf(
                player.instance,
                blockHit.point.asBlockVec(),
                blockHit.obj,
            )
            trailEndPoint = blockHit.point.asPos()
        }

        // Sample the authoritative trail once, retaining its existing range filtering.
        val trail =
            bulletTrailParticle?.let { particle ->
                val trailStart =
                    if (firePos == null) {
                        bulletTrailOrigin(
                            offsetPos,
                            player.settings.mainHand,
                            bulletTrailOffset,
                            Combat.playerAiming[player] == true,
                        )
                    } else {
                        offsetPos
                    }
                Particles.prepareLine(player.instance, particle, trailStart, trailEndPoint)
            }

        // Animated FIRE clips and their tracer share a fixed two-tick protocol;
        // the authoritative firing cooldown remains independent of that visual.
        val animation =
            if (firePos == null) {
                val finalItem =
                    WeaponLoreListener.refreshLore(
                        if (ignoreAmmo) {
                            player.itemInMainHand
                        } else {
                            player.itemInMainHand.with(
                                DataComponents.DAMAGE,
                                ammoToDamage(
                                    getAmmo(player) - 1,
                                ),
                            )
                        },
                    )
                GunAnimation.prepareFire(player, this, finalItem)
            } else {
                null
            }
        if (animation != null) {
            val instance = player.instance
            val clientTrail =
                gunClientTrail(this, animation.item, player.settings.mainHand, trailEndPoint, animation.worldAge)
            val worldTrail = if (clientTrail != null) trail?.copy(viewers = trail.viewers.filter { it !== player }) else trail
            val observers = player.viewers.filterTo(HashSet()) { it.isOnline && it.instance === instance && it !== player }
            val trailViewers = worldTrail?.viewers?.toSet() ?: emptySet()
            val recipients = observers + trailViewers + player
            val kick = recoilPacket(aimingMultiplier)
            var published = false
            try {
                // Frame every complete bundle before publishing any of them. The
                // subsequent inventory mutation can broadcast identical equipment,
                // but it cannot leak a new firing trigger ahead of the trail.
                val bundles =
                    recipients
                        .filter {
                            it.isOnline &&
                                it.instance === instance &&
                                it.playerConnection.serverState == ConnectionState.PLAY &&
                                it.playerConnection.clientState == ConnectionState.PLAY
                        }.map { viewer ->
                            val packets =
                                buildList<ServerPacket> {
                                    if (viewer === player) {
                                        add(animation.ownerClock)
                                        add(SetPlayerInventorySlotPacket(animation.slot.toInt(), animation.item))
                                        add(kick)
                                        clientTrail?.let(::add)
                                    } else if (viewer in observers) {
                                        ModelManager.shaderTimePacket(viewer, animation.worldAge)?.let(::add)
                                        add(EntityEquipmentPacket(player.entityId, mapOf(EquipmentSlot.MAIN_HAND to animation.item)))
                                    }
                                    if (viewer in trailViewers) addAll(checkNotNull(worldTrail).packets)
                                }
                            val outbound: List<SendablePacket> =
                                if (viewer.playerConnection is PlayerSocketConnection) {
                                    listOf(
                                        prepareGunPacketBundle(viewer, packets),
                                    )
                                } else {
                                    packets
                                }
                            viewer to outbound
                        }
                for ((viewer, packets) in bundles) viewer.sendPackets(packets)
                published = true
            } catch (exception: Exception) {
                // Damage has already been resolved. A visual transport failure
                // must still spend ammunition and synchronize the owner's stack.
                MinecraftServer.getExceptionManager().handleException(exception)
                ModelManager.syncShaderTime(player, animation.worldAge)
                for (viewer in observers) ModelManager.syncShaderTime(viewer, animation.worldAge)
                worldTrail?.send()
                player.sendPacket(kick)
                if (player.playerConnection.serverState == ConnectionState.PLAY &&
                    player.playerConnection.clientState == ConnectionState.PLAY
                ) {
                    clientTrail?.let(player::sendPacket)
                }
            } finally {
                // Keep the normal equip events/attributes and item-change listener
                // path. false suppresses only the owner's redundant slot packet.
                player.inventory.setItemStack(animation.slot.toInt(), animation.item, !published)
            }
        } else {
            trail?.send()
            recoil(player, aimingMultiplier)
            if (!ignoreAmmo) addAmmo(player, -1)
            if (firePos == null) GunAnimation.start(player, this, GunAnimationAction.FIRE, fireAnimationTicks)
        }

        return true
    }

    fun fireFromEntity(
        shooter: LivingEntity,
        targetPosition: Pos,
        validTargets: Collection<LivingEntity>,
    ): LivingEntity? {
        if (shooter.isDead || shooter.isRemoved) return null
        val instance = shooter.instance ?: return null

        shooter.lookAt(targetPosition)
        val aimedOrigin = shooter.position.add(0.0, shooter.eyeHeight, 0.0).withLookAt(targetPosition)
        val origin =
            aimedOrigin.withView(
                aimedOrigin.yaw + spread(),
                aimedOrigin.pitch + spread(),
            )

        instance.playSound(soundFire, origin.x, origin.y, origin.z)

        val ray = Ray(origin, origin.direction().mul(maxRange))
        val blockHit = ray.firstBlock(instance)
        val entityHit =
            ray.firstEntity(
                validTargets.filter { target ->
                    target !== shooter &&
                        !target.isDead &&
                        !target.isRemoved &&
                        target.instance === instance
                },
            )
        val targetPlayers = validTargets.filterIsInstance<Player>().toSet()
        val targetVehicles =
            targetPlayers.mapNotNullTo(hashSetOf()) { player ->
                VehicleRegistry.ride(player)?.entity
            }
        val vehicleHit = checkVehicleHit(instance, origin, ray.direction, ray.distance, targetVehicles)

        val hitTarget: LivingEntity?
        val trailEndPoint: Pos
        val blockHitDistance = blockHit?.t ?: Double.POSITIVE_INFINITY
        val entityHitDistance = entityHit?.t ?: Double.POSITIVE_INFINITY
        val vehicleHitDistance = vehicleHit?.first ?: Double.POSITIVE_INFINITY
        if (vehicleHit != null && vehicleHitDistance < blockHitDistance && vehicleHitDistance < entityHitDistance) {
            val vehicleEntity = vehicleHit.second
            val vehicle = vehicleHit.third
            val hitPoint = origin.add(ray.direction.mul(vehicleHitDistance))
            Particles.dustParticle(instance, hitPoint)
            vehicle.takeDamage(vehicleEntity, ammo.ammoType, damage, shooter as? Player, itemName)
            hitTarget = null
            trailEndPoint = hitPoint
        } else if (entityHit != null && entityHitDistance < blockHitDistance) {
            hitTarget = entityHit.obj
            Particles.bloodParticle(instance, entityHit.point.asPos())

            val damageSource =
                Damage
                    .fromProjectile(shooter, null, damage)
                    .withCombatAttribution(CombatDamageKind.PROJECTILE, itemName)
            Combat.applyDamageWithoutImmunity(hitTarget, damageSource)
            trailEndPoint = entityHit.point.asPos()
        } else {
            hitTarget = null
            if (blockHit != null) {
                Particles.dustParticle(instance, blockHit.point.asPos())
                trailEndPoint = blockHit.point.asPos()
            } else {
                trailEndPoint = origin.add(ray.direction.mul(ray.distance))
            }
        }

        if (bulletTrailParticle != null) {
            Particles.particleLine(instance, bulletTrailParticle, origin, trailEndPoint)
        }

        return hitTarget
    }

    fun spread(speed: Float = 0F): Float {
        val max = spreadMin + speed / 7 * (spreadMax - spreadMin)
        return Random.nextFloat() * max * 2 - max
    }

    fun recoil(
        player: Player,
        multiplier: Float = 1F,
    ) {
        player.sendPacket(recoilPacket(multiplier))
    }

    private fun recoilPacket(multiplier: Float): PlayerPositionAndLookPacket =
        PlayerPositionAndLookPacket(
            -1,
            Pos.ZERO,
            Pos.ZERO,
            0F,
            -(Random.nextFloat() * (recoilMax - recoilMin) + recoilMin) * multiplier,
            RelativeFlags.VIEW or RelativeFlags.COORD or RelativeFlags.DELTA_COORD,
        )

    internal fun checkVehicleHit(
        instance: Instance,
        origin: Pos,
        direction: Vec,
        maxDistance: Double,
        validVehicles: Set<Entity>? = null,
    ): Triple<Double, Entity, Vehicle>? {
        if (maxDistance <= 0.0 || direction.lengthSquared() == 0.0) return null
        val vector = direction.normalize().mul(maxDistance)
        var closest: Triple<Double, Entity, Vehicle>? = null
        for (runtime in VehicleRegistry.all()) {
            val entity = runtime.entity
            val vehicle = runtime.vehicle
            if (entity.instance != instance) continue
            if (validVehicles != null && entity !in validVehicles) continue
            val vehiclePos = entity.position
            val distance =
                vehicle.hitbox.firstIntersection(
                    origin,
                    vector,
                    vehiclePos,
                    vehiclePos.yaw,
                    vehiclePos.pitch,
                    vehicle.hitboxRoll(entity),
                ) ?: continue
            if (closest == null || distance < closest.first) {
                closest = Triple(distance, entity, vehicle)
            }
        }
        return closest
    }
}

internal const val AIMING_REDUCTION_MULTIPLIER = 0.67F

internal fun aimingMultiplier(aiming: Boolean): Float = if (aiming) AIMING_REDUCTION_MULTIPLIER else 1F

private fun gunStatsLore(
    ammo: Ammo,
    maxAmmo: Int,
    damage: Float,
    automatic: Boolean,
    sniper: Boolean,
    cooldown: Long,
    reloadTime: Long,
    recoilMin: Float,
    recoilMax: Float,
    spreadMin: Float,
    spreadMax: Float,
    maxRange: Double,
): List<Component> =
    listOf(
        gunStat("Damage", damage.toStatString()),
        Component
            .text("Ammo: ", NamedTextColor.GRAY)
            .append(ammo.itemName)
            .decoration(TextDecoration.ITALIC, false),
        gunStat("Magazine", "$maxAmmo ${if (maxAmmo == 1) "round" else "rounds"}"),
        gunStat("Fire mode", if (automatic) "Automatic" else "Semi-automatic"),
        gunStat("Fire rate", "${(60_000.0 / cooldown).roundToInt()} RPM"),
        gunStat("Reload", "${reloadTime.toSecondsString()}s"),
        gunStat("Recoil", "${recoilMin.toStatString()}-${recoilMax.toStatString()}°"),
        gunStat("Spread", "${spreadMin.toStatString()}-${spreadMax.toStatString()}°"),
        gunStat("Range", "${maxRange.toStatString()} blocks"),
        gunStat("Scope", if (sniper) "Yes" else "No"),
    )

private fun gunStat(
    name: String,
    value: String,
): Component =
    Component
        .text("$name: $value", NamedTextColor.GRAY)
        .decoration(TextDecoration.ITALIC, false)

private fun Number.toStatString(): String = BigDecimal(toString()).stripTrailingZeros().toPlainString()

private fun Long.toSecondsString(): String = BigDecimal.valueOf(this, 3).stripTrailingZeros().toPlainString()

internal fun bulletTrailOrigin(
    eyePos: Pos,
    mainHand: MainHand,
    offset: Vec,
    aiming: Boolean,
): Pos {
    if (aiming) return eyePos

    val yaw = Math.toRadians(eyePos.yaw.toDouble())
    val sideways = if (mainHand == MainHand.RIGHT) offset.x else -offset.x

    return eyePos
        .add(eyePos.direction().mul(offset.z))
        .add(cos(yaw) * sideways, offset.y, sin(yaw) * sideways)
}
