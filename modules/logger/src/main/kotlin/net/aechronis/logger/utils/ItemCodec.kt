package net.aechronis.logger.utils

import net.kyori.adventure.nbt.BinaryTagIO
import net.kyori.adventure.nbt.CompoundBinaryTag
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
}
