package net.aechronis.crates

import net.aechronis.crates.objects.Crate
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.Entity
import net.minestom.server.entity.EntityType
import net.minestom.server.entity.metadata.display.AbstractDisplayMeta
import net.minestom.server.entity.metadata.display.TextDisplayMeta
import java.util.UUID

/** Floating name tags above every placed crate block. Rebuilt from scratch whenever crates change. */
object CrateHolograms {
    private val displays = mutableListOf<Entity>()

    fun refresh(crates: Collection<Crate>) {
        clear()
        for (crate in crates) {
            val text =
                Component
                    .text(crate.title, NamedTextColor.GOLD, TextDecoration.BOLD)
                    .appendNewline()
                    .append(Component.text("Right click to open • Left click to preview", NamedTextColor.GRAY))
            for (block in crate.blocks) {
                val instance = MinecraftServer.getInstanceManager().getInstance(UUID.fromString(block.world)) ?: continue
                val display = Entity(EntityType.TEXT_DISPLAY)
                display.setNoGravity(true)
                (display.entityMeta as TextDisplayMeta).apply {
                    this.text = text
                    billboardRenderConstraints = AbstractDisplayMeta.BillboardConstraints.CENTER
                    isShadow = true
                }
                display.setInstance(instance, Pos(block.x + 0.5, block.y + 1.15, block.z + 0.5))
                displays += display
            }
        }
    }

    fun clear() {
        displays.forEach(Entity::remove)
        displays.clear()
    }
}
