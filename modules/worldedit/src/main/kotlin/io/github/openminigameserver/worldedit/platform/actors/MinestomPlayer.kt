package io.github.openminigameserver.worldedit.platform.actors

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
import io.github.openminigameserver.worldedit.platform.misc.SessionKeyImpl
import net.aechronis.utils.hasPermission
import net.minestom.server.entity.EquipmentSlot
import net.minestom.server.entity.Player
import java.util.Locale
import java.util.UUID
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer as AdventureGsonSerializer

class MinestomPlayer(
    private val platform: MinestomPlatform,
    private val player: Player,
) : AbstractPlayerActor() {
    override fun getUniqueId(): UUID = player.uuid

    override fun getGroups(): Array<String> = emptyArray()

    override fun hasPermission(permission: String): Boolean = player.hasPermission(permission)

    override fun getSessionKey(): SessionKey = SessionKeyImpl(player.uuid, player.username)

    override fun getName(): String = player.username

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
        player.sendMessage(AdventureGsonSerializer.gson().deserialize(json))
    }

    override fun printError(msg: String) {
        sendColorized(msg, TextColor.RED)
    }

    override fun getLocale(): Locale = Locale.getDefault()

    override fun <T : Any?> getFacet(cls: Class<out T>?): T? = null

    override fun getLocation(): Location = MinestomAdapter.asLocation(world, player.position)

    override fun setLocation(location: Location): Boolean {
        player.teleport(MinestomAdapter.toPosition(location))
        return true
    }

    override fun getState(): BaseEntity? {
        TODO("Not yet implemented")
    }

    override fun getWorld(): World = MinestomAdapter.asWorld(player.instance!!)

    override fun getItemInHand(handSide: HandSide?): BaseItemStack {
        val item =
            when (handSide) {
                HandSide.OFF_HAND -> player.getEquipment(EquipmentSlot.OFF_HAND)
                else -> player.getEquipment(EquipmentSlot.MAIN_HAND)
            }
        return MinestomAdapter.asBaseItemStack(item)
    }

    override fun giveItem(itemStack: BaseItemStack) {
        player.inventory.addItemStack(MinestomAdapter.toItemStack(itemStack))
    }

    override fun getInventoryBlockBag(): BlockBag? = null

    private fun sendColorized(
        msg: String,
        formatting: TextColor,
    ) {
        for (part in msg.split("\n").toTypedArray()) {
            print(TextComponent.of(part, formatting))
        }
    }
}
