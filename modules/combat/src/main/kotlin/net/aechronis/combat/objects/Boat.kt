package net.aechronis.combat.objects

import net.aechronis.combat.constants.Tags
import net.aechronis.combat.listeners.KeyPressListener
import net.aechronis.combat.utils.Message
import net.aechronis.combat.utils.Ray
import net.aechronis.combat.utils.rotatePoint
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.ShadowColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.title.Title
import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.Entity
import net.minestom.server.entity.Player
import net.minestom.server.instance.Instance
import net.minestom.server.instance.block.Block
import net.minestom.server.particle.Particle
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.floor

/** A boat may carry independently operated gun stations; its helmsman only steers. */
open class Boat(
    name: String,
    itemName: Component,
    itemLore: List<Component> = emptyList(),
    itemModel: String = "${Tags.NAMESPACE}:$name",
    model: String = "${Tags.NAMESPACE}:$name",
    scale: Double,
    hitbox: Hitbox,
    health: Health,
    placeTime: Long = 1000,
    maxSpeed: Float = 0.4f,
    acceleration: Float = 0.02f,
    braking: Float = 0.04f,
    friction: Float = 0.98f,
    turnSpeed: Float = 4.0f,
    maxClimbHeight: Float = 0.5f,
    seatOffsets: List<Vec> = listOf(Vec.ZERO),
    invisibleWhileRiding: Boolean = true,
    invulnerableWhileRiding: Boolean = true,
    val floatHeight: Double = 0.5,
    animatedParts: List<AnimatedPart> = emptyList(),
    collisionHitbox: ShulkerHitbox = ShulkerHitbox.fromHitbox(hitbox),
    val waterlineOffset: Double? = null,
    val waterHitbox: Hitbox = hitbox,
    modelScale: Vec = Vec(scale),
    val armament: BoatArmament? = null,
) : Car(
        name,
        itemName,
        itemLore,
        itemModel,
        model,
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
        invisibleWhileRiding,
        invulnerableWhileRiding,
        animatedParts + weaponParts(armament?.weapons.orEmpty()),
        collisionHitbox,
        modelScale,
    ) {
    val weapons: List<BoatWeapon> = armament?.weapons.orEmpty()
    override val gunnerSeatOffsets: List<Vec> = this.weapons.map { it.operatorOffset }

    private class MountState {
        var yaw = 0f
        var pitch = 0f
        var nextMuzzle = 0
        var ammo = 1
        var reloadStartedAt: Long? = null
    }

    private class Runtime(
        val mounts: List<MountState>,
    )

    // Direct entity removal must not retain an orphaned hull through its item definition.
    private val runtimes = armament?.let { WeakHashMap<Entity, Runtime>() }

    private val solidHitboxes = hitbox.parts.map { Hitbox(listOf(it)) }

    init {
        require(floatHeight in 0.0..1.0) { "floatHeight must be between 0.0 and 1.0" }
        require(waterlineOffset == null || waterlineOffset.isFinite()) { "Boat waterlineOffset must be finite" }
        require(waterHitbox.parts.isNotEmpty()) { "Boat waterHitbox must contain a hull" }
    }

    override fun spawn(
        instance: Instance,
        pos: Pos,
    ): Entity {
        var placement = pos
        val groundedPosition = pos.add(0.0, hitbox.getGroundOffset(), 0.0)
        val surfaceY = findWaterSurfaceY(instance, pos.x, pos.z, getCurrentSurfaceY(groundedPosition))
        if (surfaceY != null) {
            val floatedPosition = groundedPosition.withY(getVehicleY(surfaceY))
            if (hasWaterFootprint(instance, floatedPosition, surfaceY)) {
                // Vehicle.spawn adds the ground offset. Resolve flotation first so
                // the body, animated parts and collision helpers spawn together.
                placement = floatedPosition.add(0.0, -hitbox.getGroundOffset(), 0.0)
            }
        }
        return super.spawn(instance, placement).also { entity ->
            runtimes?.let { it[entity] = Runtime(weapons.map { MountState() }) }
        }
    }

    override fun canStartMoving(
        instance: Instance,
        position: Pos,
    ): Boolean = isHitboxInWater(instance, position)

    override fun canMoveTo(
        instance: Instance,
        entity: Entity,
        position: Pos,
    ): Boolean = hasSolidClearance(instance, position) && hasVehicleClearance(instance, position, entity)

    override fun canPlaceAt(
        instance: Instance,
        pos: Pos,
    ): Boolean {
        val floatedPosition = pos.withY(getVehicleY(pos.y))
        val waterBlockY = floor(pos.y - 1.0).toInt()
        return footprintSamplePoints(floatedPosition).all { (x, z) ->
            loadedBlock(instance, floor(x).toInt(), waterBlockY, floor(z).toInt())?.compare(Block.WATER) == true
        } &&
            hasSolidClearance(instance, floatedPosition) &&
            hasVehicleClearance(instance, floatedPosition)
    }

    override fun findSurfaceY(
        instance: Instance,
        position: Pos,
        currentSurfaceY: Double,
    ): Double? {
        val surfaceY = findWaterSurfaceY(instance, position.x, position.z, currentSurfaceY) ?: return null
        val floatedPosition = position.withY(getVehicleY(surfaceY))
        return if (hasWaterFootprint(instance, floatedPosition, surfaceY)) surfaceY else null
    }

    override fun getCurrentSurfaceY(position: Pos): Double = position.y + resolvedWaterlineOffset()

    override fun getVehicleY(surfaceY: Double): Double = surfaceY - resolvedWaterlineOffset()

    private fun resolvedWaterlineOffset(): Double {
        waterlineOffset?.let { return it }
        val bottomOffset = waterHitbox.getBottomOffset()
        val topOffset = waterHitbox.getTopOffset()
        return topOffset - (topOffset - bottomOffset) * floatHeight
    }

    private fun isHitboxInWater(
        instance: Instance,
        position: Pos,
    ): Boolean {
        val currentSurfaceY = getCurrentSurfaceY(position)
        val waterSurfaceY = findWaterSurfaceY(instance, position.x, position.z, currentSurfaceY) ?: return false
        val bottomY = position.y + waterHitbox.getBottomOffset()
        return waterSurfaceY >= bottomY && hasWaterFootprint(instance, position, currentSurfaceY)
    }

    private fun hasWaterFootprint(
        instance: Instance,
        position: Pos,
        currentSurfaceY: Double,
    ): Boolean =
        footprintSamplePoints(position).all { (x, z) ->
            findWaterSurfaceY(instance, x, z, currentSurfaceY) != null
        }

    private fun footprintSamplePoints(position: Pos): List<Pair<Double, Double>> =
        waterHitbox
            .getWorldCorners(position, position.yaw, position.pitch, 0f)
            .flatten()
            .map { point -> point.x to point.z }
            .plus(position.x to position.z)
            .distinct()

    private fun findWaterSurfaceY(
        instance: Instance,
        x: Double,
        z: Double,
        currentSurfaceY: Double,
    ): Double? {
        val dimension = instance.cachedDimensionType
        val startY = floor(currentSurfaceY + maxClimbHeight + 1).toInt().coerceAtMost(dimension.maxY() - 1)
        val endY = floor(currentSurfaceY - 10).toInt().coerceAtLeast(dimension.minY())

        for (y in startY downTo endY) {
            val block = loadedBlock(instance, floor(x).toInt(), y, floor(z).toInt()) ?: return null
            if (block.compare(Block.WATER)) return (y + 1).toDouble()
            if (block.isSolid) return null
        }

        return null
    }

    private fun hasVehicleClearance(
        instance: Instance,
        position: Pos,
        excludedEntity: Entity? = null,
    ): Boolean =
        VehicleRegistry.all().none { runtime ->
            val other = runtime.entity
            other !== excludedEntity &&
                other.instance === instance &&
                hitbox.intersects(
                    runtime.vehicle.hitbox,
                    position,
                    position.yaw,
                    position.pitch,
                    0f,
                    other.position,
                    other.position.yaw,
                    other.position.pitch,
                    runtime.vehicle.hitboxRoll(other),
                )
        }

    /** Check the whole hull and superstructure, including obstacles between the corners. */
    private fun hasSolidClearance(
        instance: Instance,
        position: Pos,
    ): Boolean {
        val dimension = instance.cachedDimensionType
        val corners = hitbox.getWorldCorners(position, position.yaw, position.pitch, 0f)
        for ((index, partCorners) in corners.withIndex()) {
            val minY = partCorners.minOf { it.y }
            val maxY = partCorners.maxOf { it.y }
            if (minY < dimension.minY() || maxY > dimension.maxY()) return false
            val minX = floor(partCorners.minOf { it.x } + CLEARANCE_EPSILON).toInt()
            val maxX = floor(partCorners.maxOf { it.x } - CLEARANCE_EPSILON).toInt()
            val minZ = floor(partCorners.minOf { it.z } + CLEARANCE_EPSILON).toInt()
            val maxZ = floor(partCorners.maxOf { it.z } - CLEARANCE_EPSILON).toInt()
            for (x in minX..maxX) {
                for (z in minZ..maxZ) {
                    for (y in floor(minY + CLEARANCE_EPSILON).toInt()..floor(maxY - CLEARANCE_EPSILON).toInt()) {
                        val block = loadedBlock(instance, x, y, z) ?: return false
                        if (block.isSolid &&
                            solidHitboxes[index].intersects(
                                BLOCK_HITBOX,
                                position,
                                position.yaw,
                                position.pitch,
                                0f,
                                Pos(x + 0.5, y + 0.5, z + 0.5),
                                0f,
                                0f,
                                0f,
                            )
                        ) {
                            return false
                        }
                    }
                }
            }
        }
        return true
    }

    private fun loadedBlock(
        instance: Instance,
        x: Int,
        y: Int,
        z: Int,
    ): Block? {
        val dimension = instance.cachedDimensionType
        if (y < dimension.minY() || y >= dimension.maxY()) return null
        val chunk = instance.getChunk(Math.floorDiv(x, 16), Math.floorDiv(z, 16)) ?: return null
        chunk.lockReadLock()
        return try {
            if (chunk.isLoaded) chunk.getBlock(x, y, z) else null
        } finally {
            chunk.unlockReadLock()
        }
    }

    override fun onGunnerEnter(
        player: Player,
        entity: Entity,
        stationIndex: Int,
    ) {
        val weapon = weapons.getOrNull(stationIndex) ?: return
        VehicleRegistry.gunnerOf(entity, stationIndex)?.let { Vehicle.reconcileOccupant(it.player) }
        if (VehicleRegistry.gunnerOf(entity, stationIndex) != null) {
            player.sendMessage(Component.text("${weapon.name} is occupied.", NamedTextColor.RED))
            return
        }
        if (!canEnterAsGunner(player, entity, stationIndex)) return
        super.onGunnerEnter(player, entity, stationIndex)
        val ride = VehicleRegistry.gunner(player)
        if (ride == null || ride.entity !== entity || ride.vehicle !== this || ride.stationIndex != stationIndex) {
            return
        }
    }

    override fun onGunnerExit(player: Player) {
        val ride = VehicleRegistry.gunner(player)?.takeIf { it.vehicle === this }
        ride?.stationIndex?.let { index ->
            runtimes
                ?.get(ride.entity)
                ?.mounts
                ?.getOrNull(index)
                ?.reloadStartedAt = null
        }
        if (ride != null) player.clearTitle()
        super.onGunnerExit(player)
    }

    override fun onGunnerTick(player: Player) {
        val ride = VehicleRegistry.gunner(player)?.takeIf { it.vehicle === this } ?: return
        val index = ride.stationIndex ?: return
        super.onGunnerTick(player)
        if (VehicleRegistry.gunner(player) !== ride) return
        val body = ride.entity
        val runtime = runtimes?.get(body) ?: return
        val weapon = weapons[index]
        val state = runtime.mounts[index]
        updateWeaponReload(player)
        val relativeYaw = angleDifference(body.position.yaw, player.position.yaw)
        val aim = angleDifference(weapon.neutralYaw, relativeYaw)
        val target = aim.coerceIn(-weapon.maxYaw, weapon.maxYaw)
        val delta = if (weapon.maxYaw == 180f) angleDifference(state.yaw, target) else target - state.yaw
        state.yaw += delta.coerceIn(-weapon.traverseSpeed, weapon.traverseSpeed)
        if (weapon.maxYaw == 180f) state.yaw = angleDifference(0f, state.yaw)
        state.pitch = player.position.pitch.coerceIn(weapon.minPitch, weapon.maxPitch)
        val canFire = abs(aim) <= weapon.maxYaw && abs(target - state.yaw) <= 3f
        if (KeyPressListener.playerInputEvent[player]?.isHoldingJumpKey == true && canFire) {
            fire(player, body, runtime, index)
        }
    }

    /** Projectile origin and direction, including the alternating barrel. */
    internal fun firingPose(
        entity: Entity,
        index: Int,
    ): Pos? {
        val state = runtimes?.get(entity)?.mounts?.getOrNull(index) ?: return null
        val weapon = weapons[index]
        val body = entity.position
        val origin = body.add(rotatePoint(weapon.pivotOffset, body.yaw, 0f, 0f))
        val tip = rotatePoint(weapon.muzzleOffsets[state.nextMuzzle], body.yaw + state.yaw, 0f, 0f)
        return origin.add(tip).withView(body.yaw + weapon.neutralYaw + state.yaw, state.pitch)
    }

    internal fun weaponStatus(player: Player): Pair<BoatWeapon, Int>? {
        val ride = VehicleRegistry.gunner(player)?.takeIf { it.vehicle === this } ?: return null
        val index = ride.stationIndex ?: return null
        val mount = runtimes?.get(ride.entity)?.mounts?.getOrNull(index) ?: return null
        return weapons[index] to mount.ammo
    }

    internal fun weaponInteractionTargets(entity: Entity): List<WeaponInteractionTarget> =
        weapons.mapIndexed { index, weapon ->
            val pose = entity.position
            val yaw =
                runtimes
                    ?.get(entity)
                    ?.mounts
                    ?.get(index)
                    ?.yaw ?: 0f
            WeaponInteractionTarget(
                index,
                pose.add(rotatePoint(weapon.pivotOffset, pose.yaw, 0f, 0f)).withYaw(pose.yaw + yaw),
                weapon.interactionHitbox,
            )
        }

    internal fun updateWeaponReload(
        player: Player,
        now: Long = System.currentTimeMillis(),
    ) {
        val armament = armament ?: return
        val ammo = armament.ammo
        val ride = VehicleRegistry.gunner(player)?.takeIf { it.vehicle === this } ?: return
        val index = ride.stationIndex ?: return
        val mount = runtimes?.get(ride.entity)?.mounts?.getOrNull(index) ?: return
        if (mount.ammo > 0) return
        if (ammo[player] == 0) {
            if (mount.reloadStartedAt != null) player.clearTitle()
            mount.reloadStartedAt = null
            return
        }
        val startedAt = mount.reloadStartedAt ?: now.also { mount.reloadStartedAt = it }
        val elapsed = now - startedAt
        val duration = weapons[index].reloadTime
        if (elapsed >= duration) {
            ammo[player] -= 1
            mount.ammo = armament.maxAmmo
            mount.reloadStartedAt = null
            ride.clearEmptyAmmoFeedback()
            player.clearTitle()
            return
        }
        player.showTitle(
            Title.title(
                Component.empty(),
                Message.progressBar((elapsed.toDouble() / duration).coerceIn(0.0, 1.0)).shadowColor(ShadowColor.none()),
                0,
                3,
                10,
            ),
        )
    }

    private fun fire(
        player: Player,
        body: Entity,
        runtime: Runtime,
        index: Int,
    ) {
        val ammo = armament?.ammo ?: return
        val instance = body.instance ?: return
        val weapon = weapons[index]
        val mount = runtime.mounts[index]
        if (mount.reloadStartedAt != null) return
        if (mount.ammo <= 0) {
            if (ammo[player] == 0 && VehicleRegistry.gunner(player)?.canReportEmptyAmmo(System.currentTimeMillis()) == true) {
                player.showTitle(
                    Title.title(
                        Component.empty(),
                        Component.text("✕").color(TextColor.color(0.5F, 0F, 0F)).shadowColor(ShadowColor.none()),
                        0,
                        10,
                        10,
                    ),
                )
            }
            return
        }
        val origin = body.position.add(rotatePoint(weapon.pivotOffset, body.position.yaw, 0f, 0f))
        // The saved component has baked elevation. Only traverse rotates its
        // muzzle location; projectile elevation follows this gunner's bounded aim.
        val tip = rotatePoint(weapon.muzzleOffsets[mount.nextMuzzle], body.position.yaw + mount.yaw, 0f, 0f)
        val muzzle = firingPose(body, index) ?: return
        val ignored =
            buildSet<Entity> {
                add(player)
                addAll(VehicleRegistry.ridesOf(body).map { it.player })
            }
        // The breech is inside its own mounting. Only the barrel-clearance ray
        // skips the hull; shells leaving the muzzle can hit their own ship.
        val obstruction = firstProjectileImpact(Ray(origin, tip), instance, ignored + body)
        // Reserve the shot before an immediate blast can destroy this vehicle.
        mount.ammo -= 1
        if (ammo[player] > 0) mount.reloadStartedAt = System.currentTimeMillis()
        mount.nextMuzzle = (mount.nextMuzzle + 1) % weapon.muzzleOffsets.size
        if (obstruction != null) {
            Explosion.bypassingDamageImmunity(
                instance = instance,
                pos = obstruction.point.asPos(),
                radius = weapon.projectileExplosionRadius,
                fire = 0.0,
                damage = weapon.projectileExplosionDamage,
                source = player,
                weapon = itemName,
                ammoType = ammo.ammoType,
            )
        } else {
            Projectile.bypassingDamageImmunity(
                instance = instance,
                pos = muzzle,
                model = ammo.itemModel,
                direction = muzzle.direction(),
                speed = weapon.projectileSpeed,
                explosionRadius = weapon.projectileExplosionRadius,
                explosionFire = 0.0,
                explosionDamage = weapon.projectileExplosionDamage,
                source = player,
                weapon = itemName,
                ignoredEntities = ignored,
                ammoType = ammo.ammoType,
                trailParticle = Particle.SMOKE,
                maxRange = weapon.projectileMaxRange,
            )
        }
    }

    private fun weaponPose(
        entity: Entity,
        index: Int,
    ): AnimatedPart.Pose =
        AnimatedPart.Pose(
            axis = Vec(0.0, 1.0, 0.0),
            angle =
                -Math.toRadians(
                    (
                        runtimes
                            ?.get(entity)
                            ?.mounts
                            ?.get(index)
                            ?.yaw ?: 0f
                    ).toDouble(),
                ),
        )

    override fun destroy(
        entity: Entity,
        attacker: Player?,
        weapon: Component?,
    ) {
        cleanupRuntime(entity)
        super.destroy(entity, attacker, weapon)
    }

    override fun cleanupRuntime(entity: Entity) {
        runtimes?.remove(entity)
        super.cleanupRuntime(entity)
    }

    companion object {
        private const val CLEARANCE_EPSILON = 1.0e-7
        private val BLOCK_HITBOX = Hitbox(listOf(HitboxPart(Vec.ZERO, Vec(0.5))))

        private fun weaponParts(weapons: List<BoatWeapon>): List<AnimatedPart> =
            weapons.mapIndexed { index, weapon ->
                AnimatedPart(
                    model = weapon.model,
                    offset = weapon.pivotOffset,
                    motion = AnimatedPart.Motion { context, _ -> (context.vehicle as Boat).weaponPose(context.entity, index) },
                    initialPose = AnimatedPart.Pose(axis = Vec(0.0, 1.0, 0.0)),
                )
            }

        private fun angleDifference(
            current: Float,
            target: Float,
        ): Float = ((target - current + 180f) % 360f + 360f) % 360f - 180f
    }
}
