package net.aechronis.combat.objects

import net.aechronis.combat.Combat
import net.aechronis.combat.constants.Tags
import net.aechronis.combat.utils.Mounts
import net.kyori.adventure.text.Component
import net.minestom.server.MinecraftServer
import net.minestom.server.component.DataComponents
import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.Entity
import net.minestom.server.entity.LivingEntity
import net.minestom.server.entity.Player
import net.minestom.server.item.ItemAnimation
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import net.minestom.server.item.component.Consumable
import net.minestom.server.item.component.UseCooldown
import net.minestom.server.item.component.UseEffects
import net.minestom.server.network.packet.server.play.SetCooldownPacket
import net.minestom.server.sound.SoundEvent

// The client only reports letting go of right click while it believes an item is in use.
private val HOLD_TO_THROW_USE = Consumable(3600f, ItemAnimation.TRIDENT, SoundEvent.INTENTIONALLY_EMPTY, false, emptyList())
private val HOLD_TO_THROW_EFFECTS = UseEffects(true, true, 1f)

private const val MAX_AIMED_THROW_TICKS = 200
private const val AIMED_THROW_REFINEMENTS = 16

/** An item readied by holding right click and thrown by letting go. */
class Throwable(
    name: String,
    itemName: Component,
    itemLore: List<Component> = emptyList(),
    itemModel: String = "${Tags.NAMESPACE}:$name",
    material: Material = Material.FIREWORK_STAR,
    val throwSpeed: Double = 1.0,
    val gravity: Double = 0.05,
    val projectileModel: String = itemModel,
    /** A throwable with a cooldown stays in hand when thrown instead of being used up. */
    val throwCooldownMillis: Long? = null,
    private val onImpact: (ThrowableImpact) -> Unit,
) : Item(name, itemName, itemLore, itemModel, material) {
    private val cooldownGroup = "${Tags.NAMESPACE}:$name"

    /** The farthest an aimed throw can land on level ground. */
    val maxAimedRange: Double
        get() {
            val tickLimit = throwSpeed * MAX_AIMED_THROW_TICKS
            return if (gravity > 0.0) minOf(throwSpeed * throwSpeed / gravity, tickLimit) else tickLimit
        }

    init {
        require(throwSpeed.isFinite() && throwSpeed > 0.0) { "Throwable throwSpeed must be positive and finite" }
        require(gravity.isFinite() && gravity >= 0.0) { "Throwable gravity must be non-negative and finite" }
        require(throwCooldownMillis == null || throwCooldownMillis > 0) { "Throwable throwCooldownMillis must be positive" }
    }

    override fun toItemStack(): ItemStack = withHoldToThrow(super.toItemStack()).withMaxStackSize(1)

    internal fun withHoldToThrow(itemStack: ItemStack): ItemStack {
        val holdable =
            itemStack
                .with(DataComponents.CONSUMABLE, HOLD_TO_THROW_USE)
                .with(DataComponents.USE_EFFECTS, HOLD_TO_THROW_EFFECTS)
        val cooldown = throwCooldownMillis ?: return holdable
        return holdable.with(DataComponents.USE_COOLDOWN, UseCooldown(cooldown / 1000f, cooldownGroup))
    }

    fun hold(player: Player): Boolean {
        // A blocked hotbar scroll interrupts item use on the client, which then starts it again.
        Combat.playerStates[player]?.heldThrowable?.let { return it === this }
        if (isOnCooldown(player)) return false
        if (Mounts.isMounted(player) || Vehicle.isVehicleOccupant(player)) {
            Mounts.showBlocked(player)
            return false
        }
        if (Item.getFromItemStack(player.itemInMainHand) !== this) return false

        Combat.playerStates.getOrCreate(player).heldThrowable = this
        return true
    }

    fun release(player: Player) {
        val state = Combat.playerStates[player] ?: return
        if (state.heldThrowable !== this) return
        state.clearThrowable()

        // Switching the item while held cannot create or duplicate a throwable.
        if (Item.getFromItemStack(player.itemInMainHand) !== this) return

        val cooldown = throwCooldownMillis
        if (cooldown == null) consumeOne(player) else startCooldown(player, cooldown)
        val origin = player.position.add(0.0, player.eyeHeight, 0.0)
        val direction = origin.direction()
        val instance = player.instance
        Projectile(
            instance = instance,
            pos = origin.add(direction.mul(0.35)),
            model = projectileModel,
            direction = direction,
            speed = throwSpeed,
            gravity = gravity,
            source = player,
            weapon = itemName,
            onImpact = { target, point -> onImpact(ThrowableImpact(this, player, instance, target, point)) },
        )
    }

    /**
     * Throws from an AI-controlled [thrower] on the low arc reaching [targetPosition], returning
     * false when it is out of reach. Only [validTargets] and their vehicles can be hit.
     */
    fun throwFromEntity(
        thrower: LivingEntity,
        targetPosition: Pos,
        validTargets: Collection<LivingEntity>,
    ): Boolean {
        if (thrower.isDead || thrower.isRemoved) return false
        val instance = thrower.instance ?: return false
        val origin = thrower.position.add(0.0, thrower.eyeHeight, 0.0)
        val direction = aimedThrowDirection(targetPosition.sub(origin).asVec(), throwSpeed, gravity) ?: return false

        val targets = HashSet<Entity>(validTargets)
        validTargets.filterIsInstance<Player>().mapNotNullTo(targets) { VehicleRegistry.ride(it)?.entity }
        val ignored =
            (instance.entities.filterIsInstance<LivingEntity>() + VehicleRegistry.all().map { it.entity })
                .filterNotTo(hashSetOf<Entity>()) { it in targets }

        thrower.swingMainHand()
        Projectile.ignoring(
            instance = instance,
            pos = origin.add(direction.mul(0.35)),
            model = projectileModel,
            direction = direction,
            speed = throwSpeed,
            gravity = gravity,
            weapon = itemName,
            ignoredEntities = ignored,
            onImpact = { target, point -> onImpact(ThrowableImpact(this, thrower, instance, target, point, targets)) },
        )
        return true
    }

    fun onHolderDeath(player: Player) {
        stopHolding(player)
    }

    private fun isOnCooldown(player: Player): Boolean {
        val readyAt = Combat.playerStates[player]?.throwableCooldowns?.get(name) ?: return false
        return System.currentTimeMillis() < readyAt
    }

    private fun startCooldown(
        player: Player,
        cooldownMillis: Long,
    ) {
        Combat.playerStates.getOrCreate(player).throwableCooldowns[name] = System.currentTimeMillis() + cooldownMillis
        player.sendPacket(SetCooldownPacket(cooldownGroup, (cooldownMillis / MinecraftServer.TICK_MS).toInt()))
    }

    private fun consumeOne(player: Player) {
        val item = player.itemInMainHand
        player.itemInMainHand =
            if (item.amount() <= 1) {
                ItemStack.AIR
            } else {
                item.withAmount(item.amount() - 1)
            }
    }

    /** Ends a hold the client did not release; a release resets item use itself. */
    private fun stopHolding(player: Player) {
        Combat.playerStates[player]?.clearThrowable()
        player.refreshActiveHand(false, false, false)
        player.clearItemUse()
    }
}

/** The launch direction whose low arc reaches [offset] under per-tick [gravity], or null when out of reach. */
internal fun aimedThrowDirection(
    offset: Vec,
    speed: Double,
    gravity: Double,
): Vec? {
    if (offset.isZero) return null

    // A projectile n ticks after launch has moved n * velocity - gravity * n * (n + 1) / 2.
    fun launchVelocity(ticks: Double) = Vec(offset.x() / ticks, offset.y() / ticks + gravity * (ticks + 1) / 2, offset.z() / ticks)

    val arrival = (1..MAX_AIMED_THROW_TICKS).firstOrNull { launchVelocity(it.toDouble()).length() <= speed } ?: return null
    var tooFast = (arrival - 1).toDouble()
    var reachable = arrival.toDouble()
    repeat(AIMED_THROW_REFINEMENTS) {
        val middle = (tooFast + reachable) / 2
        if (launchVelocity(middle).length() <= speed) reachable = middle else tooFast = middle
    }
    return launchVelocity(reachable).normalize()
}
