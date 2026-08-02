package io.github.openminigameserver.worldedit.platform.actors

import com.sk89q.worldedit.extension.platform.AbstractNonPlayerActor
import com.sk89q.worldedit.session.SessionKey
import com.sk89q.worldedit.util.formatting.WorldEditText
import com.sk89q.worldedit.util.formatting.text.Component
import com.sk89q.worldedit.util.formatting.text.TextComponent
import com.sk89q.worldedit.util.formatting.text.format.TextColor
import com.sk89q.worldedit.util.formatting.text.serializer.gson.GsonComponentSerializer
import io.github.openminigameserver.worldedit.platform.misc.SessionKeyImpl
import net.minestom.server.MinecraftServer
import java.util.Locale
import java.util.UUID
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer as AdventureGsonSerializer

object MinestomConsole : AbstractNonPlayerActor() {
    private val consoleSender = MinecraftServer.getCommandManager().consoleSender
    private val emptyUUID = UUID(0, 0)

    override fun getUniqueId(): UUID = emptyUUID

    override fun getGroups(): Array<String> = emptyArray()

    override fun checkPermission(permission: String?) {
    }

    override fun hasPermission(permission: String?): Boolean = true

    override fun getSessionKey(): SessionKey = SessionKeyImpl(uniqueId, name)

    override fun getName(): String = "CONSOLE"

    override fun printRaw(msg: String) {
        sendColorized(msg, TextColor.YELLOW)
    }

    override fun printDebug(msg: String) {
        sendColorized(msg, TextColor.YELLOW)
    }

    override fun print(msg: String) {
        sendColorized(msg, TextColor.WHITE)
    }

    override fun print(component: Component) {
        val newComponent = WorldEditText.format(component, locale)
        val json = GsonComponentSerializer.INSTANCE.serialize(newComponent)
        consoleSender.sendMessage(AdventureGsonSerializer.gson().deserialize(json))
    }

    override fun printError(msg: String) {
        sendColorized(msg, TextColor.RED)
    }

    private fun sendColorized(
        msg: String,
        formatting: TextColor,
    ) {
        for (part in msg.split("\n").toTypedArray()) {
            print(TextComponent.of(part, formatting))
        }
    }

    override fun getLocale(): Locale = Locale.getDefault()
}
