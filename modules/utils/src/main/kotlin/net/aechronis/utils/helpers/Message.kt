package net.aechronis.utils.helpers

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.minestom.server.advancements.FrameType
import net.minestom.server.advancements.Notification
import net.minestom.server.command.CommandSender
import net.minestom.server.entity.Player
import net.minestom.server.item.Material
import kotlin.math.roundToInt

object Message {
    fun printChat(
        sender: CommandSender?,
        s: String,
    ) {
        if (sender === null) {
            println("Message called with null sender: $s")
            return
        }

        val msg = Component.text(s, NamedTextColor.DARK_GREEN)
        sender.sendMessage(msg)
    }

    fun printChat(
        sender: CommandSender?,
        component: Component,
    ) {
        if (sender === null) {
            println("Message called with null sender")
            return
        }

        sender.sendMessage(component.colorIfAbsent(NamedTextColor.DARK_GREEN))
    }

    fun printNotifcation(
        sender: CommandSender?,
        s: String,
    ) = printNotifcation(sender, Component.text(s, NamedTextColor.DARK_GREEN))

    fun printNotifcation(
        sender: CommandSender?,
        component: Component,
    ) {
        if (sender === null) {
            println("Message called with null sender")
            return
        }
        val colored = component.colorIfAbsent(NamedTextColor.DARK_GREEN)
        if (sender is Player) {
            sender.sendNotification(Notification(colored, FrameType.TASK, Material.GREEN_STAINED_GLASS_PANE))
        } else {
            sender.sendMessage(colored)
        }
    }

    fun errorNotifcation(
        sender: CommandSender?,
        s: String,
    ) = errorNotifcation(sender, Component.text(s, NamedTextColor.RED))

    fun errorNotifcation(
        sender: CommandSender?,
        component: Component,
    ) {
        if (sender === null) {
            println("Message called with null sender")
            return
        }
        val colored = component.colorIfAbsent(NamedTextColor.RED)
        if (sender is Player) {
            sender.sendNotification(Notification(colored, FrameType.TASK, Material.BARRIER))
        } else {
            sender.sendMessage(colored)
        }
    }

    fun error(
        sender: CommandSender?,
        s: String,
    ) {
        if (sender === null) {
            println("Message called with null sender: $s")
            return
        }

        val msg = Component.text(s, NamedTextColor.RED)
        sender.sendMessage(msg)
    }

    fun announcement(
        player: Player,
        s: String,
    ) {
        player.sendActionBar(Component.text(s))
    }

    fun progressBar(
        progress: Double,
        color1: TextColor = NamedTextColor.GRAY,
        color2: TextColor = NamedTextColor.DARK_GRAY,
    ): Component =
        when (
            (
                progress *
                    10.0
            ).roundToInt()
        ) {
            0 -> Component.text("..........").color(color2)
            1 -> Component.text(".").color(color1).append(Component.text(".........", color2))
            2 -> Component.text("..").color(color1).append(Component.text("........", color2))
            3 -> Component.text("...").color(color1).append(Component.text(".......", color2))
            4 -> Component.text("....").color(color1).append(Component.text("......", color2))
            5 -> Component.text(".....").color(color1).append(Component.text(".....", color2))
            6 -> Component.text("......").color(color1).append(Component.text("....", color2))
            7 -> Component.text(".......").color(color1).append(Component.text("...", color2))
            8 -> Component.text("........").color(color1).append(Component.text("..", color2))
            9 -> Component.text(".........").color(color1).append(Component.text(".", color2))
            10 -> Component.text("..........").color(color1)
            else -> Component.empty()
        }
}
