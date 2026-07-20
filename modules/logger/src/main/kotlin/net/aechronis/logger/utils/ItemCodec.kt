package net.aechronis.logger.utils

import net.kyori.adventure.nbt.BinaryTagIO
import net.kyori.adventure.nbt.CompoundBinaryTag
import net.minestom.server.item.ItemStack
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

object ItemCodec {
    fun encodeBlockNbt(nbt: CompoundBinaryTag?): ByteArray? {
        if (nbt == null) return null
        val out = ByteArrayOutputStream()
        BinaryTagIO.writer().writeNameless(nbt, out)
        return out.toByteArray()
    }

    fun decodeBlockNbt(bytes: ByteArray?): CompoundBinaryTag? {
        if (bytes == null) return null
        return BinaryTagIO.reader().readNameless(ByteArrayInputStream(bytes))
    }

    fun encodeItem(item: ItemStack): ByteArray = encodeBlockNbt(item.toItemNBT())!!

    fun encodeInventory(items: List<ItemStack>): ByteArray {
        val builder = CompoundBinaryTag.builder().putInt("size", items.size)
        items.forEachIndexed { index, item -> builder.put(index.toString(), item.toItemNBT()) }
        return encodeBlockNbt(builder.build())!!
    }

    fun decodeInventory(bytes: ByteArray): List<ItemStack> {
        val root = decodeBlockNbt(bytes) ?: return emptyList()
        val size = root.getInt("size")
        return (0 until size).map { index ->
            if (root.contains(index.toString())) ItemStack.fromItemNBT(root.getCompound(index.toString())) else ItemStack.AIR
        }
    }
}
