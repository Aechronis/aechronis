package luna.nodes.combat.objects

import luna.nodes.combat.Combat
import luna.nodes.combat.constants.Tags
import luna.nodes.combat.utils.Message
import luna.nodes.combat.utils.Particles
import luna.nodes.combat.utils.Ray
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
import net.minestom.server.entity.LivingEntity
import net.minestom.server.entity.Player
import net.minestom.server.entity.RelativeFlags
import net.minestom.server.entity.damage.DamageType
import net.minestom.server.item.Material
import net.minestom.server.timer.TaskSchedule
import kotlin.random.Random

class Gun(
    name: String,
    itemName: Component,
    itemLore: List<Component> = emptyList(),
    itemModel: String = "${Tags.namespace}:$name",
    val ammo: Ammo,
    val maxAmmo: Int,
    val damage: Float,
    val automatic: Boolean,
    val cooldown: Long,
    val reloadTime: Long,
    val recoilMin: Float,
    val recoilMax: Float,
    val spreadMin: Float,
    val spreadMax: Float,
    val soundFire: Sound = Sound.sound(Key.key("${Tags.namespace}:$name.fire"), Sound.Source.PLAYER, 1.0f, 1.0f),
    val soundReload: Sound = Sound.sound(Key.key("${Tags.namespace}:$name.reload"), Sound.Source.PLAYER, 1.0f, 1.0f),
    val itemModelEmpty: String = "$itemModel-empty",
    val itemModelReloading: String = "$itemModel-reloading",
    val itemModelAiming: String = "$itemModel-aiming",
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

    private fun damageToAmmo(damage: Int): Int {
        if (damage == 1) return maxAmmo
        if (damage == 99) return 0
        val damagePercent = 100 - damage
        return ((damagePercent.toFloat() / 100) * maxAmmo).toInt()
    }

    private fun ammoToDamage(amount: Int): Int {
        val ammoPercent = (amount * 100) / maxAmmo
        return (100 - ammoPercent).coerceIn(1, 99)
    }

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
                        ammo.take(player, 1)

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
    fun fire(player: Player) {
        if ((Combat.playerCooldowns[player] ?: 0) < cooldown) return
        if (Combat.reloadTasks[player] != null) return
        Combat.playerCooldowns[player] = 0
        if (!hasAmmo(player)) return

        // calculate position to fire bullet (ray) from
        val speed = Combat.playerSpeeds[player] ?: 0F
        val offsetYaw = player.position.yaw + spread(speed)
        val offsetPitch = player.position.pitch + spread(speed)

        val offsetPos =
            player.position
                .withView(offsetYaw, offsetPitch)
                .add(0.0, player.eyeHeight, 0.0)

        // create ray with random offsets generated
        val ray = Ray(offsetPos, offsetPos.direction().mul(player.instance.viewDistance() * 16.0))

        val blockHit = ray.findBlocks(player.instance!!).nextClosest()
        val entityHit = ray.firstEntity(player.instance.entities.filter { it != player })

        val blockHitDistance = blockHit?.t ?: 999.9
        val entityHitDistance = entityHit?.t ?: 999.9

        // determine which is hit first
        if (blockHitDistance == 999.9 && entityHitDistance == 999.9) { // no hit
        } else if (blockHitDistance > entityHitDistance) { // entity hit
            val target = (entityHit!!.obj as LivingEntity)
            if (target as? Player != null) {
                Combat.playerKillers[target] = player
            }

            // ding sound
            player.playSound(Sound.sound(Key.key("entity.experience_orb.pickup"), Sound.Source.PLAYER, 1.0f, 1.0f))

            // blood
            Particles.bloodParticle(player.instance, entityHit.point.asPos())

            target.damage(DamageType.ARROW, damage)
        } else { // block hit
            Particles.dustParticle(player.instance, blockHit!!.point.asPos())
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
        player.teleport(
            Pos(0.0, 0.0, 0.0, 0f, -(Random.nextFloat() * (recoilMax - recoilMin) + recoilMin)),
            null,
            RelativeFlags.ALL,
        )
    }
}
