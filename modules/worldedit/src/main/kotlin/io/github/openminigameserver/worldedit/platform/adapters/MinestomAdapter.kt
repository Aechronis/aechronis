package io.github.openminigameserver.worldedit.platform.adapters

import com.sk89q.jnbt.CompoundTag
import com.sk89q.jnbt.NBTInputStream
import com.sk89q.jnbt.NBTOutputStream
import com.sk89q.worldedit.blocks.BaseItemStack
import com.sk89q.worldedit.extension.platform.Actor
import com.sk89q.worldedit.math.Vector3
import com.sk89q.worldedit.util.Location
import com.sk89q.worldedit.world.World
import com.sk89q.worldedit.world.item.ItemType
import io.github.openminigameserver.worldedit.platform.MinestomPlatform
import io.github.openminigameserver.worldedit.platform.actors.MinestomConsole
import net.kyori.adventure.nbt.BinaryTagIO
import net.kyori.adventure.nbt.CompoundBinaryTag
import net.minestom.server.command.CommandSender
import net.minestom.server.component.DataComponents
import net.minestom.server.coordinate.Point
import net.minestom.server.entity.Player
import net.minestom.server.instance.Instance
import net.minestom.server.item.Material
import net.minestom.server.item.component.CustomData
import java.io.ByteArrayOutputStream
import com.sk89q.worldedit.math.BlockVector3 as WorldEditBlockVector3
import com.sk89q.worldedit.util.Direction as WorldEditDirection
import net.minestom.server.coordinate.BlockVec as MinestomBlockVec
import net.minestom.server.coordinate.Pos as MinestomPos
import net.minestom.server.instance.block.BlockFace as MinestomBlockFace
import net.minestom.server.item.ItemStack as MinestomItemStack

object MinestomAdapter {
    lateinit var platform: MinestomPlatform

    fun asBlockVector(position: Point): WorldEditBlockVector3 = WorldEditBlockVector3.at(position.x(), position.y(), position.z())

    fun asBlockPosition(position: WorldEditBlockVector3): MinestomBlockVec = MinestomBlockVec(position.x(), position.y(), position.z())

    fun asLocation(
        world: World,
        position: Point,
    ): Location {
        val pos = position.asPos()
        return Location(
            world,
            pos.x(),
            pos.y(),
            pos.z(),
            pos.yaw(),
            pos.pitch(),
        )
    }

    fun asDirection(blockFace: MinestomBlockFace): WorldEditDirection = WorldEditDirection.valueOf(blockFace.toDirection().name)

    fun asTag(nbt: CompoundBinaryTag): CompoundTag {
        val byteArrayOutputStream = ByteArrayOutputStream()
        BinaryTagIO.writer().write(nbt, byteArrayOutputStream)
        return NBTInputStream(byteArrayOutputStream.toByteArray().inputStream()).readNamedTag().tag as CompoundTag
    }

    fun asNBT(nbt: CompoundTag): CompoundBinaryTag {
        val byteArrayOutputStream = ByteArrayOutputStream()
        NBTOutputStream(byteArrayOutputStream).use {
            it.writeNamedTag("", nbt)
        }
        return BinaryTagIO.reader().read(byteArrayOutputStream.toByteArray().inputStream()) as CompoundBinaryTag
    }

    fun asBaseItemStack(item: MinestomItemStack): BaseItemStack {
        val customData = item.get(DataComponents.CUSTOM_DATA)
        return if (customData != null) {
            BaseItemStack(
                ItemType.REGISTRY[item.material().key().asString()],
                asTag(customData.nbt()),
                item.amount(),
            )
        } else {
            BaseItemStack(ItemType.REGISTRY[item.material().key().asString()], item.amount())
        }
    }

    fun asWorld(instance: Instance): World = platform.getWorld(instance)

    fun toPosition(location: Location): MinestomPos =
        MinestomPos(
            location.x,
            location.y,
            location.z,
            location.yaw,
            location.pitch,
        )

    fun asActor(commandSender: CommandSender): Actor {
        if (commandSender is Player) {
            return platform.getPlayer(commandSender)
        }

        return MinestomConsole
    }

    fun toItemStack(itemStack: BaseItemStack): MinestomItemStack {
        var stack = MinestomItemStack.of(Material.fromKey(itemStack.type.id()), itemStack.amount)
        if (itemStack.hasNbtData()) {
            stack = stack.with(DataComponents.CUSTOM_DATA, CustomData(asNBT(itemStack.nbtData!!)))
        }
        return stack
    }

    fun toPosition(location: Vector3): MinestomPos = MinestomPos(location.x(), location.y(), location.z())
}
