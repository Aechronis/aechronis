package io.github.openminigameserver.worldedit.platform.actors

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.sk89q.worldedit.blocks.BaseItemStack
import com.sk89q.worldedit.entity.BaseEntity
import com.sk89q.worldedit.extension.platform.AbstractPlayerActor
import com.sk89q.worldedit.extent.inventory.BlockBag
import com.sk89q.worldedit.session.SessionKey
import com.sk89q.worldedit.util.HandSide
import com.sk89q.worldedit.util.Location
import com.sk89q.worldedit.util.formatting.WorldEditText
import com.sk89q.worldedit.util.formatting.text.Component
import com.sk89q.worldedit.util.formatting.text.TextComponent
import com.sk89q.worldedit.util.formatting.text.format.TextColor
import com.sk89q.worldedit.util.formatting.text.serializer.gson.GsonComponentSerializer
import com.sk89q.worldedit.world.World
import io.github.openminigameserver.worldedit.platform.MinestomPlatform
import io.github.openminigameserver.worldedit.platform.adapters.MinestomAdapter
import io.github.openminigameserver.worldedit.platform.adapters.MinestomPermissionsProvider
import io.github.openminigameserver.worldedit.platform.misc.SessionKeyImpl
import net.minestom.server.chat.JsonMessage
import net.minestom.server.entity.Player
import java.util.*

class MinestomPlayer(private val platform: MinestomPlatform, private val player: Player) : AbstractPlayerActor() {
    override fun getUniqueId(): UUID {
        return player.uuid
    }

    override fun getGroups(): Array<String> {
        return emptyArray()
    }

    override fun hasPermission(permission: String): Boolean {
        return MinestomPermissionsProvider.hasPermission(player, permission)
    }

    override fun getSessionKey(): SessionKey {
        return SessionKeyImpl(player.uuid, player.username)
    }

    override fun getName(): String {
        return player.username
    }

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
        player.sendMessage(object : JsonMessage() {
            override fun getJsonObject(): JsonObject {
                return gson.toJsonTree(newComponent).asJsonObject
            }
        })
    }

    override fun printError(msg: String) {
        sendColorized(msg, TextColor.RED)
    }

    override fun getLocale(): Locale {
        return Locale.getDefault()
    }

    override fun <T : Any?> getFacet(cls: Class<out T>?): T? {
        return null
    }

    override fun getLocation(): Location {
        return MinestomAdapter.asLocation(world, player.position)
    }

    override fun setLocation(location: Location): Boolean {
        player.teleport(MinestomAdapter.toPosition(location))
        return true
    }

    override fun getState(): BaseEntity? {
        TODO("Not yet implemented")
    }

    override fun getWorld(): World {
        return MinestomAdapter.asWorld(player.instance!!)
    }

    override fun getItemInHand(handSide: HandSide?): BaseItemStack {
        val item = when (handSide) {
            HandSide.OFF_HAND -> player.itemInOffHand
            else -> player.inventory.itemInMainHand
        }
        return MinestomAdapter.asBaseItemStack(item)
    }

    override fun giveItem(itemStack: BaseItemStack) {
        player.inventory.addItemStack(MinestomAdapter.toItemStack(itemStack))
    }

    override fun getInventoryBlockBag(): BlockBag? {
        return null
    }

    private fun sendColorized(msg: String, formatting: TextColor) {
        for (part in msg.split("\n").toTypedArray()) {
            print(TextComponent.of(part, formatting))
        }
    }
}