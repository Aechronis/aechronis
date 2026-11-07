package net.aechronis.combat.objects

import net.aechronis.combat.storage.HatCollection
import net.minestom.server.collision.BoundingBox
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.Entity
import net.minestom.server.entity.EntityType
import net.minestom.server.entity.EquipmentSlot
import net.minestom.server.entity.LivingEntity
import net.minestom.server.entity.Player
import net.minestom.server.entity.attribute.Attribute
import net.minestom.server.entity.metadata.avatar.MannequinMeta
import net.minestom.server.instance.Instance
import net.minestom.server.item.ItemStack
import net.minestom.server.network.packet.server.play.EntityEquipmentPacket
import net.minestom.server.network.player.ResolvableProfile
import kotlin.math.cos
import kotlin.math.sin

/**
 * The standing figure shown in place of a seated, invisible crew member. Observers see [body]
 * at the crew position. The crew member's own camera is at that body's eyes, where a player
 * model would fill the view from inside its head, so they see a private copy kept behind
 * their camera instead: out of sight in first person, standing in front of the F5 camera.
 *
 * While spawned, [owner]'s own hitbox is empty and [body] takes hits on their behalf, so they
 * stay hittable where others see them even while their camera is at a weapon sight. The private
 * copy is not a LivingEntity, so nothing can hit it.
 */
internal class CrewStandIn(
    val owner: Player,
) {
    private class Body(
        owner: Player,
    ) : LivingEntity(EntityType.MANNEQUIN) {
        init {
            dress(this, owner)
            armor(owner).forEach { (slot, item) -> setEquipment(slot, item) }
            // Damage passes to the owner; the figure itself must stay at the crew station.
            getAttribute(Attribute.KNOCKBACK_RESISTANCE).baseValue = 1.0
        }
    }

    private class SelfView(
        owner: Player,
    ) : Entity(EntityType.MANNEQUIN) {
        private val equipment = armor(owner)

        init {
            dress(this, owner)
        }

        override fun updateNewViewer(player: Player) {
            super.updateNewViewer(player)
            player.sendPacket(EntityEquipmentPacket(entityId, equipment))
        }
    }

    val body: LivingEntity = Body(owner)
    private val selfView: Entity = SelfView(owner)
    private val ownerBounds = owner.boundingBox

    fun isBody(entity: Entity): Boolean = entity === body

    fun spawn(
        instance: Instance,
        position: Pos,
    ) {
        body.updateViewableRule { it !== owner }
        selfView.isAutoViewable = false
        selfView.updateViewableRule { it === owner }
        body.setInstance(instance, position)
        selfView.setInstance(instance, selfViewPosition(position))
        selfView.addViewer(owner)
        owner.boundingBox = BoundingBox(0.0, 0.0, 0.0)
    }

    /** [position] is the crew position, carrying the crew member's view. */
    fun follow(position: Pos) {
        move(body, position)
        move(selfView, selfViewPosition(position))
    }

    fun remove() {
        try {
            if (!body.isRemoved) body.remove()
            if (!selfView.isRemoved) selfView.remove()
        } finally {
            owner.boundingBox = ownerBounds
        }
    }

    private fun move(
        figure: Entity,
        position: Pos,
    ) {
        if (figure.position != position) figure.teleport(position)
    }

    // A level head reaches about 0.28 blocks around the eye. The extra margin keeps it clear
    // of the camera while the client interpolates the copy through ordinary turns.
    private fun selfViewPosition(position: Pos): Pos {
        val yaw = Math.toRadians(position.yaw.toDouble())
        return Pos(
            position.x + sin(yaw) * SELF_VIEW_OFFSET,
            position.y,
            position.z - cos(yaw) * SELF_VIEW_OFFSET,
            position.yaw,
            0f,
        )
    }

    private companion object {
        const val SELF_VIEW_OFFSET = 0.5

        fun dress(
            figure: Entity,
            owner: Player,
        ) {
            figure.setNoGravity(true)
            figure.setHasPhysics(false)
            figure.editEntityMeta(MannequinMeta::class.java) { meta ->
                // Players without skin textures (offline logins) keep the default skin derived from their UUID.
                val skin = owner.skin
                val defaultSkin = ResolvableProfile.Partial(null, owner.uuid, emptyList())
                meta.profile = if (skin != null) ResolvableProfile(skin) else ResolvableProfile(defaultSkin)
                // Hide the default "NPC" label under the figure.
                meta.description = null
                meta.isImmovable = true
                meta.displayedSkinParts = owner.settings.displayedSkinParts
                meta.mainHand = owner.settings.mainHand
            }
        }

        fun armor(owner: Player): Map<EquipmentSlot, ItemStack> {
            val hat = HatCollection.equipped(owner.uuid)?.appearance
            return EquipmentSlot.armors().associateWith { slot ->
                if (slot == EquipmentSlot.HELMET && hat != null) hat else owner.getEquipment(slot)
            }
        }
    }
}
