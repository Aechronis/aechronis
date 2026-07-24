package net.aechronis.combat.objects

import net.aechronis.combat.utils.Ray
import net.kyori.adventure.text.Component
import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.Entity
import net.minestom.server.entity.EntityType
import net.minestom.server.entity.Player
import net.minestom.server.entity.metadata.display.ItemDisplayMeta
import net.minestom.server.instance.Instance
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material

class Projectile(
    val instance: Instance,
    val pos: Pos,
    val model: String,
    val direction: Vec,
    val speed: Double = 1.0,
    val explosionRadius: Int = 4,
    val explosionFire: Double = .33,
    val gravity: Double = 0.05,
    val explosionDamage: Float = 20f,
    val source: Player? = null,
    val weapon: Component? = null,
) {
    private val entity: Entity
    private var velocity: Vec = direction.mul(speed)
    var isActive = true

    init {
        val itemDisplay = Entity(EntityType.ITEM_DISPLAY)

        itemDisplay.setInstance(instance, pos.withDirection(velocity))

        val meta = itemDisplay.entityMeta as ItemDisplayMeta

        meta.itemStack = ItemStack.of(Material.BONE).withItemModel(model)

        meta.isHasNoGravity = true

        itemDisplay.spawn()

        this.entity = itemDisplay

        activeProjectiles.add(this)
    }

    fun onTick() {
        if (!isActive) return

        // accelerate downward so the projectile arcs over time
        velocity = velocity.sub(0.0, gravity, 0.0)

        val currentPos = entity.position
        val nextPos = currentPos.add(velocity)

        val blockHit = Ray(currentPos, velocity).firstBlock(instance)
        if (blockHit != null) {
            Explosion(
                instance = instance,
                pos = blockHit.point.asPos(),
                radius = explosionRadius,
                fire = explosionFire,
                damage = explosionDamage,
                source = source,
                weapon = weapon,
            )
            isActive = false
            entity.remove()
            return
        }

        // chunk is loaded
        if (!instance.isChunkLoaded(nextPos)) {
            isActive = false
            entity.remove()
            return
        }

        // move the entity
        entity.teleport(nextPos.withDirection(velocity))
    }

    companion object {
        val activeProjectiles: MutableList<Projectile> = mutableListOf()
    }
}
