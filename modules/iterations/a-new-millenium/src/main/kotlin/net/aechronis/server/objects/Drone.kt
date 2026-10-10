package net.aechronis.server.objects

import net.aechronis.combat.constants.Tags
import net.aechronis.combat.listeners.KeyPressListener
import net.aechronis.combat.objects.AmmoTypes
import net.aechronis.combat.objects.AnimatedPart
import net.aechronis.combat.objects.Explosion
import net.aechronis.combat.objects.Hitbox
import net.aechronis.combat.objects.ShulkerHitbox
import net.aechronis.combat.objects.Vehicle
import net.aechronis.combat.objects.VehicleSeat
import net.aechronis.combat.objects.VehicleSeatRole
import net.aechronis.combat.tasks.ModelManager
import net.aechronis.combat.utils.rotatePoint
import net.aechronis.combat.utils.setRoll
import net.aechronis.server.modules.ModuleScheduler
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
import net.minestom.server.entity.attribute.Attribute
import net.minestom.server.entity.metadata.avatar.MannequinMeta
import net.minestom.server.entity.metadata.display.AbstractDisplayMeta
import net.minestom.server.entity.metadata.display.ItemDisplayMeta
import net.minestom.server.event.player.PlayerInputEvent
import net.minestom.server.instance.Instance
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import net.minestom.server.network.packet.server.play.PlayerRotationPacket
import net.minestom.server.network.packet.server.play.SetPlayerInventorySlotPacket
import net.minestom.server.network.packet.server.play.SetTimePacket
import net.minestom.server.network.player.ResolvableProfile
import net.minestom.server.timer.Task
import net.minestom.server.timer.TaskSchedule
import java.util.concurrent.ConcurrentHashMap
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
    val explosionAmmoType: AmmoTypes = AmmoTypes.BOMB,
    // looping flight buzz
    val buzzSound: Sound = Sound.sound(Key.key("${Tags.NAMESPACE}:$name.buzz"), Sound.Source.PLAYER, 1f, 1f),
    // replay length for buzz
    val buzzPeriodTicks: Int = 20,
    animatedParts: List<AnimatedPart> = emptyList(),
    collisionHitbox: ShulkerHitbox = ShulkerHitbox.fromHitbox(hitbox),
) : Vehicle(
        name,
        itemName,
        itemLore,
        itemModel,
        model,
        scale,
        hitbox,
        null,
        placeTime,
        seats = listOf(VehicleSeat("pilot", "Remote pilot", VehicleSeatRole.PILOT)),
        animatedParts = animatedParts,
        collisionHitbox = collisionHitbox,
    ) {
    val rawHealth: Float = health

    override val persistent = false
    override val customDriverView = true

    private class Runtime(
        val entity: Entity,
        var health: Float,
        var battery: Float = 1f,
        var camera: LivingEntity? = null,
        var payload: Entity? = null,
    )

    private class OperatorSession(
        val runtime: Runtime,
        val originalPosition: Pos,
        val originalBoundingBox: BoundingBox,
        var yaw: Float,
        var pitch: Float,
        var inverted: Boolean,
        var mannequin: LivingEntity? = null,
        var throttle: Float = 0f,
        var buzzTick: Int = 0,
        var pendingSwitch: Boolean = false,
        var boundary: Float = 90f,
        var viewmodel: Entity? = null,
        var payloadViewmodel: Entity? = null,
    )

    private val runtimes = HashMap<Entity, Runtime>()
    private val operators = HashMap<Player, OperatorSession>()

    override fun healthStatus(entity: Entity): Pair<Float, Float>? = runtimes[entity]?.let { it.health to rawHealth }

    override fun onEnter(
        player: Player,
        entity: Entity,
    ) {
        if (!canEnterAsDriver(player, entity)) return
        val runtime = runtimes[entity] ?: return

        clearCrashStatic(player)
        val inverted = abs(entity.position.pitch) > 90f
        val session = OperatorSession(runtime, player.position, player.boundingBox, entity.position.yaw, entity.position.pitch, inverted)
        operators[player] = session
        try {
            spawnOperatorMannequin(player, session)
            player.boundingBox = BoundingBox(0.0, 0.0, 0.0)

            entity.updateViewableRule({ viewer -> viewer != player })
            runtime.payload?.updateViewableRule { viewer -> viewer != player }

            super.onEnter(player, entity)
            if (driverEntity(player) !== entity) {
                onExit(player)
                return
            }

            spectateCamera(player, runtime.camera)
            runtime.camera?.let { camera ->
                spawnViewmodels(player, session, camera, camera.position.yaw, camera.position.pitch, inverted)
            }
        } catch (failure: Exception) {
            runCatching { onExit(player) }.onFailure(failure::addSuppressed)
            throw failure
        }
    }

    private fun spawnViewmodels(
        player: Player,
        session: OperatorSession,
        camera: Entity,
        yaw: Float,
        pitch: Float,
        inverted: Boolean,
    ) {
        val viewmodel = spawnViewmodel(player, camera, model, VIEWMODEL_DOWN, VIEWMODEL_FORWARD, yaw, pitch, inverted)
        try {
            val payloadViewmodel =
                projectileModel?.let { projModel ->
                    val payloadVmScale = VIEWMODEL_SCALE * (projectileScale / scale)
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
            session.viewmodel = viewmodel
            session.payloadViewmodel = payloadViewmodel
        } catch (failure: Exception) {
            runCatching { viewmodel.remove() }.onFailure(failure::addSuppressed)
            throw failure
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
        try {
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
        } catch (failure: Exception) {
            runCatching { viewmodel.remove() }.onFailure(failure::addSuppressed)
            throw failure
        }
    }

    private fun viewmodelTranslation(
        down: Double,
        forward: Double,
        inverted: Boolean,
    ): Vec = Vec(0.0, if (inverted) down else -down, forward)

    override fun onExit(player: Player) {
        val session = operators.remove(player)
        if (session == null) {
            super.onExit(player)
            return
        }
        val retainCrashStatic = hasCrashStatic(player)
        // restore the drone model's (and mounted payload's) visibility to the pilot
        try {
            session.runtime.entity.addViewer(player)
            session.runtime.payload?.addViewer(player)
        } finally {
            try {
                super.onExit(player)
            } finally {
                restoreOperator(player, session, retainCrashStatic)
            }
        }
    }

    private fun restoreOperator(
        player: Player,
        session: OperatorSession,
        retainCrashStatic: Boolean,
    ) {
        val failures = ArrayList<Throwable>()

        fun cleanup(action: () -> Unit) {
            runCatching(action).onFailure(failures::add)
        }
        cleanup { session.payloadViewmodel?.remove() }
        cleanup { session.viewmodel?.remove() }
        if (player.isOnline) {
            for (slot in PILOT_HOTBAR_SLOTS) {
                cleanup { player.sendPacket(SetPlayerInventorySlotPacket(slot, player.inventory.getItemStack(slot))) }
            }
        }

        if (!retainCrashStatic) cleanup { player.stopSpectating() }
        cleanup { session.runtime.camera?.removeViewer(player) }

        // return the pilot to the operator clone's spot
        session.mannequin?.let { mannequin ->
            mannequinPilot.remove(mannequin)
            cleanup { player.teleport(if (mannequin.instance == null) session.originalPosition else mannequin.position) }
            cleanup { mannequin.remove() }
        }
        cleanup { player.boundingBox = session.originalBoundingBox }
        failures.firstOrNull()?.let { failure ->
            failures.drop(1).forEach(failure::addSuppressed)
            throw failure
        }
    }

    private fun spawnOperatorMannequin(
        player: Player,
        session: OperatorSession,
    ) {
        val mannequin = LivingEntity(EntityType.MANNEQUIN)
        session.mannequin = mannequin
        mannequin.editEntityMeta(MannequinMeta::class.java) { meta ->
            meta.profile = ResolvableProfile(player.skin)
        }
        mannequin.setInstance(player.instance, player.position)
        mannequin.helmet = player.helmet
        mannequin.chestplate = player.chestplate
        mannequin.leggings = player.leggings
        mannequin.boots = player.boots
        mannequin.spawn()
        mannequinPilot[mannequin] = player
    }

    override fun spawn(
        instance: Instance,
        pos: Pos,
    ): Entity {
        val entity = super.spawn(instance, pos)
        val runtime = Runtime(entity, rawHealth)
        runtimes[entity] = runtime
        try {
            runtime.camera = spawnSpider(instance, entity.position.withPitch(0F))
            // mount the payload model so observers see the drone carrying it
            if (projectileModel != null) {
                runtime.payload = spawnPayloadDisplay(entity)
            }
        } catch (failure: Exception) {
            runCatching { removeRuntimeEntity(entity) }.onFailure(failure::addSuppressed)
            throw failure
        }

        return entity
    }

    override fun cleanupRuntime(entity: Entity) {
        val runtime = runtimes.remove(entity) ?: return
        try {
            runtime.camera?.remove()
        } finally {
            runtime.payload?.remove()
        }
    }

    override fun takeDamage(
        entity: Entity,
        ammoType: AmmoTypes?,
        amount: Float,
        attacker: Player?,
        weapon: Component?,
    ): Boolean {
        val runtime = runtimes[entity] ?: return false
        val newHealth = runtime.health - amount
        runtime.health = newHealth
        if (newHealth <= 0f) {
            destroy(entity, attacker, weapon)
            return true
        }
        return false
    }

    // the payload model shown attached to the drone for outside observers
    private fun spawnPayloadDisplay(drone: Entity): Entity {
        val display = Entity(EntityType.ITEM_DISPLAY)
        try {
            display.setInstance(drone.instance!!, payloadWorldPos(drone.position))

            val meta = display.entityMeta as ItemDisplayMeta
            meta.itemStack = ItemStack.of(Material.BONE).withItemModel(projectileModel!!)
            meta.posRotInterpolationDuration = 3
            meta.scale = Vec(projectileScale)
            meta.isHasNoGravity = true

            display.spawn()
            return display
        } catch (failure: Exception) {
            runCatching { display.remove() }.onFailure(failure::addSuppressed)
            throw failure
        }
    }

    // world transform of the mounted payload, given the drone's rendered pose
    private fun payloadWorldPos(dronePos: Pos): Pos {
        val o = rotatePoint(projectileMountOffset, dronePos.yaw, dronePos.pitch, 0f)
        return Pos(dronePos.x + o.x, dronePos.y + o.y, dronePos.z + o.z, dronePos.yaw, dronePos.pitch)
    }

    // ends a pilots flight
    private fun endFlight(player: Player) {
        val entity = driverEntity(player)
        if (projectileModel == null || entity == null) {
            onExit(player)
            return
        }
        detonate(entity, player)
    }

    private fun detonate(
        entity: Entity,
        pilot: Player?,
    ) {
        entity.instance?.let { instance ->
            Explosion.bypassingDamageImmunity(
                instance = instance,
                pos = entity.position,
                radius = explosionRadius,
                fire = explosionFire,
                damage = explosionDamage,
                source = pilot,
                weapon = null,
                ammoType = explosionAmmoType,
            )
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
        if (instance.getBlock(p).solid()) return true

        if (intersectsVehicle(instance, p.asVec(), droneEntity)) return true

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
        try {
            spider.isAutoViewable = false
            spider.setInstance(instance, pos)
            spider.setNoGravity(true)
            spider.isInvisible = true
            spider.getAttribute(Attribute.SCALE).baseValue = 0.0
            spider.spawn()
            return spider
        } catch (failure: Exception) {
            runCatching { spider.remove() }.onFailure(failure::addSuppressed)
            throw failure
        }
    }

    private fun spectateCamera(
        player: Player,
        camera: LivingEntity?,
    ) {
        camera ?: return
        camera.addViewer(player)
        player.spectate(camera)
    }

    override fun onTick(player: Player) {
        val entity = driverEntity(player) ?: return
        val session = operators[player] ?: return
        val runtime = session.runtime
        val inputEvent = KeyPressListener.playerInputEvent[player]

        spectateCamera(player, runtime.camera)

        if (inputEvent?.isHoldingShiftKey == true) {
            endFlight(player)
            return
        }

        val throttle = session.throttle
        val battery = drainBattery(runtime, throttle)
        if (battery <= 0f) {
            destroy(entity)
            return
        }

        val position = entity.position
        val orientation = updateOrientation(session, inputEvent)
        val speed = ((throttle / 100f) * maxSpeed).toDouble()
        val finalPos = moveDrone(player, entity, position, orientation, speed) ?: return

        playFlightBuzz(session, finalPos, throttle)
        runtime.payload?.teleport(payloadWorldPos(finalPos.withPitch(orientation.renderPitch)))
        driverSeat(player)?.teleport(finalPos)

        updatePilotView(player, session, finalPos, orientation)

        val distance = session.originalPosition.distance(entity.position)
        if (distance > maxRange) {
            endFlight(player)
            return
        }

        updatePilotHud(player, entity, speed, battery, distance, orientation.inverted)
    }

    private fun drainBattery(
        runtime: Runtime,
        throttle: Float,
    ): Float {
        val drain = (BATTERY_IDLE_FRACTION + (1f - BATTERY_IDLE_FRACTION) * throttle / 100f) / batteryLifeTicks
        val battery = (runtime.battery - drain).coerceAtLeast(0f)
        runtime.battery = battery
        return battery
    }

    private data class FlightOrientation(
        val yaw: Float,
        val pitch: Float,
        val renderPitch: Float,
        val direction: Vec,
        val displayYaw: Float,
        val displayPitch: Float,
        val cameraPitch: Float,
        val inverted: Boolean,
        val switchCamera: Boolean,
    )

    private fun updateOrientation(
        session: OperatorSession,
        inputEvent: PlayerInputEvent?,
    ): FlightOrientation {
        var yaw = session.yaw
        val prevPitch = session.pitch
        var rawPitch = prevPitch
        val activeInverted = session.inverted
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

        val pendingSwitch = session.pendingSwitch
        var newActiveInverted = activeInverted
        var doSwitch = false

        // Hold the camera at the inversion boundary for one tick, then replace it
        // on the next tick. Movement and observer rendering keep the unclamped pitch.
        if (pendingSwitch) {
            rawPitch = session.boundary
            newActiveInverted = !activeInverted
            doSwitch = true
            session.pendingSwitch = false
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
                session.boundary = boundary
                session.pendingSwitch = true
            } else {
                val rawInverted = abs(rawPitch) > 90f
                val nearBoundary = abs(abs(rawPitch) - 90f) < 0.5f
                if (rawInverted != activeInverted && !nearBoundary) {
                    val resyncBoundary = if (rawPitch > 0f) 90f else -90f
                    rawPitch = resyncBoundary
                    session.boundary = resyncBoundary
                    session.pendingSwitch = true
                }
            }
        }

        yaw = wrapDegrees(yaw)
        val pitch = wrapDegrees(rawPitch)
        session.yaw = yaw
        session.pitch = pitch
        session.inverted = newActiveInverted

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

        return FlightOrientation(
            yaw = yaw,
            pitch = pitch,
            renderPitch = renderPitch,
            direction = Vec(dirX, dirY, dirZ),
            displayYaw = displayYaw,
            displayPitch = displayPitch,
            cameraPitch = spiderPitch,
            inverted = newActiveInverted,
            switchCamera = doSwitch,
        )
    }

    private fun moveDrone(
        player: Player,
        entity: Entity,
        position: Pos,
        orientation: FlightOrientation,
        speed: Double,
    ): Pos? {
        val finalPos =
            Pos(
                position.x + orientation.direction.x * speed,
                position.y + orientation.direction.y * speed,
                position.z + orientation.direction.z * speed,
                orientation.yaw,
                orientation.pitch,
            )

        val impact = droneImpactPoint(player, entity, position, finalPos)
        if (impact != null) {
            entity.teleport(impact.withPitch(orientation.renderPitch))
            entity.instance?.let { instance ->
                val crashCamera = spawnSpider(instance, impact.withView(orientation.displayYaw, orientation.cameraPitch))
                startCrashStatic(player, crashCamera)
            }
            detonate(entity, player)
            return null
        }

        entity.teleport(finalPos.withPitch(orientation.renderPitch))
        return finalPos
    }

    private fun playFlightBuzz(
        session: OperatorSession,
        position: Pos,
        throttle: Float,
    ) {
        val buzzPeriod = buzzPeriodTicks.coerceAtLeast(1)
        val buzzTick = session.buzzTick
        if (buzzTick == 0) {
            val pitch = (buzzSound.pitch() * (1f + BUZZ_THROTTLE_PITCH_GAIN * throttle / 100f)).coerceIn(0.5f, 2.0f)
            val buzz = Sound.sound(buzzSound.name(), buzzSound.source(), buzzSound.volume(), pitch)
            session.runtime.entity.instance
                ?.playSound(buzz, position.x, position.y, position.z)
        }
        session.buzzTick = (buzzTick + 1) % buzzPeriod
    }

    private fun updatePilotView(
        player: Player,
        session: OperatorSession,
        position: Pos,
        orientation: FlightOrientation,
    ) {
        val center = hitbox.getWorldCenter(position, orientation.yaw, orientation.pitch, 0f)
        if (orientation.switchCamera) {
            replaceCamera(player, session, center, orientation)
        }

        val spider = session.runtime.camera
        if (spider != null) {
            spider.teleport(center.withView(spider.position.yaw, spider.position.pitch))
            spider.setView(orientation.displayYaw, orientation.cameraPitch, orientation.displayYaw)
        }

        // keep the first-person models oriented to the camera so they stay in the same place on screen
        session.viewmodel?.setView(orientation.displayYaw, orientation.cameraPitch)
        session.payloadViewmodel?.setView(orientation.displayYaw, orientation.cameraPitch)

        // Lock the view without creating a player teleport while the pilot is mounted.
        player.sendPacket(
            PlayerRotationPacket(
                orientation.displayYaw,
                false,
                orientation.displayPitch,
                false,
            ),
        )
    }

    private fun replaceCamera(
        player: Player,
        session: OperatorSession,
        center: Pos,
        orientation: FlightOrientation,
    ) {
        val runtime = session.runtime
        val instance = runtime.entity.instance ?: return
        val oldSpider = runtime.camera
        val freshSpider = spawnSpider(instance, center.withView(orientation.displayYaw, orientation.cameraPitch))
        val oldViewmodel = session.viewmodel
        val oldPayloadViewmodel = session.payloadViewmodel
        try {
            freshSpider.setView(orientation.displayYaw, orientation.cameraPitch, orientation.displayYaw)
            runtime.camera = freshSpider
            // spectate the fresh camera before removing the old one
            spectateCamera(player, freshSpider)

            // respawn the viewmodel(s) on the fresh camera in lockstep
            spawnViewmodels(player, session, freshSpider, orientation.displayYaw, orientation.cameraPitch, orientation.inverted)
        } catch (failure: Exception) {
            runtime.camera = oldSpider
            runCatching { spectateCamera(player, oldSpider) }.onFailure(failure::addSuppressed)
            runCatching { freshSpider.remove() }.onFailure(failure::addSuppressed)
            throw failure
        }
        oldViewmodel?.remove()
        oldPayloadViewmodel?.remove()
        oldSpider?.remove()
    }

    private fun updatePilotHud(
        player: Player,
        entity: Entity,
        speed: Double,
        battery: Float,
        distance: Double,
        inverted: Boolean,
    ) {
        entity.instance?.let {
            sendTelemetry(player, it, speed, battery, distance, inverted)
        }

        // fill the hotbar with sculk veins using the pack's invisible item model
        for (slot in PILOT_HOTBAR_SLOTS) {
            player.sendPacket(
                SetPlayerInventorySlotPacket(
                    slot,
                    ItemStack.of(Material.SCULK_VEIN).withItemModel("aechronis:invisible").withCustomName(Component.empty()),
                ),
            )
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

    private fun startCrashStatic(
        player: Player,
        camera: LivingEntity,
    ) {
        clearCrashStatic(player)
        camera.setAutoViewable(false)
        camera.addViewer(player)
        val session = CrashStaticSession(camera)
        crashSessions[player] = session
        ModelManager.setCustomView(player, true)

        player.spectate(camera)
        player.instance?.let { instance ->
            player.sendPacket(SetTimePacket(CRASH_STATIC_TIME, instance.createTimePacket().clocks))
        }

        session.task =
            ModuleScheduler
                .buildTask { finishCrashStatic(player, session) }
                .delay(TaskSchedule.seconds(CRASH_STATIC_SECONDS))
                .schedule()
    }

    // wraps an angle in degrees to the range (-180, 180]
    private fun wrapDegrees(deg: Float): Float {
        var d = deg % 360f
        if (d <= -180f) d += 360f
        if (d > 180f) d -= 360f
        return d
    }

    internal fun adjustThrottle(
        player: Player,
        delta: Int,
    ) {
        val session = operators[player] ?: return
        session.throttle = (session.throttle + delta * -10F).coerceIn(0F, 100F)
    }

    internal fun shutdownRuntimeState() {
        val players = operators.keys.toList()
        val failures = ArrayList<Throwable>()

        fun cleanup(action: () -> Unit) {
            runCatching(action).onFailure(failures::add)
        }
        players.forEach { player -> cleanup { onExit(player) } }
        runtimes.keys.toList().forEach { entity -> cleanup { removeRuntimeEntity(entity) } }
        players.forEach { player -> cleanup { resetShutdownView(player) } }
        failures.firstOrNull()?.let { failure ->
            failures.drop(1).forEach(failure::addSuppressed)
            throw failure
        }
    }

    companion object {
        private class CrashStaticSession(
            val camera: LivingEntity,
            var task: Task? = null,
        )

        private val mannequinPilot = HashMap<LivingEntity, Player>()
        private val crashSessions = ConcurrentHashMap<Player, CrashStaticSession>()

        internal fun operatorFor(mannequin: Entity): Player? = mannequinPilot[mannequin]

        internal fun hasCrashStatic(player: Player): Boolean = crashSessions.containsKey(player)

        internal fun clearCrashStatic(
            player: Player,
            resetCamera: Boolean = true,
        ) {
            val session = crashSessions.remove(player)
            session?.task?.cancel()
            ModelManager.setCustomView(player, false)
            if (session == null) return
            if (resetCamera && player.isOnline) player.stopSpectating()
            session.camera.remove()
        }

        internal fun shutdownCrashStatics() {
            val failures = ArrayList<Throwable>()
            for (player in crashSessions.keys.toList()) {
                runCatching { clearCrashStatic(player) }.onFailure(failures::add)
                runCatching { resetShutdownView(player) }.onFailure(failures::add)
            }
            failures.firstOrNull()?.let { failure ->
                failures.drop(1).forEach(failure::addSuppressed)
                throw failure
            }
        }

        private fun resetShutdownView(player: Player) {
            ModelManager.setCustomView(player, false)
            if (!player.isOnline) return
            player.stopSpectating()
            for (slot in PILOT_HOTBAR_SLOTS) {
                player.sendPacket(SetPlayerInventorySlotPacket(slot, player.inventory.getItemStack(slot)))
            }
            player.instance?.let { instance ->
                player.sendPacket(SetTimePacket(10000, instance.createTimePacket().clocks))
            }
        }

        private fun finishCrashStatic(
            player: Player,
            session: CrashStaticSession,
        ) {
            if (!crashSessions.remove(player, session)) return
            ModelManager.setCustomView(player, false)
            if (player.isOnline) player.stopSpectating()
            session.camera.remove()
        }

        const val BATTERY_IDLE_FRACTION = 0.25f

        const val BUZZ_THROTTLE_PITCH_GAIN = 0.6f

        const val TELEMETRY_TOP_SPEED = 1.0
        const val COLLISION_STEP = 0.3

        const val CRASH_STATIC_TIME = 10000L
        const val CRASH_STATIC_SECONDS = 2L

        private val PILOT_HOTBAR_SLOTS = 0..8

        const val VIEWMODEL_FORWARD = -0.1 // blocks ahead of the camera
        const val VIEWMODEL_DOWN = -.35 // blocks below centre
        const val VIEWMODEL_PAYLOAD_FORWARD = 0.1
        const val VIEWMODEL_PAYLOAD_DOWN = 0.2
        val VIEWMODEL_SCALE = 2.0 // model scale
        const val VIEWMODEL_INTERPOLATION = 3
    }
}
