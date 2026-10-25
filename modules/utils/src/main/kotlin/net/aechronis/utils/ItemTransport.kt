package net.aechronis.utils

import net.kyori.adventure.nbt.BinaryTagIO
import net.minestom.server.item.ItemStack
import java.io.ByteArrayOutputStream

object ItemTransport {
    const val MAX_ITEM_NBT_BYTES = 32 * 1024

    fun isSafeForTransport(item: ItemStack): Boolean =
        runCatching {
            val output = ByteArrayOutputStream()
            BinaryTagIO.writer().writeNameless(item.toItemNBT(), output)
            output.size() <= MAX_ITEM_NBT_BYTES
        }.getOrDefault(false)
}
