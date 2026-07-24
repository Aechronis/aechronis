package net.aechronis.combat.objects

import net.aechronis.combat.constants.Tags
import net.aechronis.combat.listeners.KeyPressListener
import net.aechronis.combat.tasks.ModelManager
import net.aechronis.combat.utils.rotatePoint
import net.aechronis.combat.utils.setRoll
import net.kyori.adventure.key.Key
import net.kyori.adventure.sound.Sound
import net.kyori.adventure.text.Component
import net.minestom.server.collision.BoundingBox
import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.Entity
import net.minestom.server.entity.EntityType
import net.minestom.server.entity.LivingEntity
import net.minestom.server.entity.Player
import net.minestom.server.entity.RelativeFlags
import net.minestom.server.entity.attribute.Attribute
import net.minestom.server.entity.metadata.avatar.MannequinMeta
import net.minestom.server.entity.metadata.display.AbstractDisplayMeta
import net.minestom.server.entity.metadata.display.ItemDisplayMeta
import net.minestom.server.instance.Instance
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import net.minestom.server.network.packet.server.play.PlayerPositionAndLookPacket
import net.minestom.server.network.packet.server.play.SetPlayerInventorySlotPacket
import net.minestom.server.network.packet.server.play.SetTimePacket
import net.minestom.server.network.player.ResolvableProfile
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

class Drone(
    name: String,
    itemName: Component,
    itemLore: List<Component> = emptyList(),
    itemModel: String = "${Tags.NAMESPACE}:$name",
    model: String = "${Tags.NAMESPACE}:$name",
    scale: Double,
    hitbox: Hitbox,
    health: Float = 1F,
    placeTime: Long = 3000,
    // top speed in blocks/tick at 100% throttle
    val maxSpeed: Float = 0.6f,
    // degrees of yaw change per tick while holding A/D
    val turnSpeed: Float = 3.0f,
    // degrees of pitch change per tick while holding W/S
    val pitchSpeed: Float = 3.0f,
    val maxRange: Long = 100,
    // flight time in ticks on a full battery at 100% throttle; hovering at
    // zero throttle drains at BATTERY_IDLE_FRACTION of that rate
    val batteryLifeTicks: Int = 2400,
    // model for the mounted payload; null means this drone is unarmed and won't explode
    val projectileModel: String? = null,
    // local-space mount of the payload on the drone body (+Z ahead, -Y down), in blocks
    val projectileMountOffset: Vec = Vec(0.0, -0.35, 0.3),
    // render scale of the mounted payload model; the first-person payload
    // viewmodel is scaled proportionally to match
    val projectileScale: Double = scale,
    val explosionRadius: Int = 4,
    val explosionFire: Double = 0.33,
    // blast damage applied to vehicles/players within the radius when it detonates
    val explosionDamage: Float = 20f,
    // looping flight buzz
    val buzzSound: Sound = Sound.sound(Key.key("${Tags.NAMESPACE}:$name.buzz"), Sound.Source.PLAYER, 1f, 1f),
    // replay length for buzz
    val buzzPeriodTicks: Int = 20,
) : Vehicle(
        name,
        itemName,
        itemLore,
        itemModel,
        model,
        scale,
        hitbox,
        health,
        placeTime,
    ) {
    override fun onEnter(
        player: Player,
        entity: Entity,
    ) {
        playerOriginalPos[player] = player.position

        spawnOperatorMannequin(player)
        player.updateViewableRule { false }
        playerOriginalBoundingBox[player] = player.boundingBox
        player.boundingBox = BoundingBox(0.0, 0.0, 0.0)

        entity.updateViewableRule({ viewer -> viewer != player })
        entityPayload[entity]?.updateViewableRule { viewer -> viewer != player }

        super.onEnter(player, entity)

        playerThrottle[player] = 0F

        val inverted = abs(entity.position.pitch) > 90f
        playerYaw[player] = entity.position.yaw
        playerPitch[player] = entity.position.pitch
        playerInverted[player] = inverted
        playerPendingSwitch[player] = false

        player.spectate(entitySpider[entity])

        entitySpider[entity]?.let { camera ->
            spawnViewmodels(player, camera, camera.position.yaw, camera.position.pitch, inverted)
        }
    }

    private fun spawnViewmodels(
        player: Player,
        camera: Entity,
        yaw: Float,
        pitch: Float,
        inverted: Boolean,
    ) {
        playerViewmodel[player] = spawnViewmodel(player, camera, model, VIEWMODEL_DOWN, VIEWMODEL_FORWARD, yaw, pitch, inverted)
        val projModel = projectileModel
        if (projModel != null) {
            val payloadVmScale = VIEWMODEL_SCALE * (projectileScale / scale)
            playerPayloadViewmodel[player] =
                spawnViewmodel(
                    player,
                    camera,
                    projModel,
                    VIEWMODEL_PAYLOAD_DOWN,
                    VIEWMODEL_PAYLOAD_FORWARD,
                    yaw,
                    pitch,
                    inverted,
                    payloadVmScale,
                )
        }
    }

    private fun spawnViewmodel(
        player: Player,
        mount: Entity,
        vmModel: String,
        down: Double,
        forward: Double,
        yaw: Float,
        pitch: Float,
        inverted: Boolean,
        vmScale: Double = VIEWMODEL_SCALE,
    ): Entity {
        val viewmodel = Entity(EntityType.ITEM_DISPLAY)
        viewmodel.updateViewableRule { it == player }
        viewmodel.setInstance(mount.instance!!, mount.position.withView(yaw, pitch))

        val meta = viewmodel.entityMeta as ItemDisplayMeta
        meta.itemStack = ItemStack.of(Material.BONE).withItemModel(vmModel)
        meta.setBillboardRenderConstraints(AbstractDisplayMeta.BillboardConstraints.FIXED)
        meta.scale = Vec(vmScale)
        meta.setTranslation(viewmodelTranslation(down, forward, inverted))

        meta.leftRotation = setRoll(if (inverted) Math.PI.toFloat() else 0f)
        meta.setTransformationInterpolationDuration(0)
        meta.posRotInterpolationDuration = VIEWMODEL_INTERPOLATION
        meta.isHasNoGravity = true
        viewmodel.spawn()
        viewmodel.setView(yaw, pitch)

        mount.addPassenger(viewmodel)
        return viewmodel
    }

    private fun viewmodelTranslation(
        down: Double,
        forward: Double,
        inverted: Boolean,
    ): Vec = Vec(0.0, if (inverted) down else -down, forward)

    override fun onExit(player: Player) {
        // restore the drone model's (and mounted payload's) visibility to the pilot
        val entity = playerVehicleEntity[player]
        entity?.addViewer(player)
        entityPayload[entity]?.addViewer(player)
        super.onExit(player)
        playerOriginalPos.remove(player)
        playerThrottle.remove(player)
        playerBuzzTick.remove(player)
        playerPayloadViewmodel.remove(player)?.remove()
        // base class drops the vehicle/entity tracking
        playerYaw.remove(player)
        playerPitch.remove(player)
        playerInverted.remove(player)
        playerPendingSwitch.remove(player)
        playerBoundary.remove(player)
        playerLockYaw.remove(player)
        playerLockPitch.remove(player)
        playerViewmodel.remove(player)?.remove()
        // restore the normal shader/time signal in case we exited inverted
        player.stopSpectating()

        // return the pilot to the operator clone's spot
        playerOperatorMannequin.remove(player)?.let { mannequin ->
            mannequinPilot.remove(mannequin)
            player.teleport(mannequin.position)
            mannequin.remove()
        }
        player.updateViewableRule { true }
        playerOriginalBoundingBox.remove(player)?.let { player.boundingBox = it }
    }

    private fun spawnOperatorMannequin(player: Player) {
        val mannequin = LivingEntity(EntityType.MANNEQUIN)
        mannequin.editEntityMeta(MannequinMeta::class.java) { meta ->
            meta.profile = ResolvableProfile(player.skin)
        }
        mannequin.setInstance(player.instance, player.position)
        mannequin.helmet = player.helmet
        mannequin.chestplate = player.chestplate
        mannequin.leggings = player.leggings
        mannequin.boots = player.boots
        mannequin.spawn()
        playerOperatorMannequin[player] = mannequin
        mannequinPilot[mannequin] = player
    }

    override fun spawn(
        player: Player,
        pos: Pos,
    ): Entity {
        val entity = super.spawn(player, pos)

        entitySpider[entity] = spawnSpider(player.instance, entity.position.withPitch(0F))
        entityBattery[entity] = 1f

        // mount the payload model so observers see the drone carrying it
        if (projectileModel != null) {
            entityPayload[entity] = spawnPayloadDisplay(entity)
        }

        return entity
    }

    override fun destroy(
        entity: Entity,
        attacker: Player?,
        weapon: Component?,
    ) {
        // these aren't tracked by the base Vehicle, so clean them up ourselves
        entitySpider.remove(entity)?.remove()
        entityPayload.remove(entity)?.remove()
        entityBattery.remove(entity)
        super.destroy(entity, attacker, weapon)
    }

    // the payload model shown attached to the drone for outside observers
    private fun spawnPayloadDisplay(drone: Entity): Entity {
        val display = Entity(EntityType.ITEM_DISPLAY)
        display.setInstance(drone.instance!!, payloadWorldPos(drone.position))

        val meta = display.entityMeta as ItemDisplayMeta
        meta.itemStack = ItemStack.of(Material.BONE).withItemModel(projectileModel!!)
        meta.posRotInterpolationDuration = 3
        meta.scale = Vec(projectileScale)
        meta.isHasNoGravity = true

        display.spawn()
        return display
    }

    // world transform of the mounted payload, given the drone's rendered pose
    private fun payloadWorldPos(dronePos: Pos): Pos {
        val o = rotatePoint(projectileMountOffset, dronePos.yaw, dronePos.pitch, 0f)
        return Pos(dronePos.x + o.x, dronePos.y + o.y, dronePos.z + o.z, dronePos.yaw, dronePos.pitch)
    }

    // ends a pilots flight
    private fun endFlight(player: Player) {
        val entity = playerVehicleEntity[player]
        if (projectileModel == null || entity == null) {
            onExit(player)
            return
        }

        // The blast can't hurt the drone that detonated (it's destroyed anyway)
        // nor the pilot's own operator clone (the pilot is flying it remotely).
        entity.instance?.let { instance ->
            Explosion(instance, entity.position, explosionRadius, explosionFire, explosionDamage, player)
        }

        // destroy() ejects the pilot (via onExit) and clears the spider/payload
        destroy(entity)
    }

    private fun droneImpactPoint(
        player: Player,
        entity: Entity,
        from: Pos,
        to: Pos,
    ): Pos? {
        val instance = entity.instance ?: return null
        val delta = Vec(to.x - from.x, to.y - from.y, to.z - from.z)
        val dist = delta.length()
        if (dist == 0.0) return null
        val dir = delta.normalize()

        var d = COLLISION_STEP
        while (d < dist) {
            val p = Pos(from.x + dir.x * d, from.y + dir.y * d, from.z + dir.z * d, to.yaw, to.pitch)
            if (isObstructed(instance, p, player, entity)) return p
            d += COLLISION_STEP
        }
        return if (isObstructed(instance, to, player, entity)) to else null
    }

    private fun isObstructed(
        instance: Instance,
        p: Pos,
        pilot: Player,
        droneEntity: Entity,
    ): Boolean {
        if (instance.getBlock(p).isSolid) return true

        for ((vehicleEntity, vehicle) in Vehicle.entityVehicle) {
            if (vehicleEntity == droneEntity || vehicleEntity.instance != instance) continue
            val vp = vehicleEntity.position
            if (vehicle.hitbox.containsPoint(p.asVec(), vp, vp.yaw, vp.pitch, 0f) != null) return true
        }

        for (other in instance.players) {
            if (other == pilot) continue
            val bb = other.boundingBox
            val op = other.position
            val halfWidth = bb.width() / 2.0
            val withinHorizontal =
                p.x >= op.x - halfWidth &&
                    p.x <= op.x + halfWidth &&
                    p.z >= op.z - halfWidth &&
                    p.z <= op.z + halfWidth
            if (withinHorizontal && p.y >= op.y && p.y <= op.y + bb.height()) return true
        }

        return false
    }

    private fun spawnSpider(
        instance: Instance,
        pos: Pos,
    ): LivingEntity {
        val spider = LivingEntity(EntityType.CAVE_SPIDER)
        spider.setInstance(instance, pos)
        spider.setNoGravity(true)
        spider.isInvisible = true
        spider.getAttribute(Attribute.SCALE).baseValue = 0.0
        spider.spawn()
        return spider
    }

    override fun onTick(player: Player) {
        val entity = playerVehicleEntity[player] ?: return
        val inputEvent = KeyPressListener.playerInputEvent[player]

        player.spectate(entitySpider[entity])

        ModelManager.disableHitAnimation(player)

        if (inputEvent?.isHoldingShiftKey == true) {
            endFlight(player)
            return
        }

        // battery
        val throttle = playerThrottle[player] ?: 0F
        val drain = (BATTERY_IDLE_FRACTION + (1f - BATTERY_IDLE_FRACTION) * throttle / 100f) / batteryLifeTicks
        val battery = ((entityBattery[entity] ?: 1f) - drain).coerceAtLeast(0f)
        entityBattery[entity] = battery
        if (battery <= 0f) {
            destroy(entity)
            return
        }

        val position = entity.position

        // continuous orientation
        var yaw = playerYaw[player] ?: position.yaw
        val prevPitch = playerPitch[player] ?: position.pitch
        var rawPitch = prevPitch
        val activeInverted = playerInverted[player] == true
        val yawSign = if (activeInverted) -1f else 1f
        if (inputEvent != null) {
            if (inputEvent.isHoldingForwardKey) rawPitch += pitchSpeed
            if (inputEvent.isHoldingBackwardKey) rawPitch -= pitchSpeed
            if (inputEvent.isHoldingLeftKey) yaw -= turnSpeed * yawSign
            if (inputEvent.isHoldingRightKey) yaw += turnSpeed * yawSign
        }

        val velocityPitch = rawPitch
        // the unclamped pitch the model/payload are rendered with for observers
        val renderPitch = wrapDegrees(velocityPitch)

        val pendingSwitch = playerPendingSwitch[player] ?: false
        var newActiveInverted = activeInverted
        var doSwitch = false

        if (pendingSwitch) {
            rawPitch = playerBoundary[player] ?: 90f
            newActiveInverted = !activeInverted
            doSwitch = true
            playerPendingSwitch[player] = false
        } else {
            val crossPos = (prevPitch < 90f && rawPitch > 90f) || (prevPitch > 90f && rawPitch < 90f)
            val crossNeg = (prevPitch < -90f && rawPitch > -90f) || (prevPitch > -90f && rawPitch < -90f)
            val boundary =
                when {
                    crossPos -> 90f
                    crossNeg -> -90f
                    else -> null
                }
            if (boundary != null) {
                rawPitch = boundary
                playerBoundary[player] = boundary
                playerPendingSwitch[player] = true
            } else {
                val rawInverted = abs(rawPitch) > 90f
                val nearBoundary = abs(abs(rawPitch) - 90f) < 0.5f
                if (rawInverted != activeInverted && !nearBoundary) {
                    val resyncBoundary = if (rawPitch > 0f) 90f else -90f
                    rawPitch = resyncBoundary
                    playerBoundary[player] = resyncBoundary
                    playerPendingSwitch[player] = true
                }
            }
        }

        yaw = wrapDegrees(yaw)
        val pitch = wrapDegrees(rawPitch)
        playerYaw[player] = yaw
        playerPitch[player] = pitch
        playerInverted[player] = newActiveInverted

        val yawRad = Math.toRadians(yaw.toDouble())
        val pitchRad = Math.toRadians(velocityPitch.toDouble())
        val xz = cos(pitchRad)
        val dirX = -xz * sin(yawRad)
        val dirY = -sin(pitchRad)
        val dirZ = xz * cos(yawRad)

        val displayYaw: Float
        val displayPitch: Float
        if (!newActiveInverted) {
            displayYaw = yaw
            displayPitch = pitch
        } else {
            displayYaw = wrapDegrees(yaw + 180f)
            displayPitch = if (pitch >= 0f) 180f - pitch else -180f - pitch
        }

        val atBoundary = abs(pitch) >= 90f
        val spiderPitch = if (atBoundary) displayPitch else displayPitch - 1F

        val speed = ((throttle / 100f) * maxSpeed).toDouble()

        val finalPos =
            Pos(
                position.x + dirX * speed,
                position.y + dirY * speed,
                position.z + dirZ * speed,
                yaw,
                pitch,
            )

        val impact = droneImpactPoint(player, entity, position, finalPos)
        if (impact != null) {
            entity.teleport(impact.withPitch(renderPitch))
            if (projectileModel != null) {
                endFlight(player)
            } else {
                // destroy() ejects the pilot (via onExit) and clears the spider
                destroy(entity)
            }
            return
        }

        entity.teleport(finalPos.withPitch(renderPitch))

        val buzzPeriod = buzzPeriodTicks.coerceAtLeast(1)
        val buzzTick = playerBuzzTick[player] ?: 0
        if (buzzTick == 0) {
            val pitch = (buzzSound.pitch() * (1f + BUZZ_THROTTLE_PITCH_GAIN * throttle / 100f)).coerceIn(0.5f, 2.0f)
            val buzz = Sound.sound(buzzSound.name(), buzzSound.source(), buzzSound.volume(), pitch)
            entity.instance?.playSound(buzz, finalPos.x, finalPos.y, finalPos.z)
        }
        playerBuzzTick[player] = (buzzTick + 1) % buzzPeriod

        entityPayload[entity]?.teleport(payloadWorldPos(finalPos.withPitch(renderPitch)))

        playerSeatEntity[player]?.teleport(finalPos)

        val center = hitbox.getWorldCenter(finalPos, yaw, pitch, 0f)

        if (doSwitch) {
            val instance = entity.instance
            if (instance != null) {
                val oldSpider = entitySpider[entity]
                val freshSpider = spawnSpider(instance, center.withView(displayYaw, spiderPitch))
                freshSpider.setView(displayYaw, spiderPitch, displayYaw)
                entitySpider[entity] = freshSpider
                // spectate the fresh camera before removing the old one
                player.spectate(freshSpider)

                // respawn the viewmodel(s) on the fresh camera in lockstep
                val oldViewmodel = playerViewmodel[player]
                val oldPayloadViewmodel = playerPayloadViewmodel[player]
                spawnViewmodels(player, freshSpider, displayYaw, spiderPitch, newActiveInverted)
                oldViewmodel?.remove()
                oldPayloadViewmodel?.remove()
                oldSpider?.remove()
            }
        }

        val spider = entitySpider[entity]
        if (spider != null) {
            spider.teleport(center.withView(spider.position.yaw, spider.position.pitch))
            spider.setView(displayYaw, spiderPitch, displayYaw)
        }

        // keep the first-person models oriented to the camera so they stay in the same place on screen
        playerViewmodel[player]?.setView(displayYaw, spiderPitch)
        playerPayloadViewmodel[player]?.setView(displayYaw, spiderPitch)

        playerLockYaw[player] = displayYaw
        playerLockPitch[player] = displayPitch
        player.sendPacket(
            PlayerPositionAndLookPacket(
                -1,
                Pos.ZERO,
                Pos.ZERO,
                displayYaw,
                displayPitch,
                RelativeFlags.COORD or RelativeFlags.DELTA_COORD,
            ),
        )

        val distance = playerOriginalPos[player]?.distance(entity.position) ?: return

        // out of range
        if (distance > maxRange) {
            endFlight(player)
            return
        }

        entity.instance?.let {
            sendTelemetry(player, it, speed, battery, distance, newActiveInverted)
        }

        // fill the hotbar with glow lichen, which the resource pack retextures to nothing
        for (slot in 0..8) {
            player.sendPacket(SetPlayerInventorySlotPacket(slot, ItemStack.of(Material.GLOW_LICHEN).withCustomName(Component.empty())))
        }
    }

    // sends one frame of pilot HUD telemetry to the shader via the worldAge field
    private fun sendTelemetry(
        player: Player,
        instance: Instance,
        speed: Double,
        battery: Float,
        distance: Double,
        inverted: Boolean,
    ) {
        val time =
            encodeTelemetry(
                speed = (speed / TELEMETRY_TOP_SPEED).toFloat(),
                battery = battery,
                link = linkQuality(distance),
                inverted = inverted,
            )
        player.sendPacket(SetTimePacket(time, player.instance.createTimePacket().clocks))
    }

    // video link quality; full next to the controller, zero at maxRange
    private fun linkQuality(distance: Double): Float = (1.0 - distance / maxRange).toFloat()

    private fun encodeTelemetry(
        speed: Float,
        battery: Float,
        link: Float,
        inverted: Boolean,
    ): Long {
        fun q(
            v: Float,
            max: Int,
        ) = (v.coerceIn(0f, 1f) * max).roundToInt().toLong()
        return (if (inverted) 12000L else 0L) + q(battery, 19) * 400L + q(link, 19) * 20L + q(speed, 9) * 2L
    }

    // wraps an angle in degrees to the range (-180, 180]
    private fun wrapDegrees(deg: Float): Float {
        var d = deg % 360f
        if (d <= -180f) d += 360f
        if (d > 180f) d -= 360f
        return d
    }

    companion object {
        val entitySpider = hashMapOf<Entity, LivingEntity>()

        val entityBattery = hashMapOf<Entity, Float>()

        val entityPayload = hashMapOf<Entity, Entity>()

        val playerOperatorMannequin = hashMapOf<Player, LivingEntity>()
        val mannequinPilot = hashMapOf<LivingEntity, Player>()

        val playerOriginalPos = HashMap<Player, Pos>()

        val playerOriginalBoundingBox = hashMapOf<Player, BoundingBox>()

        val playerThrottle = hashMapOf<Player, Float>()

        val playerBuzzTick = hashMapOf<Player, Int>()

        val playerYaw = hashMapOf<Player, Float>()
        val playerPitch = hashMapOf<Player, Float>()

        val playerInverted = hashMapOf<Player, Boolean>()

        val playerPendingSwitch = hashMapOf<Player, Boolean>()
        val playerBoundary = hashMapOf<Player, Float>()

        val playerLockYaw = hashMapOf<Player, Float>()
        val playerLockPitch = hashMapOf<Player, Float>()

        val playerViewmodel = hashMapOf<Player, Entity>()
        val playerPayloadViewmodel = hashMapOf<Player, Entity>()

        const val BATTERY_IDLE_FRACTION = 0.25f

        const val BUZZ_THROTTLE_PITCH_GAIN = 0.6f

        const val TELEMETRY_TOP_SPEED = 1.0
        const val COLLISION_STEP = 0.3

        const val VIEWMODEL_FORWARD = -0.1 // blocks ahead of the camera
        const val VIEWMODEL_DOWN = -.35 // blocks below centre
        const val VIEWMODEL_PAYLOAD_FORWARD = 0.1
        const val VIEWMODEL_PAYLOAD_DOWN = 0.2
        val VIEWMODEL_SCALE = 2.0 // model scale
        const val VIEWMODEL_INTERPOLATION = 3
    }
}
