package io.github.openminigameserver.worldedit.platform.actors

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.sk89q.worldedit.extension.platform.AbstractNonPlayerActor
import com.sk89q.worldedit.session.SessionKey
import com.sk89q.worldedit.util.formatting.WorldEditText
import com.sk89q.worldedit.util.formatting.text.Component
import com.sk89q.worldedit.util.formatting.text.TextComponent
import com.sk89q.worldedit.util.formatting.text.format.TextColor
import com.sk89q.worldedit.util.formatting.text.serializer.gson.GsonComponentSerializer
import io.github.openminigameserver.worldedit.platform.misc.SessionKeyImpl
import net.minestom.server.MinecraftServer
import net.minestom.server.chat.JsonMessage
import java.util.*

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

    val gson: Gson = GsonComponentSerializer.populate(GsonBuilder()).create()
    override fun print(component: Component) {
        val newComponent = WorldEditText.format(component, locale)
        consoleSender.sendMessage(object : JsonMessage() {
            override fun getJsonObject(): JsonObject {
                return gson.toJsonTree(newComponent).asJsonObject
            }
        })
    }

    override fun printError(msg: String) {
        sendColorized(msg, TextColor.RED)
    }

    private fun sendColorized(msg: String, formatting: TextColor) {
        for (part in msg.split("\n").toTypedArray()) {
            print(TextComponent.of(part, formatting))
        }
    }

    override fun getLocale(): Locale {
        return Locale.getDefault()
    }
}