package net.aechronis.viaversion

import com.viaversion.viaversion.api.type.Types
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.ByteToMessageDecoder
import io.netty.handler.codec.MessageToByteEncoder
import io.netty.handler.codec.MessageToMessageCodec
import java.util.zip.Deflater
import java.util.zip.Inflater
import javax.crypto.Cipher

/** Minecraft framing and transport only; all protocol mappings live in the Via dependencies. */
internal object MinecraftWireCodec {
    class FrameDecoder(
        private val limit: () -> Int,
    ) : ByteToMessageDecoder() {
        override fun decode(
            ctx: ChannelHandlerContext,
            input: ByteBuf,
            output: MutableList<Any>,
        ) {
            input.markReaderIndex()
            var length = 0
            for (i in 0 until 3) {
                if (!input.isReadable) {
                    input.resetReaderIndex()
                    return
                }
                val next = input.readUnsignedByte().toInt()
                length = length or ((next and 127) shl (7 * i))
                if (next and 128 == 0) {
                    require(length > 0 && length <= limit()) { "Invalid packet length: $length" }
                    if (input.readableBytes() < length) {
                        input.resetReaderIndex()
                        return
                    }
                    output.add(input.readRetainedSlice(length))
                    return
                }
            }
            throw IllegalArgumentException("Packet length exceeds three bytes")
        }
    }

    class FrameEncoder : MessageToByteEncoder<ByteBuf>() {
        override fun encode(
            ctx: ChannelHandlerContext,
            input: ByteBuf,
            output: ByteBuf,
        ) {
            val length = input.readableBytes()
            require(length > 0 && length < (1 shl 21)) { "Invalid outgoing packet length: $length" }
            Types.VAR_INT.writePrimitive(output, length)
            output.writeBytes(input)
        }
    }

    fun decompress(
        input: ByteBuf,
        limit: Int,
    ): ByteBuf {
        val length = Types.VAR_INT.readPrimitive(input)
        if (length == 0) return input.readRetainedSlice(input.readableBytes())
        require(length in 0..limit) { "Invalid decompressed packet length: $length" }
        val compressed = ByteArray(input.readableBytes())
        input.readBytes(compressed)
        val expanded = ByteArray(length)
        val inflater = Inflater()
        try {
            inflater.setInput(compressed)
            val written = inflater.inflate(expanded)
            require(written == length && inflater.finished() && inflater.remaining == 0) { "Invalid compressed packet" }
            return Unpooled.wrappedBuffer(expanded)
        } finally {
            inflater.end()
        }
    }

    class Compression(
        private val limit: () -> Int,
    ) : MessageToMessageCodec<ByteBuf, ByteBuf>() {
        private var threshold = -1

        fun enable(threshold: Int) {
            this.threshold = threshold
        }

        override fun decode(
            ctx: ChannelHandlerContext,
            input: ByteBuf,
            output: MutableList<Any>,
        ) {
            output.add(if (threshold < 0) input.retain() else decompress(input, limit()))
        }

        override fun encode(
            ctx: ChannelHandlerContext,
            input: ByteBuf,
            output: MutableList<Any>,
        ) {
            if (threshold < 0) {
                output.add(input.retain())
                return
            }
            val result = ctx.alloc().buffer()
            try {
                if (input.readableBytes() < threshold) {
                    Types.VAR_INT.writePrimitive(result, 0)
                    result.writeBytes(input)
                } else {
                    val bytes = ByteArray(input.readableBytes())
                    input.readBytes(bytes)
                    Types.VAR_INT.writePrimitive(result, bytes.size)
                    val deflater = Deflater()
                    try {
                        deflater.setInput(bytes)
                        deflater.finish()
                        val block = ByteArray(8192)
                        while (!deflater.finished()) result.writeBytes(block, 0, deflater.deflate(block))
                    } finally {
                        deflater.end()
                    }
                }
                output.add(result.retain())
            } finally {
                result.release()
            }
        }
    }

    class Encryption(
        private val encrypt: Cipher,
        private val decrypt: Cipher,
    ) : MessageToMessageCodec<ByteBuf, ByteBuf>() {
        private fun apply(
            cipher: Cipher,
            input: ByteBuf,
        ): ByteBuf {
            val bytes = ByteArray(input.readableBytes())
            input.readBytes(bytes)
            return Unpooled.wrappedBuffer(cipher.update(bytes))
        }

        override fun decode(
            ctx: ChannelHandlerContext,
            input: ByteBuf,
            output: MutableList<Any>,
        ) {
            output.add(apply(decrypt, input))
        }

        override fun encode(
            ctx: ChannelHandlerContext,
            input: ByteBuf,
            output: MutableList<Any>,
        ) {
            output.add(apply(encrypt, input))
        }
    }
}
