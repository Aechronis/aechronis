package net.aechronis.combat.objects

import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.Entity
import net.minestom.server.entity.metadata.display.ItemDisplayMeta
import net.minestom.server.instance.Instance
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material

/** Configure an already-owned display so failed setup can use the vehicle's normal runtime cleanup. */
internal fun VehicleDisplayEntity.spawnWeaponDisplay(
    instance: Instance,
    position: Pos,
    model: String,
    scale: Double,
) {
    setInstance(instance, position)
    val meta = entityMeta as ItemDisplayMeta
    meta.itemStack = ItemStack.of(Material.BONE).withItemModel(model)
    meta.posRotInterpolationDuration = 3
    meta.scale = Vec(scale)
    meta.isHasNoGravity = true
    spawn()
}

/** Attempt every owned display even if an earlier removal fails. */
internal fun removeWeaponDisplays(vararg displays: Entity) {
    var failure: Throwable? = null
    for (display in displays) {
        try {
            display.remove()
        } catch (error: Throwable) {
            if (failure == null) {
                failure = error
            } else if (failure !== error) {
                failure.addSuppressed(error)
            }
        }
    }
    failure?.let { throw it }
}
