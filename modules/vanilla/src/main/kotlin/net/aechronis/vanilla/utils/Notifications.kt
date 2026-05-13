package net.aechronis.vanilla.utils

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.advancements.FrameType
import net.minestom.server.advancements.Notification
import net.minestom.server.item.Material

object Notifications {
    val fullInv =
        Notification(
            Component.text("Your inventory is full!").color(NamedTextColor.RED),
            FrameType.TASK,
            Material.BARRIER,
        )
}
