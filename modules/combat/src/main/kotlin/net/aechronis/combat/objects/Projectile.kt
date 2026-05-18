package net.aechronis.combat.objects

import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.Entity
import net.minestom.server.entity.EntityType
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

        val nextPos = entity.position.add(velocity)

        // chunk is loaded
        if (!instance.isChunkLoaded(nextPos)) {
            isActive = false
            entity.remove()
            return
        }

        // if the next position is inside a solid block
        val block = instance.getBlock(nextPos)
        if (block.isSolid) {
            // explode
            Explosion(instance, nextPos, explosionRadius, explosionFire)
            isActive = false
            entity.remove()
            return
        }

        // move the entity
        entity.teleport(nextPos.withDirection(velocity))
    }

    fun remove() {
        activeProjectiles.remove(this)
        isActive = false
        entity.remove()
    }

    companion object {
        val activeProjectiles: MutableList<Projectile> = mutableListOf()
    }
}
