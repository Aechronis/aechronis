package io.github.openminigameserver.worldedit.platform.adapters

import com.sk89q.jnbt.CompoundTag
import com.sk89q.jnbt.NBTInputStream
import com.sk89q.jnbt.NBTOutputStream
import com.sk89q.jnbt.Tag
import com.sk89q.worldedit.blocks.BaseItemStack
import com.sk89q.worldedit.extension.platform.Actor
import com.sk89q.worldedit.math.Vector3
import com.sk89q.worldedit.util.Location
import com.sk89q.worldedit.world.World
import com.sk89q.worldedit.world.item.ItemType
import io.github.openminigameserver.worldedit.platform.MinestomPlatform
import io.github.openminigameserver.worldedit.platform.actors.MinestomConsole
import net.minestom.server.command.CommandSender
import net.minestom.server.entity.Player
import net.minestom.server.instance.Instance
import net.minestom.server.registry.Registries
import net.minestom.server.utils.BlockPosition
import net.minestom.server.utils.NBTUtils
import org.jglrxavpok.hephaistos.nbt.NBTCompound
import org.jglrxavpok.hephaistos.nbt.NBTReader
import org.jglrxavpok.hephaistos.nbt.NBTWriter
import java.io.ByteArrayOutputStream
import com.sk89q.worldedit.math.BlockVector3 as WorldEditBlockVector3
import com.sk89q.worldedit.util.Direction as WorldEditDirection
import net.minestom.server.instance.block.BlockFace as MinestomBlockFace
import net.minestom.server.item.ItemStack as MinestomItemStack
import net.minestom.server.utils.Position as MinestomPosition
import org.jglrxavpok.hephaistos.nbt.NBT as MinestomNBT

object MinestomAdapter {
    lateinit var platform: MinestomPlatform

    fun asBlockVector(position: MinestomPosition): WorldEditBlockVector3 {
        return WorldEditBlockVector3.at(position.x.toDouble(), position.y.toDouble(), position.z.toDouble())
    }

    fun asBlockPosition(position: WorldEditBlockVector3): BlockPosition {
        return BlockPosition(position.x, position.y, position.z)
    }

    fun asLocation(world: World, position: MinestomPosition): Location {
        return Location(
            world,
            position.x.toDouble(),
            position.y.toDouble(),
            position.z.toDouble(),
            position.yaw,
            position.pitch
        )
    }

    fun asDirection(blockFace: MinestomBlockFace): WorldEditDirection {
        return WorldEditDirection.valueOf(blockFace.toDirection().name)
    }

    fun asTag(nbt: MinestomNBT): Tag {
        val byteArrayOutputStream = ByteArrayOutputStream()
        val output = NBTWriter(byteArrayOutputStream, false).use {
            it.writeNamed("value", nbt)
            byteArrayOutputStream.toByteArray()
        }
        return NBTInputStream(output.inputStream()).readNamedTag().tag
    }

    fun asNBT(nbt: Tag): MinestomNBT {
        val byteArrayOutputStream = ByteArrayOutputStream()
        val output = NBTOutputStream(byteArrayOutputStream).use {
            it.writeNamedTag("value", nbt)
            byteArrayOutputStream.toByteArray()
        }
        return NBTReader(output.inputStream()).readNamed().second
    }

    fun asBaseItemStack(item: MinestomItemStack): BaseItemStack {
        return BaseItemStack(
            ItemType.REGISTRY[item.material.getName()],
            asTag(item.toNBT()) as CompoundTag,
            item.amount.toInt()
        )
    }

    fun asWorld(instance: Instance): World {
        return platform.getWorld(instance)
    }

    fun toPosition(location: Location): MinestomPosition {
        return MinestomPosition(
            location.x.toFloat(),
            location.y.toFloat(),
            location.z.toFloat(),
            location.yaw,
            location.pitch
        )
    }

    fun asActor(commandSender: CommandSender): Actor {
        if (commandSender is Player) {
            return platform.getPlayer(commandSender)
        }

        return MinestomConsole
    }

    fun toItemStack(itemStack: BaseItemStack): MinestomItemStack {
        val stack = MinestomItemStack(Registries.getMaterial(itemStack.type.id), itemStack.amount.toByte())
        if (itemStack.hasNbtData()) {
            NBTUtils.loadDataIntoItem(stack, asNBT(itemStack.nbtData!!) as NBTCompound)
        }
        return stack
    }

    fun toPosition(location: Vector3): MinestomPosition {
        return MinestomPosition(location.x.toFloat(), location.y.toFloat(), location.z.toFloat())
    }
}

